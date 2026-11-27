"""What a model may say in the game chat, through the real gateway; no inference."""

import json
import threading
import time
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from llm.game_tool_gateway import (
    CHAT_LENGTH,
    CHAT_NOTE,
    CHAT_PER_TURN,
    CHAT_REMINDER,
    HELD_FOR_CHAT,
    GameToolGateway,
)
from llm.subscription_client import CHAT_TOOL, TOOLS

CHAT_TOOLS = [*TOOLS, CHAT_TOOL]


def envelope(value):
    return {"content": [{"type": "text", "text": json.dumps(value)}], "structuredContent": value, "isError": False}


def turn(number):
    """A decision on a new turn: the gateway counts chat messages by the turn the board shows."""
    return {
        "action_pending": True,
        "action_type": "GAME_SELECT",
        "response_type": "boolean",
        "choices": [{"index": 0, "description": "Cast Lightning Bolt"}],
        "context": f"T{number} PRECOMBAT_MAIN",
        "board": [{"name": "you", "hand": []}],
        "game_seq": number,
    }


@pytest.fixture
def table(tmp_path):
    @contextmanager
    def start(tools=CHAT_TOOLS):
        sent = []

        class Native(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                params = request.get("params", {})
                if request["method"] == "tools/list":
                    listed = [name for name in CHAT_TOOLS if name != "report_problem"]
                    result = {"tools": [{"name": name, "description": "Send a chat message to the game"}
                                        if name == CHAT_TOOL else {"name": name, "description": name}
                                        for name in listed]}
                    result["tools"][-1]["inputSchema"] = {
                        "type": "object",
                        "properties": {"message": {"type": "string", "description": "Message to send"}},
                    }
                elif params["name"] == CHAT_TOOL:
                    sent.append(params["arguments"]["message"])
                    result = envelope({"success": True})
                else:
                    result = envelope(turn(int(params["arguments"].get("turn", 1))))
                body = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        native = ThreadingHTTPServer(("127.0.0.1", 0), Native)
        thread = threading.Thread(target=native.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        gateway = GameToolGateway(
            f"http://127.0.0.1:{native.server_port}/mcp", tools, tmp_path / "player-tools.jsonl", optimize_play=True
        )
        number = 0

        def post(tool, arguments=None):
            nonlocal number
            number += 1
            payload = {
                "jsonrpc": "2.0",
                "id": number,
                "method": "tools/list" if tool == "list" else "tools/call",
                "params": {} if tool == "list" else {"name": tool, "arguments": arguments or {}},
            }
            request = urllib.request.Request(gateway.url, data=json.dumps(payload).encode())
            with urllib.request.urlopen(request, timeout=5) as response:
                return json.loads(response.read())

        def say(message):
            result = post(CHAT_TOOL, {"message": message})["result"]
            return None if not result["isError"] else result["structuredContent"]["error"]

        try:
            yield gateway, post, say, sent
        finally:
            gateway.close()
            native.shutdown()
            native.server_close()
            thread.join(timeout=3)

    return start


def test_the_chat_tool_says_its_limits(table):
    with table() as (_gateway, post, _say, _sent):
        tools = {tool["name"]: tool for tool in post("list")["result"]["tools"]}
    chat = tools[CHAT_TOOL]
    assert f"at most {CHAT_LENGTH} characters" in chat["description"]
    assert chat["inputSchema"]["properties"]["message"]["maxLength"] == CHAT_LENGTH
    assert chat["annotations"]["destructiveHint"] is False


def test_a_seat_without_chat_is_not_offered_the_tool_and_may_not_use_it(table):
    with table(tools=TOOLS) as (gateway, post, _say, sent):
        assert CHAT_TOOL not in {tool["name"] for tool in post("list")["result"]["tools"]}
        assert "error" in post(CHAT_TOOL, {"message": "hello"})
    assert sent == []
    assert gateway.violations == [CHAT_TOOL]


def test_a_message_reaches_the_game_as_one_line_of_plain_text(table):
    with table() as (gateway, _post, say, sent):
        assert say("  good luck,\nhave fun​\t!  ") is None
    assert sent == ["good luck, have fun !"]
    assert gateway.chat_messages == 1


@pytest.mark.parametrize(
    ("message", "why"),
    [
        ("", "empty"),
        (" \n ", "empty"),
        ("x" * (CHAT_LENGTH + 1), f"{CHAT_LENGTH} characters"),
        ("\\w Opponent psst", "can't start with"),
        ("/me concedes", "can't start with"),
        ("~nxart1 card art", "can't start with"),
        ("<b>gg</b>", "Plain text only"),
        (42, "as text"),
    ],
)
def test_a_message_that_is_not_plain_short_talk_is_not_sent(table, message, why):
    with table() as (gateway, _post, say, sent):
        assert why in say(message)
    assert sent == []
    assert gateway.chat_messages == 0


def chat_with_a_decision(tmp_path, tools):
    decision = {**turn(3), "recent_chat": ["Host: build a ski resort?"]}

    class Native:
        def forward(self, payload, _timeout):
            body = {"jsonrpc": "2.0", "id": payload["id"], "result": envelope(decision)}
            return 200, json.dumps(body).encode()

    gateway = GameToolGateway(Native(), tools, tmp_path / "player-tools.jsonl", optimize_play=True)
    try:
        payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": "pass_priority", "arguments": {}}}
        request = urllib.request.Request(gateway.url, data=json.dumps(payload).encode())
        with urllib.request.urlopen(request, timeout=5) as response:
            result = json.loads(response.read())["result"]
    finally:
        gateway.close()
    assert json.loads(result["content"][0]["text"]) == result["structuredContent"]
    return result["structuredContent"]


def test_chat_that_comes_with_a_decision_leads_the_result(tmp_path):
    result = chat_with_a_decision(tmp_path, CHAT_TOOLS)
    assert list(result)[:2] == ["chat_note", "recent_chat"]
    assert result["chat_note"] == CHAT_NOTE
    assert result["recent_chat"] == ["Host: build a ski resort?"]
    assert result["action_pending"] is True


def test_a_seat_without_chat_gets_no_chat_note(tmp_path):
    assert "chat_note" not in chat_with_a_decision(tmp_path, TOOLS)


def test_a_wait_sent_with_a_call_that_ends_for_chat_is_answered_at_once(tmp_path):
    """A model that sends choose_action and pass_priority together gets the chat now, not after the others' turn."""
    forwarded = []
    chat_wake = {"action_pending": False, "stop_reason": "chat", "note": "chat", "recent_chat": ["Host: hi"]}

    class Native:
        def forward(self, payload, _timeout):
            name = payload["params"]["name"]
            forwarded.append(name)
            if name == "choose_action":
                time.sleep(0.4)  # the model's own action, which then waits and ends for chat
                result = envelope(chat_wake)
            else:
                result = envelope(turn(2))
            return 200, json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": result}).encode()

    gateway = GameToolGateway(Native(), CHAT_TOOLS, tmp_path / "player-tools.jsonl", optimize_play=True)

    def post(number, tool, arguments, replies):
        payload = {"jsonrpc": "2.0", "id": number, "method": "tools/call",
                   "params": {"name": tool, "arguments": arguments}}
        request = urllib.request.Request(gateway.url, data=json.dumps(payload).encode())
        with urllib.request.urlopen(request, timeout=5) as response:
            replies[tool] = json.loads(response.read())["result"]["structuredContent"]

    try:
        replies = {}
        action = threading.Thread(target=post, args=(1, "choose_action", {"choice": "p17"}, replies))
        action.start()
        time.sleep(0.1)
        post(2, "pass_priority", {"until": "my_turn"}, replies)
        action.join(timeout=5)
        assert replies["choose_action"]["stop_reason"] == "chat"
        assert replies["pass_priority"] == HELD_FOR_CHAT
        assert forwarded == ["choose_action"]

        # Sent after the model saw the chat, the next wait runs as usual.
        later = {}
        post(3, "pass_priority", {"until": "my_turn"}, later)
        assert later["pass_priority"]["action_pending"] is True
        assert forwarded == ["choose_action", "pass_priority"]
    finally:
        gateway.close()
    outcomes = [json.loads(line)["outcome"] for line in (tmp_path / "player-tools.jsonl").read_text().splitlines()]
    assert sorted(outcomes) == ["held_for_chat", "ok", "ok"]


def chat_then_calls(tmp_path, first, calls, tools=CHAT_TOOLS):
    """The model's calls after one whose result is `first`; what reached the game and what the model got back."""
    forwarded = []

    class Native:
        def forward(self, payload, _timeout):
            name = payload["params"]["name"]
            forwarded.append(name)
            result = {"success": True} if name == CHAT_TOOL else first if len(forwarded) == 1 else turn(2)
            return 200, json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": envelope(result)}).encode()

    gateway = GameToolGateway(Native(), tools, tmp_path / "player-tools.jsonl", optimize_play=True)
    replies = []
    try:
        for number, (tool, arguments) in enumerate([("pass_priority", {"until": "my_turn"}), *calls], 1):
            payload = {"jsonrpc": "2.0", "id": number, "method": "tools/call",
                       "params": {"name": tool, "arguments": arguments}}
            request = urllib.request.Request(gateway.url, data=json.dumps(payload).encode())
            with urllib.request.urlopen(request, timeout=5) as response:
                replies.append(json.loads(response.read())["result"]["structuredContent"])
    finally:
        gateway.close()
    return forwarded, replies[1:]


CHAT_WAKE = {"action_pending": False, "stop_reason": "chat", "note": "chat", "recent_chat": ["Host: nervous yet?"]}
WAIT = ("pass_priority", {"until": "my_turn"})


def test_the_first_wait_after_unanswered_chat_reminds_the_model_once(tmp_path):
    forwarded, replies = chat_then_calls(tmp_path, CHAT_WAKE, [WAIT, WAIT])
    assert replies[0] == {"action_pending": False, "stop_reason": "chat", "recent_chat": ["Host: nervous yet?"],
                          "note": CHAT_REMINDER}
    assert replies[1]["action_pending"] is True
    assert forwarded == ["pass_priority", "pass_priority"]
    outcomes = [json.loads(line)["outcome"] for line in (tmp_path / "player-tools.jsonl").read_text().splitlines()]
    assert outcomes == ["ok", "chat_reminder", "ok"]


def test_a_model_that_answers_the_chat_is_not_reminded(tmp_path):
    forwarded, replies = chat_then_calls(tmp_path, CHAT_WAKE, [(CHAT_TOOL, {"message": "Not yet!"}), WAIT])
    assert replies[1]["action_pending"] is True
    assert forwarded == ["pass_priority", CHAT_TOOL, "pass_priority"]


def test_chat_that_comes_with_a_decision_brings_no_reminder(tmp_path):
    """recent_chat beside a decision may be only game notices; the bridge ends a wait early only for talk."""
    decision = {**turn(1), "recent_chat": ["Host accepted the rollback request"]}
    forwarded, replies = chat_then_calls(tmp_path, decision, [WAIT])
    assert replies[0]["action_pending"] is True
    assert forwarded == ["pass_priority", "pass_priority"]


def test_a_seat_without_chat_is_never_reminded(tmp_path):
    forwarded, replies = chat_then_calls(tmp_path, CHAT_WAKE, [WAIT], tools=TOOLS)
    assert replies[0]["action_pending"] is True
    assert forwarded == ["pass_priority", "pass_priority"]


def test_a_few_messages_each_turn_then_none_until_the_next(table):
    with table() as (gateway, post, say, sent):
        post("pass_priority", {"turn": 1})
        for number in range(CHAT_PER_TURN):
            assert say(f"message {number}") is None
        assert "this turn" in say("one too many")
        post("pass_priority", {"turn": 2})
        assert say("new turn") is None
    assert sent == [*(f"message {number}" for number in range(CHAT_PER_TURN)), "new turn"]
    assert gateway.chat_messages == CHAT_PER_TURN + 1
