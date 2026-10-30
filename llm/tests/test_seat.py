"""Exercise the model seat's lifecycle with fake processes, never a real model."""

import json
import os
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from llm import claude_client, paths, providers, seat


class FakeSocket:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def bind(self, _):
        pass

    def getsockname(self):
        return "127.0.0.1", 12345

    def settimeout(self, _):
        pass

    def connect_ex(self, _):
        return 0


@pytest.fixture
def harness(tmp_path, monkeypatch):
    catalog = tmp_path / "catalog.json"
    catalog.write_text(
        json.dumps(
            {
                "providers": [
                    {
                        "id": "chatgpt_subscription",
                        "models": [
                            {"id": "gpt-6-astra", "efforts": ["medium", "high"]},
                            {"id": "gpt-6.1-terra", "efforts": ["medium"]},
                        ],
                    },
                    {"id": "claude_subscription", "models": [{"id": "claude-opus-5-5", "efforts": ["medium", "max"]}]},
                    {"id": "openrouter_free", "models": [{"id": "a/alpha:free", "efforts": ["default", "low"]}]},
                    {"id": "zai_coding_plan", "models": [{"id": "glm-5.3", "efforts": ["default", "max"]}]},
                ],
                "unavailable": [],
            }
        )
    )
    deck = tmp_path / "deck.dck"
    deck.write_text("40 Island\n")
    request = {
        "table_id": "7c134c35-4cbd-4072-a24d-c5d9a2b72987",
        "server_port": 18171,
        "catalog": str(catalog),
        "deck": str(deck),
        "model": "gpt-6-astra",
        "reasoning": "high",
        "seat": 2,
        "game_type": "Two Player Duel",
        "deck_type": "Limited",
        "player_count": 2,
        "owner_pid": os.getpid(),
        "username": "ChatGPT 2",
        "run": str(tmp_path / "run"),
    }
    bridge = SimpleNamespace(poll=lambda: None)
    stopped = []

    def stop(proc):
        stopped.append(proc)
        proc.poll = lambda: 0

    def launch_bridge(arguments, _env, log, workdir):
        assert workdir == Path(request["run"]) / "bridge"
        assert f"-Dxmage.bridge.seat={request['seat']}" in arguments
        assert f"-Dxmage.bridge.tableId={request['table_id']}" in arguments
        assert not any("continueMatch" in argument for argument in arguments)
        log.write(f"[{request['username']}] Game over: fake test result\n")
        log.flush()
        return bridge

    runtime = SimpleNamespace(
        launch_bridge=launch_bridge,
        stop_tree=stop,
        save_json=lambda path, value: path.write_text(json.dumps(value)),
    )
    gateway = SimpleNamespace(
        url="http://127.0.0.1:12346/mcp",
        violations=[],
        call_count=0,
        fatal_error=None,
        close=lambda: None,
        finish_game=lambda: None,
        new_player=lambda: None,
        optimization_stats=lambda: {"policy": "gui-efficient-v2"},
    )
    monkeypatch.setattr(seat.socket, "socket", FakeSocket)
    monkeypatch.setattr(paths, "PLAYER_CWD", tmp_path / "player-sandbox")

    def make_gateway(*_args, **kwargs):
        assert kwargs["optimize_play"] is True
        assert kwargs["upstream_timeout"] > seat.MAX_SECONDS
        return gateway

    monkeypatch.setattr(seat, "GameToolGateway", make_gateway)
    monkeypatch.setattr(seat.time, "sleep", lambda _: None)
    monkeypatch.setattr(seat, "check_owner", lambda *_: None)
    return request, runtime, stopped, gateway


def result(value):
    return {"result": {"content": [{"type": "text", "text": json.dumps(value)}]}}


