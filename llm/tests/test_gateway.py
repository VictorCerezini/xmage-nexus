"""Exercise the HTTP information boundary without any model or credentials."""

import json
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from llm.game_tool_gateway import GameToolGateway, validate_loopback_url


@pytest.fixture
def gateway(tmp_path):
    received = []

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(payload)
            if payload["method"] == "tools/list":
                result = {
                    "tools": [{"name": name} for name in ["get_game_state", "choose_action", "concede", "read_file"]]
                }
            else:
                result = {"own_hand": ["Island"], "opponent_hand_count": 7}
            body = json.dumps({"jsonrpc": "2.0", "id": payload.get("id"), "result": result}).encode()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(body)

    upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True)
    thread.start()
    trace = tmp_path / "trace.jsonl"
    proxy = GameToolGateway(
        f"http://127.0.0.1:{upstream.server_port}/mcp",
        ["get_game_state", "choose_action", "concede"],
        trace,
    )
    recorded = threading.Event()
    record_call = proxy.record_call

    def record_and_signal(record):
        record_call(record)
        recorded.set()

    proxy.record_call = record_and_signal

    def post(method, params=None):
        recorded.clear()
        request = urllib.request.Request(
            proxy.url,
            data=json.dumps({"id": 1, "method": method, "params": {} if params is None else params}).encode(),
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            decoded = json.loads(response.read())
        if method == "tools/call":
            assert recorded.wait(timeout=3)
        return decoded

    try:
        yield proxy, post, received, trace
    finally:
        proxy.close()
        upstream.shutdown()
        upstream.server_close()
        thread.join(timeout=3)


def test_discovery_only_exposes_game_tools_with_accurate_annotations(gateway):
    _, post, _, _ = gateway
    tools = {tool["name"]: tool for tool in post("tools/list")["result"]["tools"]}
    assert set(tools) == {"get_game_state", "choose_action", "concede"}
    assert tools["get_game_state"]["annotations"]["readOnlyHint"] is True
    assert tools["choose_action"]["annotations"]["readOnlyHint"] is False
    assert tools["choose_action"]["annotations"]["destructiveHint"] is False
    assert tools["concede"]["annotations"]["destructiveHint"] is True
    assert all(tool["annotations"]["openWorldHint"] is False for tool in tools.values())


def test_unlisted_tool_never_reaches_native_bridge_and_allowed_response_is_unchanged(gateway):
    proxy, post, received, trace = gateway
    rejected = post("tools/call", {"name": "read_file"})
    assert rejected["error"]["code"] == -32601
    assert received == []
    allowed = post("tools/call", {"name": "get_game_state"})
    assert allowed["result"] == {"own_hand": ["Island"], "opponent_hand_count": 7}
    assert len(received) == 1
    assert proxy.violations == ["read_file"]
    records = [json.loads(line) for line in trace.read_text().splitlines()]
    assert records[0]["outcome"] == "rejected"
    assert records[1]["tool"] == "get_game_state"


def test_call_budget_is_enforced_before_forwarding(gateway):
    proxy, post, received, _ = gateway
    proxy.max_calls = 1
    assert "result" in post("tools/call", {"name": "get_game_state"})
    assert "error" in post("tools/call", {"name": "get_game_state"})
    assert len(received) == 1


@pytest.mark.parametrize("url", ["file:///private/data", "https://example.com/mcp", "http://192.0.2.1/mcp"])
def test_bridge_must_be_loopback_http(url):
    with pytest.raises(ValueError, match=r"127\.0\.0\.1"):
        validate_loopback_url(url)
