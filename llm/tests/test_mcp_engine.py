"""Exercise the mcp/ engine's stdio client, tool text and deck reading with a fake server, never a real game."""

import json
import subprocess
import sys
import threading
import time
import urllib.request

import pytest

from llm import mcp_engine, paths, subscription_client
from llm.game_tool_gateway import GameToolGateway

FAKE_SERVER = r"""
import json, sys
held = None
for line in sys.stdin:
    message = json.loads(line)
    if "id" not in message:
        continue
    method = message["method"]
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "serverInfo": {"name": "fake"}, "capabilities": {}}
    elif method == "tools/call":
        name = message["params"]["name"]
        if name == "exit":
            break
        if name == "slow":
            held = message["id"]
            continue
        if name == "fail":
            result = {"content": [{"type": "text", "text": "no hay prompt pendiente"}], "isError": True}
        elif name == "plain":
            result = {"content": [{"type": "text", "text": "unido a la mesa"}]}
        else:
            result = {"content": [{"type": "text", "text": json.dumps({"called": name})}]}
    else:
        result = {"echo": method}
    print("[log] not a JSON-RPC line", flush=True)
    print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}), flush=True)
    if held is not None:
        slow = {"content": [{"type": "text", "text": json.dumps({"called": "slow"})}]}
        print(json.dumps({"jsonrpc": "2.0", "id": held, "result": slow}), flush=True)
        held = None
"""