@pytest.mark.parametrize(("players", "seat_number"), [(2, 2), (4, 3)])
def test_selection_reaches_player_only_after_game_starts(harness, monkeypatch, players, seat_number):
    request, runtime, stopped, _ = harness
    request.update(
        game_type="Commander Two Player Duel" if players == 2 else "Commander Free For All",
        deck_type="Variant Magic - Commander",
        player_count=players,
        seat=seat_number,
    )
    observations = iter([False, False, True])
    waiting = []

    def rpc(_url, method, params):
        if method == "initialize":
            return {"result": {}}
        if params["name"] == "get_game_state":
            available = next(observations)
            waiting.append(available)
            return result({"available": available})
        return result({"action_pending": False})

    def start(prompt, out, _err, _url, *, model, reasoning, tool_timeout_seconds):
        assert waiting == [False, False, True]
        assert (model, reasoning) == ("gpt-6-astra", "high")
        assert tool_timeout_seconds == seat.MAX_SECONDS + 60
        assert "opponent's deck" in prompt
        assert request["game_type"] in prompt
        assert f"Number of players: {players}" in prompt
        assert "commander selection" in prompt
        out.write(json.dumps({"type": "turn.completed", "usage": {"input_tokens": 10, "output_tokens": 2}}) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    monkeypatch.setattr(seat, "rpc", rpc)
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["cleanup_complete"] is True
    assert manifest["usage"][0]["input_tokens"] == 10
    assert manifest["optimization"]["policy"] == "gui-efficient-v2"
    assert len(stopped) == 2


def test_seat_outside_player_count_is_rejected(harness):
    request, *_ = harness
    request["seat"] = 3
    with pytest.raises(ValueError, match="outside the configured player count"):
        seat.validate_request(request)


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("[ChatGPT 2] Game over: test", "game_over"),
        ("[ChatGPT 2] Player death detected from game log", "player_eliminated"),
        ("[ChatGPT 3] Player death detected from game log", None),
        ("[ChatGPT 2] Zombie game detected: declaring game dead", None),
        ("[ChatGPT 2] CRITICAL: sendPlayerBoolean failed", None),
    ],
)
def test_only_native_game_end_or_own_elimination_confirms_completion(message, expected):
    assert seat.native_terminal_reason(message, "ChatGPT 2") == expected


def test_closing_window_before_start_consumes_no_inference(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    checks = iter([True, True, False])

    def check(*_):
        if not next(checks):
            raise seat.SessionCancelledError("test window closed")

    monkeypatch.setattr(seat, "check_owner", check)
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": False, "action_pending": False}))
    start = Mock(side_effect=AssertionError("Inference must not run while waiting"))
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "cancelled"
    assert manifest["usage"] == []
    assert manifest["cleanup_complete"] is True
    start.assert_not_called()
    assert len(stopped) == 1


def test_early_player_exit_is_failure_and_cleans_processes(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(*_args, **_kwargs):
        return SimpleNamespace(poll=lambda: 1, returncode=1)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "failed"
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 2


CAPACITY = "Selected model is at capacity. Please try a different model."


def codex_failure(out, message):
    out.write(json.dumps({"type": "error", "message": message}) + "\n")
    out.write(json.dumps({"type": "turn.failed", "error": {"message": message}}) + "\n")
    out.flush()
    return SimpleNamespace(poll=lambda: 1, returncode=1)


@pytest.fixture
def undecided(harness, monkeypatch):
    """A game the bridge has not logged an end for; game_over() logs it."""
    request, runtime, stopped, gateway = harness
    bridge_log = []

    def launch_bridge(_arguments, _env, log, _workdir):
        bridge_log.append(log)
        return SimpleNamespace(poll=lambda: None)

    def game_over():
        bridge_log[0].write(f"[{request['username']}] Game over: fake test result\n")
        bridge_log[0].flush()

    runtime.launch_bridge = launch_bridge
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))
    monkeypatch.setattr(seat, "RETRY_DELAYS", (0,) * len(seat.RETRY_DELAYS))
    return request, runtime, stopped, gateway, game_over


def test_a_player_stopped_by_a_busy_model_is_replaced_and_finishes_the_game(undecided):
    request, runtime, _stopped, gateway, game_over = undecided
    prompts, new_players = [], []
    gateway.new_player = lambda: new_players.append(True)

    def start(prompt, out, *_args, **_kwargs):
        prompts.append(prompt)
        if len(prompts) == 1:
            return codex_failure(out, CAPACITY)
        game_over()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["recoveries"] == [{"game": 1, "attempt": 1, "reason": CAPACITY, "model": "gpt-6-astra"}]
    assert "model_switch" not in manifest
    assert "model" not in manifest["games"][0]
    assert manifest["games"][0]["player_restarts"] == 1
    assert new_players == [True]
    assert seat.RESUME_PROMPT not in prompts[0]
    assert prompts[1].endswith(seat.RESUME_PROMPT)
    assert (run / "codex-events-retry1.jsonl").exists()
    assert (run / "player-prompt-retry1.txt").read_text(encoding="utf-8") == prompts[1]


def test_a_reached_usage_limit_is_not_retried(undecided):
    request, runtime, _stopped, _gateway, _game_over = undecided
    starts = []

    def start(_prompt, out, *_args, **_kwargs):
        starts.append(True)
        return codex_failure(out, "You've hit your usage limit. Try again at 6:00 PM.")

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "failed"
    assert "usage limit" in manifest["error"]
    assert "recoveries" not in manifest
    assert len(starts) == 1


def test_a_player_that_keeps_stopping_gives_up_after_the_last_retry(undecided):
    request, runtime, _stopped, _gateway, _game_over = undecided
    starts = []

    def start(_prompt, out, *_args, **_kwargs):
        starts.append(True)
        return codex_failure(out, CAPACITY)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert len(starts) == len(seat.RETRY_DELAYS) + 1
    assert len(manifest["recoveries"]) == len(seat.RETRY_DELAYS)
    assert "at capacity" in manifest["error"]


