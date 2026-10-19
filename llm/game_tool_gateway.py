"""Loopback-only, allow-listed MCP forwarding and player-view tracing."""

import json
import threading
import time
import urllib.error
import urllib.request
from contextlib import nullcontext
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from llm.player_view import PlayerView


def validate_loopback_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
        raise ValueError("The game bridge must be an HTTP endpoint on 127.0.0.1")


def rpc_error(request, code, message):
    return {"jsonrpc": "2.0", "id": request.get("id"), "error": {"code": code, "message": message}}


def finished_response(request):
    result = {"game_over": True, "message": "This game has ended. Report your result briefly and stop."}
    text = json.dumps(result, separators=(",", ":"))
    return {
        "jsonrpc": "2.0",
        "id": request.get("id"),
        "result": {"content": [{"type": "text", "text": text}], "structuredContent": result, "isError": False},
    }


class GameToolGateway:
    def __init__(
        self, upstream, allowed_tools, trace_path, max_calls=400, *, optimize_play=False, upstream_timeout=180
    ):
        validate_loopback_url(upstream)
        if upstream_timeout <= 0:
            raise ValueError("The native bridge timeout must be positive")
        self.upstream = upstream
        self.upstream_timeout = upstream_timeout
        self.allowed = set(allowed_tools)
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
                    if response is None and is_call and gateway.game_finished:
                        response = finished_response(payload)
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
                        if is_call and gateway.view and record["outcome"] != "game_finished"
                        else response
                    )
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
                if gateway.view and outgoing != record["response"]:
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

    def record_call(self, record):
        with self.lock, self.trace_path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, separators=(",", ":")) + "\n")
            self.failed_calls += record["outcome"] != "ok"
            self.upstream_response_bytes += record.get("upstream_response_bytes", 0)
            self.model_response_bytes += record["model_response_bytes"]

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

    def finish_game(self):
        """Answer every later game call with game_over, so this player never acts in the match's next game."""
        self.game_finished = True

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
