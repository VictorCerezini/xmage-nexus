"""The model's problem reports and the end-of-game question, through the real gateway; no inference."""

import copy
import json
import threading
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from llm import game_tool_gateway
from llm.game_tool_gateway import END_QUESTION, REPORT_TOOL, GameToolGateway
from llm.subscription_client import TOOLS

ACTION_SCHEMA = {
    "type": "object",
    "properties": {"action_pending": {"type": "boolean"}, "game_over": {"type": "boolean"}},
}


def decision(**changes):
    value = {
        "action_pending": True,
        "action_type": "GAME_CHOOSE_ABILITY",
        "response_type": "index",
        "message": "Choose mode<br>Boros Charm",
        "choices": [{"index": 0, "description": "4 damage to target player"}],
        "game_seq": 7,
    }
    value.update(changes)
    return value


def empty_priority():
    return {
        "action_pending": True,
        "action_type": "GAME_SELECT",
        "response_type": "boolean",
        "choices": [],
        "game_seq": 8,
    }


def game_over():
    return {"action_pending": False, "stop_reason": "game_over", "game_over": True}


def envelope(value):
    return {
        "content": [{"type": "text", "text": json.dumps(value)}],
        "structuredContent": copy.deepcopy(value),
        "isError": False,
    }


def value(response):
    result = response["result"]
    parsed = json.loads(result["content"][0]["text"])
    assert parsed == result["structuredContent"]
    return parsed