USAGE_LIMIT = "You've hit your usage limit. Try again at 1:56 AM."


def test_a_model_still_failing_after_every_retry_hands_the_game_to_the_backup(undecided):
    request, runtime, _stopped, _gateway, game_over = undecided
    request.update(backup_model="gpt-6.1-terra", backup_reasoning="medium")
    models = []

    def start(_prompt, out, *_args, model, reasoning, **_kwargs):
        models.append((model, reasoning))
        if model == "gpt-6-astra":
            return codex_failure(out, CAPACITY)
        game_over()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    tries = len(seat.RETRY_DELAYS) + 1
    assert models == [("gpt-6-astra", "high")] * tries + [("gpt-6.1-terra", "medium")]
    assert manifest["status"] == "completed"
    assert manifest["model_switch"] == {
        "game": 1,
        "from": "chatgpt_subscription gpt-6-astra",
        "to": "chatgpt_subscription gpt-6.1-terra",
        "reason": CAPACITY,
    }
    assert manifest["games"][0]["model"] == "chatgpt_subscription gpt-6.1-terra"
    assert manifest["games"][0]["player_restarts"] == tries
    assert {entry["model"] for entry in manifest["recoveries"]} == {"gpt-6-astra"}


def test_a_usage_limit_hands_the_game_to_another_providers_backup_at_once(undecided, monkeypatch):
    request, runtime, _stopped, gateway, game_over = undecided
    request.update(backup_provider="claude_subscription", backup_model="claude-opus-5-5", backup_reasoning="max")
    new_players, prompts = [], []
    gateway.new_player = lambda: new_players.append(True)

    def start_codex(prompt, out, *_args, **_kwargs):
        prompts.append(prompt)
        return codex_failure(out, USAGE_LIMIT)

    def start_claude(prompt, out, _err, _url, *, model, reasoning, tool_timeout_seconds):
        assert (model, reasoning) == ("claude-opus-5-5", "max")
        prompts.append(prompt)
        out.write(json.dumps({"type": "result", "num_turns": 4, "usage": {"input_tokens": 7}}) + "\n")
        out.flush()
        game_over()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    # Each provider starts its own CLI: no single start_player stands in for both.
    chosen = {
        "chatgpt_subscription": replace(seat.providers.get("chatgpt_subscription"), start=start_codex),
        "claude_subscription": replace(seat.providers.get("claude_subscription"), start=start_claude),
    }
    monkeypatch.setattr(seat.providers, "get", lambda provider_id: chosen[provider_id])

    assert seat.run_player(request, runtime=runtime) == 0
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["provider"] == "chatgpt_subscription"
    assert manifest["model_switch"] == {
        "game": 1,
        "from": "chatgpt_subscription gpt-6-astra",
        "to": "claude_subscription claude-opus-5-5",
        "reason": USAGE_LIMIT,
    }
    assert manifest["recoveries"] == [{"game": 1, "attempt": 1, "reason": USAGE_LIMIT, "model": "gpt-6-astra"}]
    assert manifest["games"][0]["model"] == "claude_subscription claude-opus-5-5"
    # Each player's trace is read by its own provider: Codex's reader finds nothing in Claude's.
    assert [entry["input_tokens"] for entry in manifest["usage"]] == [7]
    assert new_players == [True]
    assert prompts[1].endswith(seat.RESUME_PROMPT)
    assert (run / "codex-events.jsonl").is_file()
    assert (run / "claude-events-retry1.jsonl").is_file()


def test_a_backup_that_fails_too_gets_its_own_retries_then_the_seat_gives_up(undecided):
    request, runtime, _stopped, _gateway, _game_over = undecided
    request.update(backup_model="gpt-6.1-terra", backup_reasoning="medium")
    models = []

    def start(_prompt, out, *_args, model, **_kwargs):
        models.append(model)
        return codex_failure(out, CAPACITY)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    tries = len(seat.RETRY_DELAYS) + 1
    assert models == ["gpt-6-astra"] * tries + ["gpt-6.1-terra"] * tries
    assert "at capacity" in manifest["error"]


def test_a_backup_on_the_same_limited_plan_stops_the_seat_after_one_try(undecided):
    request, runtime, _stopped, _gateway, _game_over = undecided
    request.update(backup_model="gpt-6.1-terra", backup_reasoning="medium")
    models = []

    def start(_prompt, out, *_args, model, **_kwargs):
        models.append(model)
        return codex_failure(out, USAGE_LIMIT)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert models == ["gpt-6-astra", "gpt-6.1-terra"]
    assert "usage limit" in manifest["error"]


