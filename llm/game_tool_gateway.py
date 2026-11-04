"""Loopback-only, allow-listed MCP forwarding and player-view tracing."""

import json
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from contextlib import nullcontext
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from llm.player_view import PlayerView, action_payload, replace_payload

# A tool the gateway answers itself: the model tells us when the game tools kept it from doing what it
# wanted. Nothing reaches the bridge or the game; the note is saved in the seat's run folder.
REPORT_TOOL = "report_problem"
# The answer to the end-of-game question that says nothing went wrong.
NO_PROBLEM = "none"
MAX_REPORTS = 20
RECENT_CALLS = 5
RESPONSE_EXCERPT = 4000
GAME_OVER_KEYS = ("game_over", "player_dead")
END_QUESTION = (
    "Your game is over. Before you stop, one question: did the game tools let you do everything you wanted "
    f"this game? Call {REPORT_TOOL} once for each problem you have not reported yet, or once with "
    f'problem="{NO_PROBLEM}" if everything worked. Then report your result briefly and stop.'
)
REPORT_TOOL_DEFINITION = {
    "name": REPORT_TOOL,
    "description": (
        "Report a problem with the game tools, right when it happens: a legal choice you needed was missing, "
        "a choice was made for you that you did not want, or an action was refused or did something other "
        "than what it said. Name the card, what you tried and what happened instead, then keep playing. "
        "It does not change the game and nobody in the game sees it; it is saved for the people who fix "
        "these tools. Do not use it for your own mistakes, for rules questions or for commentary. "
        f'At the end of the game, problem="{NO_PROBLEM}" says everything worked.'
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "problem": {
                "type": "string",
                "description": (
                    "The card or prompt, what you wanted to do, and what the game tools did instead; "
                    f'or "{NO_PROBLEM}" at the end of the game if everything worked.'
                ),
            }
        },
        "required": ["problem"],
        "additionalProperties": False,
    },
    "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False, "idempotentHint": False},
}


def validate_loopback_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
        raise ValueError("The game bridge must be an HTTP endpoint on 127.0.0.1")


def rpc_error(request, code, message):
    return {"jsonrpc": "2.0", "id": request.get("id"), "error": {"code": code, "message": message}}


def tool_response(request, result, *, is_error=False):
    text = json.dumps(result, separators=(",", ":"))
    return {
        "jsonrpc": "2.0",
        "id": request.get("id"),
        "result": {"content": [{"type": "text", "text": text}], "structuredContent": result, "isError": is_error},
    }


def finished_response(request, question=None):
    result = {"game_over": True, "message": "This game has ended. Report your result briefly and stop."}
    if question:
        result["bridge_check"] = question
    return tool_response(request, result)


def response_excerpt(response):
    """What the model was shown, cut short: the text of a tool result, or the JSON of an error."""
    if not isinstance(response, dict):
        return None
    result = response.get("result")
    if isinstance(result, dict):
        texts = [part.get("text", "") for part in result.get("content", []) if part.get("type") == "text"]
        text = "\n".join(texts)
    else:
        text = json.dumps(response, separators=(",", ":"), ensure_ascii=False)
    return text[:RESPONSE_EXCERPT]