@pytest.fixture
def serve(tmp_path):
    @contextmanager
    def start(responses, tools=TOOLS):
        remaining = iter(responses)
        received = []

        class Native(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                received.append(request)
                if request["method"] == "tools/list":
                    # the bridge's own tools; it has never heard of report_problem
                    listed = [name for name in TOOLS if name != REPORT_TOOL]
                    result = {
                        "tools": [
                            {"name": name, "description": name, "outputSchema": copy.deepcopy(ACTION_SCHEMA)}
                            for name in listed
                        ]
                    }
                else:
                    result = envelope(next(remaining))
                body = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        native = ThreadingHTTPServer(("127.0.0.1", 0), Native)
        thread = threading.Thread(target=native.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        trace = tmp_path / "player-tools.jsonl"
        reports = tmp_path / "problem-reports.jsonl"
        gateway = GameToolGateway(
            f"http://127.0.0.1:{native.server_port}/mcp", tools, trace, optimize_play=True, report_path=reports
        )
        recorded = threading.Event()
        record_call = gateway.record_call

        def record_and_signal(record):
            record_call(record)
            recorded.set()

        gateway.record_call = record_and_signal
        request_number = 0

        def post(tool, arguments=None):
            nonlocal request_number
            request_number += 1
            recorded.clear()
            payload = {
                "jsonrpc": "2.0",
                "id": request_number,
                "method": "tools/list" if tool == "list" else "tools/call",
                "params": {} if tool == "list" else {"name": tool, "arguments": arguments or {}},
            }
            request = urllib.request.Request(gateway.url, data=json.dumps(payload).encode())
            with urllib.request.urlopen(request, timeout=5) as response:
                decoded = json.loads(response.read())
            if tool != "list":
                assert recorded.wait(timeout=3)
            return decoded

        def saved():
            return [json.loads(line) for line in reports.read_text(encoding="utf-8").splitlines()]

        try:
            yield gateway, post, received, saved
        finally:
            gateway.close()
            native.shutdown()
            native.server_close()
            thread.join(timeout=3)

    return start


def test_the_model_is_offered_report_problem_and_told_about_the_end_question(serve):
    with serve([]) as (_gateway, post, received, _saved):
        tools = {tool["name"]: tool for tool in post("list")["result"]["tools"]}
    assert REPORT_TOOL in tools
    assert tools[REPORT_TOOL]["inputSchema"]["required"] == ["problem"]
    assert tools[REPORT_TOOL]["annotations"]["destructiveHint"] is False
    assert "bridge_check" in tools["pass_priority"]["outputSchema"]["properties"]
    assert len(received) == 1


def test_without_the_report_tool_nothing_changes(serve):
    tools = [name for name in TOOLS if name != REPORT_TOOL]
    with serve([game_over()], tools=tools) as (gateway, post, _received, _saved):
        listed = {tool["name"]: tool for tool in post("list")["result"]["tools"]}
        assert REPORT_TOOL not in listed
        assert "bridge_check" not in listed["pass_priority"]["outputSchema"]["properties"]
        assert "bridge_check" not in value(post("pass_priority"))
        assert post(REPORT_TOOL, {"problem": "x"})["error"]["code"] == -32601
        assert gateway.problem_reports == 0


def test_a_report_during_the_game_is_saved_with_what_the_model_saw_and_never_reaches_the_bridge(serve):
    with serve([decision()]) as (gateway, post, received, saved):
        post("get_action_choices")
        answer = post(REPORT_TOOL, {"problem": "Boros Charm: the bridge chose the damage mode for me."})
        assert value(answer) == {"saved": True, "message": "Saved, thank you. Keep playing."}
        assert len(received) == 1
        [entry] = saved()
        assert entry["problem"] == "Boros Charm: the bridge chose the damage mode for me."
        assert entry["all_worked"] is False
        assert entry["after_game_end"] is False
        assert entry["call"] == 2
        [last] = entry["recent_calls"]
        assert last["tool"] == "get_action_choices"
        assert "Choose mode<br>Boros Charm" in last["shown"]
        assert gateway.problem_reports == 1
        assert gateway.said_all_worked is False
        assert gateway.optimization_stats()["failed_calls"] == 0


def test_a_report_does_not_disturb_the_empty_priority_pass(serve):
    with serve([empty_priority(), decision()]) as (gateway, post, received, _saved):
        post("get_action_choices")
        post(REPORT_TOOL, {"problem": "The land I wanted to tap was not offered."})
        post("choose_action", {"choice": "no"})
        assert received[-1]["params"]["name"] == "pass_priority"
        assert gateway.optimization_stats()["batched_pass_attempts"] == 1


def test_the_game_over_result_asks_the_end_question_once_and_none_says_everything_worked(serve):
    with serve([game_over(), game_over()]) as (gateway, post, _received, saved):
        first = value(post("pass_priority"))
        assert first["game_over"] is True
        assert first["bridge_check"] == END_QUESTION
        assert "bridge_check" not in value(post("pass_priority"))
        answer = value(post(REPORT_TOOL, {"problem": "None."}))
        assert answer["message"] == "Saved, thank you. Report your result briefly and stop."
        [entry] = saved()
        assert entry["all_worked"] is True
        assert entry["after_game_end"] is True
        assert gateway.said_all_worked is True
        assert gateway.problem_reports == 0


def test_an_elimination_asks_the_end_question_too(serve):
    eliminated = {"action_pending": False, "player_dead": True}
    with serve([eliminated]) as (_gateway, post, _received, _saved):
        assert value(post("pass_priority"))["bridge_check"] == END_QUESTION


def test_after_the_game_ended_reports_still_reach_the_gateway_and_the_question_is_asked(serve):
    with serve([decision()]) as (gateway, post, received, saved):
        post("get_action_choices")
        gateway.finish_game()
        finished = value(post("pass_priority"))
        assert finished["game_over"] is True
        assert finished["bridge_check"] == END_QUESTION
        assert "bridge_check" not in value(post("get_game_state"))
        post(REPORT_TOOL, {"problem": "Shadrix Silverquill's modes were chosen for me."})
        assert len(received) == 1
        assert [entry["after_game_end"] for entry in saved()] == [True]
        assert gateway.problem_reports == 1


def test_an_empty_report_is_refused_and_not_saved(serve):
    with serve([]) as (gateway, post, _received, _saved):
        answer = post(REPORT_TOOL, {"problem": "  "})
        assert answer["result"]["isError"] is True
        assert gateway.problem_reports == 0
        assert not gateway.report_path.exists()


def test_a_game_takes_a_limited_number_of_reports(serve, monkeypatch):
    monkeypatch.setattr(game_tool_gateway, "MAX_REPORTS", 2)
    with serve([]) as (gateway, post, _received, saved):
        for number in range(3):
            answer = value(post(REPORT_TOOL, {"problem": f"problem {number}"}))
        assert answer["saved"] is False
        assert len(saved()) == 2
        assert gateway.problem_reports == 2