@pytest.mark.parametrize(
    ("backup", "error"),
    [
        ({"backup_model": "gpt-6-astra", "backup_reasoning": "high"}, "must differ"),
        ({"backup_model": "gpt-6.1-terra", "backup_reasoning": "high"}, "reasoning level high"),
        (
            {"backup_provider": "claude_subscription", "backup_model": "claude-opus-5-5", "backup_reasoning": "high"},
            "effort level high",
        ),
        ({"backup_provider": "nobody", "backup_model": "x", "backup_reasoning": "default"}, "nobody"),
    ],
)
def test_an_unusable_backup_model_is_rejected(harness, backup, error):
    request, *_ = harness
    request.update(backup)
    with pytest.raises((ValueError, KeyError), match=error):
        seat.validate_request(request)


def test_another_providers_backup_is_accepted(harness):
    request, *_ = harness
    request.update(backup_provider="claude_subscription", backup_model="claude-opus-5-5", backup_reasoning="max")
    seat.validate_request(request)
    request.update(backup_provider="chatgpt_subscription", backup_model="gpt-6.1-terra", backup_reasoning="medium")
    seat.validate_request(request)


def test_a_game_that_ends_while_waiting_to_retry_is_not_replayed(undecided, monkeypatch):
    request, runtime, _stopped, gateway, game_over = undecided
    monkeypatch.setattr(seat, "RETRY_DELAYS", (60,) * len(seat.RETRY_DELAYS))
    starts, finished = [], []
    gateway.finish_game = lambda: finished.append(True)
    # The others finish the game while no player is connected for this seat.
    monkeypatch.setattr(seat.time, "sleep", lambda _: starts and not finished and game_over())

    def start(_prompt, out, *_args, **_kwargs):
        starts.append(True)
        return codex_failure(out, CAPACITY)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["games"][0]["terminal_reason"] == "game_over"
    assert len(manifest["recoveries"]) == 1
    assert len(starts) == 1
    assert finished == [True]


def test_a_game_decided_before_the_player_stopped_is_not_replayed(undecided):
    request, runtime, _stopped, _gateway, game_over = undecided
    starts = []

    def start(_prompt, out, *_args, **_kwargs):
        starts.append(True)
        game_over()  # The game is decided, but this player crashed before reporting it.
        return codex_failure(out, CAPACITY)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert "at capacity" in manifest["error"]
    assert "recoveries" not in manifest
    assert len(starts) == 1


def test_provider_failure_reasons_name_the_cause():
    assert providers.codex_failure_reason([{"type": "turn.failed", "error": {"message": CAPACITY}}]) == CAPACITY
    assert providers.codex_failure_reason([{"type": "item.completed"}]) is None
    limit = {"type": "result", "is_error": True, "result": "You've hit your session limit"}
    assert claude_client.failure_reason([limit]) == "You've hit your session limit"
    assert claude_client.failure_reason([{"type": "result", "is_error": False, "result": "done"}]) is None
    assert providers.lasting_limit("You've hit your session limit")
    assert not providers.lasting_limit(CAPACITY)
    assert not providers.lasting_limit(None)


@pytest.mark.parametrize("kind", ["command_execution", "file_change", "web_search"])
def test_non_game_tools_are_rejected(kind):
    with pytest.raises(RuntimeError, match="boundary"):
        providers.get("chatgpt_subscription").require_game_only_events(
            [{"type": "item.completed", "item": {"type": kind}}]
        )