@pytest.fixture
def server(tmp_path):
    script = tmp_path / "fake_server.py"
    script.write_text(FAKE_SERVER, encoding="utf-8")
    proc = subprocess.Popen(
        [sys.executable, str(script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    stdio = mcp_engine.StdioServer(proc)
    stdio.initialize()
    yield stdio
    stdio.close()
    proc.wait(timeout=5)


def test_supervisor_calls_read_json_text_and_errors(server):
    assert server.call("mage_session") == {"called": "mage_session"}
    assert server.call("plain") == {"text": "unido a la mesa"}
    with pytest.raises(RuntimeError, match="no hay prompt pendiente"):
        server.call("fail")


def test_overlapping_requests_are_matched_by_id(server):
    answers = {}

    def slow():
        answers["slow"] = server.call("slow")

    thread = threading.Thread(target=slow)
    thread.start()
    while not server.pending:
        thread.join(timeout=0.01)
    assert server.call("fast") == {"called": "fast"}
    thread.join(timeout=5)
    assert answers["slow"] == {"called": "slow"}


def test_the_cli_session_rides_on_the_supervisor_initialization(server):
    status, raw = server.forward(
        {"jsonrpc": "2.0", "id": "cli-1", "method": "initialize", "params": {"protocolVersion": "2025-03-26"}}, 5
    )
    answer = json.loads(raw)
    assert (status, answer["id"], answer["result"]["protocolVersion"]) == (200, "cli-1", "2025-03-26")
    assert server.forward({"jsonrpc": "2.0", "method": "notifications/initialized"}, 5) == (202, b"")
    status, raw = server.forward({"jsonrpc": "2.0", "id": 41, "method": "tools/list", "params": {}}, 5)
    assert json.loads(raw) == {"jsonrpc": "2.0", "id": 41, "result": {"echo": "tools/list"}}


def test_a_stopped_server_fails_every_waiting_request(server):
    with pytest.raises(ConnectionError, match="stopped"):
        server.call("exit")
    with pytest.raises(ConnectionError, match="stopped"):
        server.call("mage_session", timeout=30)


def test_ending_the_session_disconnects_from_the_proxy_and_tolerates_a_stopped_server(server):
    sent = []
    send = server._send
    server._send = lambda message: sent.append(message) or send(message)
    server.end_session()
    assert sent[-1]["params"] == {"name": "mage_disconnect", "arguments": {}}
    with pytest.raises(ConnectionError):
        server.call("exit")
    started = time.monotonic()
    server.end_session(timeout=30)
    assert time.monotonic() - started < 5


def test_play_tools_get_english_text_without_the_session_pin():
    tool = {
        "name": "mage_choose",
        "description": "Responde al prompt pendiente",
        "inputSchema": {
            "type": "object",
            "properties": {"session": {"type": "string", "description": "Pin"}, "optionId": {"type": "string"}},
            "required": ["session"],
        },
    }
    mcp_engine.describe_tool(tool)
    assert tool["description"].startswith("Answer the pending prompt")
    assert set(tool["inputSchema"]["properties"]) == {"optionId"}
    assert tool["inputSchema"]["properties"]["optionId"]["description"].startswith("The id or value")
    assert tool["inputSchema"]["required"] == []
    assert tool["annotations"]["readOnlyHint"] is False
    assert set(mcp_engine.DESCRIPTIONS) == set(mcp_engine.TOOLS)


def test_concede_is_the_only_destructive_play_tool():
    destructive = []
    for name in mcp_engine.TOOLS:
        tool = {"name": name, "inputSchema": {"properties": {}}}
        mcp_engine.describe_tool(tool)
        if tool["annotations"]["destructiveHint"]:
            destructive.append(name)
    assert destructive == ["mage_concede"]


def test_deck_file_becomes_the_join_deck(tmp_path):
    deck = tmp_path / "deck.dck"
    deck.write_text(
        "NAME:Mirror Bears\n20 [M12:246] Forest\n20 [M12:193] Runeclaw Bear\nSB: 2 [M12:180] Giant Growth\n"
        "LAYOUT MAIN:(1,1)(NONE,false,50)|\n",
        encoding="utf-8",
    )
    assert mcp_engine.read_deck(deck) == {
        "name": "Mirror Bears",
        "cards": [
            {"cardName": "Forest", "setCode": "M12", "cardNumber": "246", "amount": 20},
            {"cardName": "Runeclaw Bear", "setCode": "M12", "cardNumber": "193", "amount": 20},
        ],
        "sideboard": [{"cardName": "Giant Growth", "setCode": "M12", "cardNumber": "180", "amount": 2}],
    }


def test_deck_without_cards_is_rejected(tmp_path):
    deck = tmp_path / "deck.dck"
    deck.write_text("40 Island\n", encoding="utf-8")
    with pytest.raises(ValueError, match="no cards"):
        mcp_engine.read_deck(deck)


@pytest.mark.parametrize(
    ("game_over", "expected"),
    [
        ("Player ChatGPT 2 is the winner", "ChatGPT 2"),
        ("Bot is the winner", "Bot"),
        ({"message": "Player Mad 1 is the winner"}, "Mad 1"),
        ({"winnerName": "ChatGPT 2", "message": "anything"}, "ChatGPT 2"),
        ("Game is a draw", None),
        (None, None),
    ],
)
def test_winner_comes_from_the_game_over_message(game_over, expected):
    assert mcp_engine.winner(game_over) == expected


class FakeUpstream:
    def __init__(self):
        self.received = []

    def forward(self, payload, timeout):
        self.received.append((payload, timeout))
        if payload["method"] == "tools/list":
            tools = [{"name": name, "inputSchema": {"properties": {}}} for name in ["mage_connect", *mcp_engine.TOOLS]]
            result = {"tools": tools}
        else:
            result = {"content": [{"type": "text", "text": "{}"}]}
        return 200, json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": result}).encode()


def post(gateway, method, params=None):
    request = urllib.request.Request(
        gateway.url, data=json.dumps({"jsonrpc": "2.0", "id": 3, "method": method, "params": params or {}}).encode()
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())


def test_gateway_serves_only_the_play_tools_of_an_object_upstream(tmp_path):
    upstream = FakeUpstream()
    gateway = GameToolGateway(
        upstream, mcp_engine.TOOLS, tmp_path / "trace.jsonl", upstream_timeout=99, describe=mcp_engine.describe_tool
    )
    try:
        listed = post(gateway, "tools/list")["result"]["tools"]
        assert [tool["name"] for tool in listed] == mcp_engine.TOOLS
        assert all(tool["description"] == mcp_engine.DESCRIPTIONS[tool["name"]] for tool in listed)
        assert "error" in post(gateway, "tools/call", {"name": "mage_connect", "arguments": {}})
        assert "result" in post(gateway, "tools/call", {"name": "mage_pass_priority", "arguments": {}})
        assert gateway.violations == ["mage_connect"]
        assert gateway.call_count == 2
        assert all(timeout == 99 for _payload, timeout in upstream.received)
        assert [payload["params"]["name"] for payload, _ in upstream.received[1:]] == ["mage_pass_priority"]
    finally:
        gateway.close()


def test_codex_enables_and_pre_approves_the_engine_tools(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": "C:/Tools/codex.exe"})
    command = subscription_client.command("http://127.0.0.1:12345/mcp", tools=mcp_engine.TOOLS)
    configs = {
        command[index + 1].split("=", 1)[0]: json.loads(command[index + 1].split("=", 1)[1])
        for index, value in enumerate(command)
        if value == "-c"
    }
    assert configs["mcp_servers.mage.enabled_tools"] == mcp_engine.TOOLS
    approvals = {key: value for key, value in configs.items() if key.endswith("approval_mode")}
    assert approvals == {
        "mcp_servers.mage.default_tools_approval_mode": "auto",
        "mcp_servers.mage.tools.mage_concede.approval_mode": "approve",
    }