class GameToolGateway:
    def __init__(
        self,
        upstream,
        allowed_tools,
        trace_path,
        max_calls=400,
        *,
        optimize_play=False,
        upstream_timeout=180,
        describe=None,
        report_path=None,
    ):
        """upstream is the bridge's loopback URL, or an object whose forward(payload, timeout) answers.
        With report_problem among the allowed tools, the model's problem reports go to report_path
        (problem-reports.jsonl next to the trace by default)."""
        if isinstance(upstream, str):
            validate_loopback_url(upstream)
        if upstream_timeout <= 0:
            raise ValueError("The native bridge timeout must be positive")
        self.upstream = upstream
        self.upstream_timeout = upstream_timeout
        self.allowed = set(allowed_tools)
        self.describe = describe
        if optimize_play and "pass_priority" not in self.allowed:
            raise ValueError("Efficient GUI play requires the pass_priority tool")
        self.view = PlayerView() if optimize_play else None
        self.trace_path = trace_path
        self.max_calls = max_calls
        self.call_count = 0
        self.violations = []
        self.lock = threading.Lock()
        self.operation_lock = threading.Lock() if optimize_play else nullcontext()
        self.failed_calls = 0
        self.fatal_error = None
        self.upstream_response_bytes = 0
        self.model_response_bytes = 0
        self.game_finished = False
        self.reports = REPORT_TOOL in self.allowed
        self.report_path = report_path or trace_path.with_name("problem-reports.jsonl")
        self.problem_reports = 0
        self.said_all_worked = False
        self.saved_reports = 0
        self.end_question_asked = False
        # The last game calls, saved with a report so it shows what the model was looking at.
        self.recent_calls = deque(maxlen=RECENT_CALLS)
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                self.send_response(405)
                self.end_headers()

            def do_DELETE(self):
                self.send_response(202)
                self.end_headers()

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                with gateway.operation_lock:
                    self.exchange(payload)

            def exchange(self, payload):
                method = payload.get("method")
                is_call = method == "tools/call"
                params = payload.get("params", {})
                started = time.monotonic()
                record = {
                    "ts": datetime.now(UTC).isoformat(),
                    "request_id": payload.get("id"),
                    "tool": params.get("name"),
                    "arguments": params.get("arguments", {}),
                    "effective_tool": params.get("name"),
                    "outcome": "ok",
                }
                status = 200
                response = None
                try:
                    if is_call:
                        with gateway.lock:
                            gateway.call_count += 1
                            count = gateway.call_count
                            if params.get("name") not in gateway.allowed:
                                gateway.violations.append(params.get("name"))
                        if params.get("name") not in gateway.allowed or count > gateway.max_calls:
                            response = rpc_error(payload, -32601, "Tool not permitted or pilot call limit exceeded")
                            record["outcome"] = "rejected"
                    if response is None and is_call and params.get("name") == REPORT_TOOL:
                        # Answered here, also after the game ended: that is when the model is asked.
                        response = gateway.report(payload)
                        record["outcome"] = "reported"
                    if response is None and is_call and gateway.game_finished:
                        response = finished_response(payload, gateway.ask_end_question())
                        record["outcome"] = "game_finished"
                    if response is None:
                        effective = gateway.view.prepare_request(payload) if is_call and gateway.view else payload
                        if is_call:
                            record["effective_tool"] = effective["params"]["name"]
                            record["effective_arguments"] = effective["params"].get("arguments", {})
                        status, raw = gateway.forward_request(effective)
                        record["upstream_response_bytes"] = len(raw)
                        response = json.loads(raw) if raw else None
                        if is_call and not isinstance(response, dict):
                            raise ValueError("Missing native tool response")
                        if is_call and "error" not in response and not isinstance(response.get("result"), dict):
                            raise ValueError("Invalid native tool result")
                    record["response"] = response
                    answered = response
                    if (
                        is_call
                        and record["outcome"] == "ok"
                        and gateway.view
                        and gateway.view.needs_auto_pass(record["effective_tool"], response)
                    ):
                        # Nothing is playable after this action, so its window is passed without a model turn.
                        passing = {
                            "jsonrpc": "2.0",
                            "id": payload.get("id"),
                            "method": "tools/call",
                            "params": {"name": "pass_priority", "arguments": {}},
                        }
                        _pass_status, raw_pass = gateway.forward_request(passing)
                        record["upstream_response_bytes"] += len(raw_pass)
                        passed = json.loads(raw_pass) if raw_pass else None
                        if not isinstance(passed, dict) or (
                            "error" not in passed and not isinstance(passed.get("result"), dict)
                        ):
                            raise ValueError("Invalid native pass result")
                        record["auto_pass_response"] = passed
                        answered = gateway.view.merge_auto_pass(response, passed)
                    if method == "tools/list" and response and "result" in response:
                        gateway.describe_tools(response)
                    if (
                        is_call
                        and record["outcome"] == "ok"
                        and ("error" in response or response.get("result", {}).get("isError"))
                    ):
                        record["outcome"] = "upstream_error"
                    outgoing = (
                        gateway.view.model_response(params["name"], answered)
                        if is_call and gateway.view and record["outcome"] not in {"game_finished", "reported"}
                        else response
                    )
                    if is_call and record["outcome"] == "ok":
                        outgoing = gateway.with_end_question(outgoing)
                except (TimeoutError, urllib.error.URLError, ConnectionError, OSError, ValueError) as error:
                    if gateway.view:
                        gateway.view.invalidate_action()
                    if isinstance(error, TimeoutError) or (
                        isinstance(error, urllib.error.URLError) and isinstance(error.reason, TimeoutError)
                    ):
                        record["outcome"] = "timeout"
                    elif isinstance(error, ValueError):
                        record["outcome"] = "invalid_response"
                        gateway.fatal_error = "The native bridge returned invalid tool data; see player-tools.jsonl"
                    else:
                        record["outcome"] = "transport_error"
                    outgoing = rpc_error(
                        payload,
                        -32603,
                        "Native bridge request failed; an action may already have been applied. "
                        "Refresh choices before deciding what to do next; do not blindly repeat the action.",
                    )
                    record["response"] = outgoing
                    status = 200
                body = json.dumps(outgoing, separators=(",", ":"), ensure_ascii=False).encode() if outgoing else b""
                record["model_response_bytes"] = len(body)
                if outgoing != record["response"]:
                    record["model_response"] = outgoing
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    record["outcome"] = "client_disconnected"
                finally:
                    if is_call:
                        record["latency_seconds"] = round(time.monotonic() - started, 3)
                        gateway.record_call(record)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def forward_request(self, payload):
        if not isinstance(self.upstream, str):
            return self.upstream.forward(payload, self.upstream_timeout)
        request = urllib.request.Request(  # noqa: S310 - upstream is restricted to loopback HTTP
            self.upstream, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
        )
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(request, timeout=self.upstream_timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def describe_tools(self, response):
        tools = [tool for tool in response["result"]["tools"] if tool["name"] in self.allowed]
        response["result"]["tools"] = tools
        for tool in tools:
            read_only = tool["name"].startswith("get_")
            tool["annotations"] = {
                "readOnlyHint": read_only,
                "destructiveHint": tool["name"] == "concede",
                "openWorldHint": False,
                "idempotentHint": read_only,
            }
            if self.view:
                self.view.describe_tool(tool)
            if self.describe:
                self.describe(tool)
            properties = (tool.get("outputSchema") or {}).get("properties")
            if self.reports and isinstance(properties, dict) and any(key in properties for key in GAME_OVER_KEYS):
                properties["bridge_check"] = {
                    "type": "string",
                    "description": "Asked once when your game ends: whether the game tools let you do everything",
                }
        if self.reports:
            tools.append(json.loads(json.dumps(REPORT_TOOL_DEFINITION)))

    def report(self, request):
        """Save the model's problem report with the calls that led to it, and tell the model it was saved."""
        arguments = request.get("params", {}).get("arguments") or {}
        problem = arguments.get("problem") if isinstance(arguments, dict) else None
        if not isinstance(problem, str) or not problem.strip():
            return tool_response(
                request, {"saved": False, "message": "Describe the problem in the problem argument."}, is_error=True
            )
        problem = problem.strip()
        all_worked = problem.rstrip(".!").strip().lower() == NO_PROBLEM
        with self.lock:
            if self.saved_reports >= MAX_REPORTS:
                return tool_response(
                    request, {"saved": False, "message": "This game's report limit is reached. Keep playing."}
                )
            entry = {
                "ts": datetime.now(UTC).isoformat(),
                "call": self.call_count,
                "after_game_end": self.game_finished or self.end_question_asked,
                "all_worked": all_worked,
                "problem": problem[:RESPONSE_EXCERPT],
                "recent_calls": list(self.recent_calls),
            }
            try:
                with self.report_path.open("a", encoding="utf-8") as file:
                    file.write(json.dumps(entry, separators=(",", ":"), ensure_ascii=False) + "\n")
            except OSError:
                # A report that cannot be saved must not stop the game.
                return tool_response(
                    request, {"saved": False, "message": "The report could not be saved. Keep playing."}
                )
            self.saved_reports += 1
            if all_worked:
                self.said_all_worked = True
            else:
                self.problem_reports += 1
        if self.game_finished or self.end_question_asked:
            message = "Saved, thank you. Report your result briefly and stop."
        else:
            message = "Saved, thank you. Keep playing."
        return tool_response(request, {"saved": True, "message": message})

    def ask_end_question(self):
        """The end-of-game question, the first time this game's end reaches the model; otherwise None."""
        if not self.reports:
            return None
        with self.lock:
            if self.end_question_asked:
                return None
            self.end_question_asked = True
        return END_QUESTION

    def with_end_question(self, response):
        """The bridge's own game over or elimination result, with the end-of-game question added once."""
        if not self.reports or self.end_question_asked:
            return response
        payload = action_payload(response)
        if payload is None or not any(payload.get(key) for key in GAME_OVER_KEYS):
            return response
        question = self.ask_end_question()
        if question is None:
            return response
        return replace_payload(response, {**payload, "bridge_check": question})

    def record_call(self, record):
        with self.lock, self.trace_path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, separators=(",", ":")) + "\n")
            self.failed_calls += record["outcome"] not in {"ok", "reported"}
            self.upstream_response_bytes += record.get("upstream_response_bytes", 0)
            self.model_response_bytes += record["model_response_bytes"]
            if record["tool"] != REPORT_TOOL:
                self.recent_calls.append(
                    {
                        "ts": record["ts"],
                        "tool": record["tool"],
                        "arguments": record["arguments"],
                        "outcome": record["outcome"],
                        "shown": response_excerpt(record.get("model_response", record.get("response"))),
                    }
                )

    def optimization_stats(self):
        return {
            "policy": "gui-efficient-v2" if self.view else "native",
            "batched_pass_attempts": self.view.batched_pass_attempts if self.view else 0,
            "auto_passes": self.view.auto_passes if self.view else 0,
            "omitted_boards": self.view.omitted_boards if self.view else 0,
            "omitted_rules": self.view.omitted_rules if self.view else 0,
            "failed_calls": self.failed_calls,
            "upstream_response_bytes": self.upstream_response_bytes,
            "model_response_bytes": self.model_response_bytes,
        }

    def new_player(self):
        """Another model process takes over this game (the previous one stopped mid-game)."""
        if self.view:
            self.view.new_player()

    def finish_game(self):
        """Answer every later game call with game_over, so this player never acts in the match's next game."""
        self.game_finished = True

    def resume_game(self):
        """A rollback brought this player back into the game it had been eliminated from."""
        self.game_finished = False

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