def test_claude_seat_uses_its_own_cli_trace_and_usage(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="claude_subscription", model="claude-opus-5-5", reasoning="max", username="Claude 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(prompt, out, _err, _url, *, model, reasoning, tool_timeout_seconds):
        assert (model, reasoning) == ("claude-opus-5-5", "max")
        assert "opponent's deck" in prompt
        out.write(json.dumps({"type": "system", "subtype": "init", "tools": claude_client.MCP_TOOLS}) + "\n")
        out.write(json.dumps({"type": "result", "num_turns": 4, "usage": {"input_tokens": 7}}) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["provider"] == "claude_subscription"
    assert manifest["auth"] == "existing Claude Code subscription login"
    assert manifest["usage"][0]["input_tokens"] == 7
    assert (run / "claude-events.jsonl").is_file()
    assert not (run / "codex-events.jsonl").exists()
    assert len(stopped) == 2


def test_claude_seat_rejects_a_non_game_tool_during_play(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="claude_subscription", model="claude-opus-5-5", reasoning="medium", username="Claude 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(_prompt, out, *_args, **_kwargs):
        event = {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": "Bash"}]}}
        out.write(json.dumps(event) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: None)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert "boundary" in manifest["error"]
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 2


def test_openrouter_seat_uses_opencode_trace_and_usage(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="openrouter_free", model="a/alpha:free", reasoning="default", username="OpenRouter 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(_prompt, out, _err, _url, *, model, reasoning, tool_timeout_seconds):
        assert (model, reasoning) == ("a/alpha:free", "default")
        tool = {"type": "tool", "tool": "mage_pass_priority", "state": {"status": "completed"}}
        step = {"type": "step-finish", "tokens": {"input": 7, "output": 1, "reasoning": 0, "cache": {"read": 0}}}
        out.write(json.dumps({"type": "tool_use", "part": tool}) + "\n")
        out.write(json.dumps({"type": "step_finish", "part": step}) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["provider"] == "openrouter_free"
    assert manifest["usage"][0]["input"] == 7
    assert (run / "opencode-events.jsonl").is_file()
    assert len(stopped) == 2


def test_openrouter_seat_rejects_a_non_game_tool_during_play(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="openrouter_free", model="a/alpha:free", reasoning="low", username="OpenRouter 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(_prompt, out, *_args, **_kwargs):
        event = {"type": "tool_use", "part": {"type": "tool", "tool": "read", "state": {"status": "error"}}}
        out.write(json.dumps(event) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: None)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert "boundary" in manifest["error"]
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 2


def test_openrouter_seat_failure_records_the_cli_reason(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="openrouter_free", model="a/alpha:free", reasoning="default", username="OpenRouter 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(_prompt, out, *_args, **_kwargs):
        error = {"type": "error", "error": {"data": {"message": "a/alpha:free is temporarily rate-limited upstream."}}}
        out.write(json.dumps(error) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: 1, returncode=1)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert "rate-limited upstream" in manifest["error"]
    assert "choose another free model" in manifest["error"]
    assert len(stopped) == 2


def test_zai_seat_uses_its_own_trace_and_records_the_plan_limit(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    request.update(provider="zai_coding_plan", model="glm-5.3", reasoning="max", username="GLM 2")
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def start(_prompt, out, _err, _url, *, model, reasoning, tool_timeout_seconds):
        assert (model, reasoning) == ("glm-5.3", "max")
        error = {"type": "error", "error": {"data": {"message": "Usage limit reached for 5 hour."}}}
        out.write(json.dumps(error) + "\n")
        out.flush()
        return SimpleNamespace(poll=lambda: 1, returncode=1)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["provider"] == "zai_coding_plan"
    assert "5-hour or weekly allowance" in manifest["error"]
    assert (run / "opencode-zai-events.jsonl").is_file()
    assert len(stopped) == 2


def test_provider_that_was_not_signed_in_is_rejected(harness):
    request, *_ = harness
    Path(request["catalog"]).write_text(json.dumps({"providers": [{"id": "chatgpt_subscription", "models": []}]}))
    request.update(provider="claude_subscription", model="claude-opus-5-5", reasoning="medium")
    with pytest.raises(ValueError, match="not signed in"):
        seat.validate_request(request)


def test_table_cancellation_stops_player_even_when_window_remains_open(harness):
    request, runtime, stopped, _ = harness
    run = Path(request["run"])
    run.mkdir()
    (run / "cancel.requested").write_text("Table closed")
    start = Mock(side_effect=AssertionError("A cancelled table must not consume inference"))
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "cancelled"
    assert manifest["reason"] == "XMage table was cancelled or left"
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 1
    start.assert_not_called()


def test_table_password_reaches_only_the_bridge(harness):
    request, runtime, _, _ = harness
    request["table_password"] = "table-secret"
    run = Path(request["run"])
    run.mkdir()
    (run / "cancel.requested").write_text("")
    launched = {}

    def launch_bridge(arguments, env, _log, _workdir):
        launched.update(arguments=arguments, env=env)
        return SimpleNamespace(poll=lambda: None)

    runtime.launch_bridge = launch_bridge
    assert seat.run_player(request, runtime=runtime, start_player=Mock()) == 0
    assert launched["env"][seat.TABLE_PASSWORD_ENV] == "table-secret"
    assert not any("table-secret" in argument for argument in launched["arguments"])
    assert seat.TABLE_PASSWORD_ENV not in os.environ
    assert "table-secret" not in (run / "manifest.json").read_text()


def test_table_cancellation_during_a_blocked_player_wait_still_cleans_both_processes(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def cancel_during_play(_events):
        (Path(request["run"]) / "cancel.requested").write_text("Table closed during human wait")

    chatgpt = replace(providers.get("chatgpt_subscription"), require_game_only_events=cancel_during_play)
    monkeypatch.setitem(providers.PROVIDERS, "chatgpt_subscription", chatgpt)
    # This game has no result yet.
    runtime.launch_bridge = lambda *_args: SimpleNamespace(poll=lambda: None)
    agent = SimpleNamespace(poll=lambda: None)
    start = Mock(return_value=agent)
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "cancelled"
    assert "terminal_reason" not in manifest
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 2
    start.assert_called_once()


def test_a_seat_cancelled_after_the_app_stopped_reading_still_records_it(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))

    def cancel_during_play(_events):
        (Path(request["run"]) / "cancel.requested").write_text("The other seat failed")

    chatgpt = replace(providers.get("chatgpt_subscription"), require_game_only_events=cancel_during_play)
    monkeypatch.setitem(providers.PROVIDERS, "chatgpt_subscription", chatgpt)
    runtime.launch_bridge = lambda *_args: SimpleNamespace(poll=lambda: None)

    class ClosedPipe:
        def write(self, _text):
            raise OSError(22, "Invalid argument")

        def flush(self):
            raise OSError(22, "Invalid argument")

    monkeypatch.setattr("sys.stdout", ClosedPipe())
    start = Mock(return_value=SimpleNamespace(poll=lambda: None))
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "cancelled"
    assert manifest["cleanup_complete"] is True
    assert len(stopped) == 2


def test_closing_the_window_after_the_game_ended_records_the_result(harness, monkeypatch):
    request, runtime, stopped, _ = harness
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))
    owner_checks = iter([None] * 3)

    def close_after_start(*_):
        if next(owner_checks, "closed") == "closed":
            raise seat.SessionCancelledError("XMage player window closed")

    monkeypatch.setattr(seat, "check_owner", close_after_start)
    # The harness bridge log already reports the game over; the model is still writing its report.
    start = Mock(return_value=SimpleNamespace(poll=lambda: None))
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["terminal_reason"] == "game_over"
    assert manifest["reason"] == "XMage player window closed"
    assert len(stopped) == 2


def test_game_log_keeps_each_game_of_a_match_apart():
    text = (
        "joined\n[ChatGPT 2] Game started: gameId=a\n[ChatGPT 2] Game over: first\n"
        "[ChatGPT 2] Match continues: You need one more win to win the match.\n"
        "[ChatGPT 2] Game started: gameId=b\n[ChatGPT 2] Player death detected from game log\n"
    )
    assert seat.games_started(text, "ChatGPT 2") == 2
    assert seat.native_terminal_reason(seat.game_log(text, "ChatGPT 2", 1), "ChatGPT 2") == "game_over"
    assert seat.native_terminal_reason(seat.game_log(text, "ChatGPT 2", 2), "ChatGPT 2") == ("player_eliminated")
    assert seat.game_log(text, "ChatGPT 2", 1).startswith("joined")
    assert seat.match_line(text, "ChatGPT 2", "Match continues") == "You need one more win to win the match."
    assert seat.match_line(text, "ChatGPT 2", "Match over") is None
    assert seat.game_file("codex-events.jsonl", 1) == "codex-events.jsonl"
    assert seat.game_file("codex-events.jsonl", 2) == "codex-events-game2.jsonl"


@pytest.mark.parametrize("wins", [0, seat.MAX_WINS + 1])
def test_wins_needed_outside_the_supported_range_is_rejected(harness, wins):
    request, *_ = harness
    request["wins_needed"] = wins
    with pytest.raises(ValueError, match="Wins needed"):
        seat.validate_request(request)


@pytest.fixture
def match(harness, monkeypatch):
    """A best-of-three whose bridge log the fake players advance one game at a time."""
    request, runtime, stopped, _ = harness
    request["wins_needed"] = 2
    bridge_log = []
    gateways = []

    def launch_bridge(arguments, _env, log, _workdir):
        assert "-Dxmage.bridge.continueMatch=true" in arguments
        bridge_log.append(log)
        write("Game started: gameId=first")
        return SimpleNamespace(poll=lambda: None)

    def write(line):
        bridge_log[0].write(f"[{request['username']}] {line}\n")
        bridge_log[0].flush()

    def make_gateway(_url, _tools, trace, **_kwargs):
        gateway = SimpleNamespace(
            url="http://127.0.0.1:12346/mcp",
            trace=trace,
            violations=[],
            call_count=3,
            fatal_error=None,
            finished=[],
            close=lambda: None,
            optimization_stats=lambda: {"policy": "gui-efficient-v2", "auto_passes": 2},
        )
        gateway.finish_game = lambda: gateway.finished.append(True)
        gateways.append(gateway)
        return gateway

    runtime.launch_bridge = launch_bridge
    monkeypatch.setattr(seat, "GameToolGateway", make_gateway)
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))
    return request, runtime, stopped, gateways, write


def test_best_of_three_plays_each_game_with_a_fresh_player_until_the_match_ends(match):
    request, runtime, stopped, gateways, write = match
    prompts = []

    def start(prompt, out, _err, _url, **_kwargs):
        prompts.append(prompt)
        out.write(json.dumps({"type": "turn.completed", "usage": {"input_tokens": len(prompts)}}) + "\n")
        out.flush()
        write(f"Game over: game {len(prompts)}")
        if len(prompts) == 1:
            write("Match continues: You need one more win to win the match.")
            write("Game started: gameId=second")
        else:
            write("Match over: You won the match!")
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    run = Path(request["run"])
    manifest = json.loads((run / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["match_result"] == "You won the match!"
    assert [game["game"] for game in manifest["games"]] == [1, 2]
    assert manifest["usage"] == [{"input_tokens": 1}, {"input_tokens": 2}]
    assert manifest["tool_calls"] == 6
    assert manifest["optimization"] == {"policy": "gui-efficient-v2", "auto_passes": 4}
    assert "first player to win 2 games. This is game 1." in prompts[0]
    assert "Match status" not in prompts[0]
    assert "This is game 2. Match status: You need one more win to win the match." in prompts[1]
    assert [gateway.trace.name for gateway in gateways] == ["player-tools.jsonl", "player-tools-game2.jsonl"]
    assert (run / "codex-events-game2.jsonl").is_file()
    assert (run / "player-prompt-game2.txt").is_file()
    assert len(stopped) == 3


def test_match_won_in_the_first_game_needs_no_further_player(match):
    request, runtime, stopped, _, write = match

    def start(*_args, **_kwargs):
        write("Game over: conceded")
        write("Match over: Opponent has quit the match.")
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    start = Mock(side_effect=start)
    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["match_result"] == "Opponent has quit the match."
    start.assert_called_once()
    assert len(stopped) == 2


def test_bridge_leaving_between_games_without_a_match_result_is_a_failure(match):
    request, runtime, _, _, write = match

    def start(*_args, **_kwargs):
        write("Game over: first")
        bridge.poll = lambda: 1
        return SimpleNamespace(poll=lambda: 0, returncode=0)

    launch = runtime.launch_bridge
    bridge = SimpleNamespace()

    def launch_and_keep(*args):
        bridge.poll = launch(*args).poll
        return bridge

    runtime.launch_bridge = launch_and_keep
    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "failed"
    assert "between games" in manifest["error"]
    assert len(manifest["games"]) == 1
    assert manifest["cleanup_complete"] is True


def test_player_that_keeps_going_after_its_game_ended_is_stopped_and_the_result_kept(harness, monkeypatch):
    request, runtime, stopped, gateway = harness
    finished = []
    gateway.finish_game = lambda: finished.append(True)
    monkeypatch.setattr(seat, "REPORT_GRACE_SECONDS", 0)
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))
    # The harness bridge log already reports the game over; this player never exits by itself.
    agent = SimpleNamespace(poll=lambda: None)
    assert seat.run_player(request, runtime=runtime, start_player=Mock(return_value=agent)) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert manifest["terminal_reason"] == "game_over"
    assert finished == [True]
    assert agent in stopped


class FakeMcpServer:
    def __init__(self, sessions, game_over="Player ChatGPT 2 is the winner"):
        self.proc = SimpleNamespace(poll=lambda: None)
        self.calls = []
        self.sessions = sessions
        self.game_over = game_over

    def initialize(self):
        self.calls.append(("initialize", None))

    def call(self, name, arguments=None, **_options):
        self.calls.append((name, arguments))
        if name == "mage_session":
            return self.sessions.pop(0) if len(self.sessions) > 1 else self.sessions[0]
        if name == "mage_game_state":
            return {"state": {"turn": 9}, "gameOver": {"at": 1, "data": self.game_over}}
        return {"text": "ok"}

    def end_session(self):
        self.calls.append(("end_session", self.proc.poll()))

    def close(self):
        pass


@pytest.fixture
def mcp_harness(harness, monkeypatch, tmp_path):
    request, runtime, stopped, gateway = harness
    deck = tmp_path / "mcp-deck.dck"
    deck.write_text("NAME:Mirror Bears\n20 [M12:246] Forest\n20 [M12:193] Runeclaw Bear\n", encoding="utf-8")
    request.update(engine="mcp", proxy_url="ws://127.0.0.1:8787", deck=str(deck))
    server = FakeMcpServer(
        [{"gameId": None}, {"gameId": "g1", "game": {"turn": 1}}, {"gameOver": None}, {"gameOver": {"at": 1}}]
    )
    runtime.launch_mcp = lambda _env, _log: server
    runtime.launch_bridge = Mock(side_effect=AssertionError("The mcp engine runs no bridge"))
    built = {}

    def make_gateway(upstream, tools, _trace, **kwargs):
        built.update(kwargs, upstream=upstream, tools=tools)
        return gateway

    monkeypatch.setattr(seat, "GameToolGateway", make_gateway)
    monkeypatch.setattr(seat, "rpc", Mock(side_effect=AssertionError("The mcp engine has no HTTP bridge")))
    return request, runtime, stopped, gateway, server, built


def test_mcp_engine_joins_through_the_proxy_and_records_the_winner(mcp_harness):
    request, runtime, stopped, gateway, server, built = mcp_harness
    request["table_password"] = "table-secret"
    finished = []
    gateway.finish_game = lambda: finished.append(True)
    polls = iter([None, None])
    agent = SimpleNamespace(poll=lambda: next(polls, 0), returncode=0)

    def start(prompt, out, _err, url, *, model, reasoning, tool_timeout_seconds, tools):
        assert tools == seat.mcp_engine.TOOLS
        assert url == gateway.url
        assert "mage_wait_for_prompt" in prompt
        assert "get_action_choices" not in prompt
        out.write(json.dumps({"type": "turn.completed", "usage": {"input_tokens": 5, "output_tokens": 1}}) + "\n")
        out.flush()
        return agent

    assert seat.run_player(request, runtime=runtime, start_player=start) == 0
    names = [name for name, _ in server.calls]
    assert names[:3] == ["initialize", "mage_connect", "mage_join_table"]
    connect = dict(server.calls)["mage_connect"]
    assert connect["proxyUrl"] == "ws://127.0.0.1:8787"
    assert (connect["host"], connect["port"], connect["username"]) == ("127.0.0.1", 18171, "ChatGPT 2")
    join = dict(server.calls)["mage_join_table"]
    assert join["tableId"] == request["table_id"]
    assert join["password"] == "table-secret"
    assert [card["cardName"] for card in join["deck"]["cards"]] == ["Forest", "Runeclaw Bear"]
    assert built["upstream"] is server
    assert built["tools"] == seat.mcp_engine.TOOLS
    assert built["optimize_play"] is False
    assert built["describe"] is seat.mcp_engine.describe_tool
    assert finished == [True]
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "completed"
    assert (manifest["engine"], manifest["tool_policy"]) == ("mcp", "mcp")
    assert manifest["games"][0]["winner"] == "ChatGPT 2"
    assert manifest["games"][0]["turns"] == 9
    assert manifest["usage"][0]["input_tokens"] == 5
    assert manifest["cleanup_complete"] is True
    assert server.proc in stopped
    assert server.calls[-1] == ("end_session", None)


def test_mcp_engine_server_that_stops_during_play_is_a_failure(mcp_harness):
    request, runtime, _stopped, _gateway, server, _built = mcp_harness
    server.sessions = [{"gameId": "g1", "game": {"turn": 1}}]

    def start(*_args, **_kwargs):
        server.proc.poll = lambda: 1
        return SimpleNamespace(poll=lambda: None, returncode=None)

    assert seat.run_player(request, runtime=runtime, start_player=start) == 1
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["status"] == "failed"
    assert "mcp/ game server stopped during play" in manifest["error"]
    assert manifest["cleanup_complete"] is True


@pytest.mark.parametrize(
    ("change", "message"),
    [
        ({"engine": "browser"}, "Unknown game engine"),
        ({"provider": "claude_subscription", "model": "claude-opus-5-5", "reasoning": "max"}, "does not support"),
        ({"wins_needed": 2}, "two-player single games"),
        ({"player_count": 3}, "two-player single games"),
        ({"proxy_url": "ws://example.com:8787"}, "local proxy"),
    ],
)
def test_mcp_engine_requests_outside_the_experiment_are_rejected(mcp_harness, change, message):
    request, *_ = mcp_harness
    request.update(change)
    with pytest.raises(ValueError, match=message):
        seat.validate_request(request)


def test_bridge_seat_records_the_winner_from_its_game_over_line(harness, monkeypatch):
    request, runtime, _stopped, _gateway = harness
    launch = runtime.launch_bridge

    def launch_with_winner(arguments, env, log, workdir):
        bridge = launch(arguments, env, log, workdir)
        log.write(f"[{request['username']}] Game over: Player Mad 1 is the winner\n")
        log.flush()
        return bridge

    runtime.launch_bridge = launch_with_winner
    monkeypatch.setattr(seat, "rpc", lambda *_: result({"available": True}))
    agent = SimpleNamespace(poll=lambda: 0, returncode=0)
    assert seat.run_player(request, runtime=runtime, start_player=Mock(return_value=agent)) == 0
    manifest = json.loads((Path(request["run"]) / "manifest.json").read_text())
    assert manifest["engine"] == "bridge"
    assert manifest["games"][0]["winner"] == "Mad 1"
