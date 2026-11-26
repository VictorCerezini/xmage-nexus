"""Google (Antigravity CLI) seats with a fake agy: isolation, the catalog and the event trace, no model run."""

import json
import os
import subprocess
from dataclasses import replace

import pytest

from llm import antigravity_client, paths, providers
from llm.subscription_client import TOOLS

# What `agy models` printed for a Google AI plan (agy 1.5.17).
LISTING = (
    "gemini-4-hydrogen-high\tGemini 4 Hydrogen (High)\n"
    "gemini-4-hydrogen-xhigh\tGemini 4 Hydrogen (XHigh)\n"
    "gemini-4-hydrogen-medium\tGemini 4 Hydrogen (Medium)\n"
    "gemini-4-hydrogen-low\tGemini 4 Hydrogen (Low)\n"
    "gemini-3.8-flash-high\tGemini 3.8 Flash (High)\n"
    "gemini-3.8-flash-medium\tGemini 3.8 Flash (Medium)\n"
    "gemini-3.8-flash-low\tGemini 3.8 Flash (Low)\n"
    "claude-sonnet-4-6\tClaude Sonnet 4.6 (Thinking)\n"
    "claude-opus-4-6-thinking\tClaude Opus 4.6 (Thinking)\n"
    "gpt-oss-120b-medium\tGPT-OSS 120B (Medium)\n"
)


@pytest.fixture
def fake_agy(tmp_path, monkeypatch):
    executable = tmp_path / "agy.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"agy_executable": str(executable)})
    monkeypatch.setattr(paths, "RUN_ROOT", tmp_path / "run")
    monkeypatch.setattr(paths, "PLAYER_CWD", tmp_path / "player")
    return executable


def flag(command, name):
    return command[command.index(name) + 1]


def catalog():
    return antigravity_client.read_catalog(antigravity_client.read_listing(LISTING))


def test_the_picker_shows_each_model_once_with_its_reasoning_levels():
    models = {item["id"]: item for item in catalog()["models"]}
    assert list(models) == [
        "gemini-4-hydrogen",
        "gemini-3.8-flash",
        "claude-sonnet-4-6",
        "claude-opus-4-6-thinking",
        "gpt-oss-120b",
    ]
    assert models["gemini-4-hydrogen"] == {
        "id": "gemini-4-hydrogen",
        "name": "Gemini 4 Hydrogen",
        "efforts": ["low", "medium", "high", "xhigh"],
        "defaultEffort": "medium",
        "isDefault": True,
    }
    assert models["gemini-3.8-flash"]["efforts"] == ["low", "medium", "high"]
    # "(Thinking)" is part of the name, not a level
    assert models["claude-opus-4-6-thinking"]["name"] == "Claude Opus 4.6 (Thinking)"
    assert models["claude-opus-4-6-thinking"]["efforts"] == ["default"]
    assert models["gpt-oss-120b"] == {
        "id": "gpt-oss-120b",
        "name": "GPT-OSS 120B",
        "efforts": ["medium"],
        "defaultEffort": "medium",
        "isDefault": False,
    }
    assert sum(item["isDefault"] for item in models.values()) == 1
    assert catalog()["id"] == "google_subscription"


@pytest.mark.parametrize(
    ("model", "reasoning", "listed"),
    [
        ("gemini-4-hydrogen", "xhigh", "gemini-4-hydrogen-xhigh"),
        ("gpt-oss-120b", "medium", "gpt-oss-120b-medium"),
        ("claude-opus-4-6-thinking", "default", "claude-opus-4-6-thinking"),
    ],
)
def test_a_choice_runs_the_model_agy_listed(model, reasoning, listed, fake_agy):
    antigravity_client.validate_selection(catalog(), model, reasoning)
    assert flag(antigravity_client.command(model=model, reasoning=reasoning), "--model") == listed
    assert listed in LISTING


@pytest.mark.parametrize(
    "selection", [("gemini-4-hydrogen", "max"), ("gemini-4-carbon", "high"), ("claude-sonnet-4-6", "high")]
)
def test_unavailable_model_or_level_is_rejected(selection):
    with pytest.raises(ValueError, match=r"not in the Google model catalog|does not support"):
        antigravity_client.validate_selection(catalog(), *selection)


def test_command_plays_as_the_game_agent_with_the_prompt_on_stdin(fake_agy):
    command = antigravity_client.command(model="gemini-3.8-flash", reasoning="low")
    assert command[0] == str(fake_agy)
    assert flag(command, "--agent") == "mage-player"
    assert flag(command, "--output-format") == "stream-json"
    assert flag(command, "--input-format") == "stream-json"
    assert command[-1] == "--print="
    assert "--disable-slash-commands" in command
    # Approving every tool would undo the permission rules.
    assert "--dangerously-skip-permissions" not in command


def test_the_home_offers_only_the_game_server_and_no_builtin_tools(tmp_path):
    home = tmp_path / "antigravity-home"
    antigravity_client.write_home(home, "http://127.0.0.1:4321/mcp", tool_timeout_seconds=7260)
    servers = json.loads((home / ".gemini/config/mcp_config.json").read_text(encoding="utf-8"))["mcpServers"]
    assert list(servers) == ["mage"]
    assert servers["mage"]["serverUrl"] == "http://127.0.0.1:4321/mcp"
    assert servers["mage"]["timeoutSeconds"] == 7260
    assert servers["mage"]["tools"] == {name: {"eager": True} for name in TOOLS}
    agent = (home / ".gemini/config/agents/mage-player.md").read_text(encoding="utf-8")
    assert "excludeDefaultComponents: true" in agent
    assert "inheritCustomizations: false" in agent
    assert "This is a game, not a coding task." in agent
    settings = json.loads((home / ".gemini/antigravity-cli/settings.json").read_text(encoding="utf-8"))
    assert settings["permissions"]["allow"] == ["mcp(mage/*)"]
    assert {"command(*)", "read_file(*)", "write_file(*)", "read_url(*)", "execute_url(*)"} <= set(
        settings["permissions"]["deny"]
    )
    # A connection test has no game server.
    antigravity_client.write_home(home)
    assert json.loads((home / ".gemini/config/mcp_config.json").read_text(encoding="utf-8")) == {"mcpServers": {}}


def test_each_seat_gets_its_own_home_and_the_prompt_as_one_user_message(fake_agy, tmp_path, monkeypatch):
    started = {}

    class Process:
        def __init__(self, command, **options):
            started.update(command=command, **options)
            self.stdin = self

        def write(self, data):
            started["stdin"] = data

        def close(self):
            started["closed"] = True

    monkeypatch.setattr(antigravity_client.subprocess, "Popen", Process)
    run = tmp_path / "table-seat-2"
    run.mkdir()
    with (run / "agy-events.jsonl").open("w") as out, (run / "agy-stderr.log").open("w") as err:
        antigravity_client.start(
            "Play.", out, err, "http://127.0.0.1:4321/mcp", model="gemini-4-hydrogen", reasoning="high"
        )
    home = run / "antigravity-home"
    assert started["env"]["USERPROFILE"] == str(home)
    assert started["env"]["HOME"] == str(home)
    assert started["cwd"] == paths.PLAYER_CWD
    assert (home / ".gemini/config/mcp_config.json").is_file()
    assert json.loads(started["stdin"]) == {"event": "user", "message": {"content": "Play."}}
    assert started["closed"]


def test_credential_overrides_are_removed_only_from_child_environment(monkeypatch, tmp_path):
    for name in antigravity_client.CREDENTIAL_OVERRIDES:
        monkeypatch.setenv(name, "test-value-not-a-secret")
    cleaned = antigravity_client.clean_env(tmp_path)
    assert not antigravity_client.CREDENTIAL_OVERRIDES & {key.upper() for key in cleaned}
    assert all(os.environ[name] == "test-value-not-a-secret" for name in antigravity_client.CREDENTIAL_OVERRIDES)


def step(step_type, tool_name=None, **fields):
    return {"event": "step_update", "step_update": {"step_type": step_type, "tool_name": tool_name, **fields}}


def test_game_tool_calls_are_accepted():
    antigravity_client.require_game_only_events(
        [
            # init lists the CLI's whole tool registry, also for an agent without them
            {"event": "init", "init": {"tools": ["run_command", "search_web"], "agent": "mage-player"}},
            step("user_input"),
            step("agent_response", text_delta="Attacking."),
            step("tool", "mcp_mage_get_game_state", state="ACTIVE"),
            step("tool", "mcp_mage_choose_action", state="DONE"),
            step("tool", "mcp_mage_report_problem", state="DONE"),
            # offered only at a table whose host turned chat on
            step("tool", "mcp_mage_send_chat_message", state="DONE"),
        ]
    )


@pytest.mark.parametrize(
    "tool_name",
    ["run_command", "read_file", "search_web", "call_mcp_tool", "mcp_other_get_game_state", "mcp_mage_read_file"],
)
def test_non_game_tools_are_rejected(tool_name):
    with pytest.raises(RuntimeError, match="boundary"):
        antigravity_client.require_game_only_events([step("tool", tool_name, state="ERROR")])


def test_usage_comes_from_the_result_or_adds_up_the_model_requests():
    usage = {"input_tokens": 10, "output_tokens": 2, "thinking_tokens": 1, "cache_read_tokens": 5, "total_tokens": 13}
    summary = {"status": "SUCCESS", "num_turns": 1, "duration_seconds": 4.5}
    result = {"event": "result", "result": {**summary, "usage": usage}}
    assert antigravity_client.usage([step("agent_response", usage=usage), result]) == [
        {"status": "SUCCESS", "num_turns": 1, "duration_seconds": 4.5, **usage}
    ]
    # stopped after its game ended: no result, but each request's usage was printed
    assert antigravity_client.usage([step("agent_response", usage=usage), step("agent_response", usage=usage)]) == [
        {"status": "stopped", "model_requests": 2, **{key: value * 2 for key, value in usage.items()}}
    ]
    assert antigravity_client.usage([step("user_input")]) == []


def test_failure_reason_is_the_cli_error():
    failed = {"event": "result", "result": {"status": "ERROR", "finished_reason": "error", "error": "quota exceeded"}}
    assert antigravity_client.failure_reason([step("error_message"), failed]) == "quota exceeded"
    assert providers.lasting_limit(antigravity_client.failure_reason([failed]))
    assert antigravity_client.failure_reason([{"event": "result", "result": {"status": "SUCCESS"}}]) is None
    assert antigravity_client.failure_reason([step("tool", "mcp_mage_pass_priority")]) is None


def ran(stdout="", stderr="", returncode=0):
    def run(command, **options):
        assert command[1:] == ["models"]
        assert options["env"]["USERPROFILE"] == str(paths.RUN_ROOT / "antigravity-home")
        return subprocess.CompletedProcess(command, returncode, stdout, stderr)

    return run


def test_discover_reads_the_model_list_with_its_own_home(fake_agy, monkeypatch):
    monkeypatch.setattr(antigravity_client.subprocess, "run", ran(LISTING, "Fetching available models...\n"))
    assert antigravity_client.discover() == catalog()


def test_a_signed_out_cli_says_how_to_sign_in(fake_agy, monkeypatch):
    monkeypatch.setattr(
        antigravity_client.subprocess, "run", ran(stderr="error: You are not logged into Antigravity.\n", returncode=1)
    )
    with pytest.raises(RuntimeError) as caught:
        antigravity_client.discover()
    # Not a SignInError: the app would word its command as adding a key.
    assert not isinstance(caught.value, paths.SignInError)
    assert str(caught.value) == antigravity_client.SIGN_IN_STEPS
    assert "run agy and sign in" in str(caught.value)
    assert "key" not in str(caught.value)


def test_another_failure_says_what_agy_printed(fake_agy, monkeypatch):
    failed = ran(stderr="Fetching...\nerror: network down\n", returncode=1)
    monkeypatch.setattr(antigravity_client.subprocess, "run", failed)
    with pytest.raises(RuntimeError, match="network down"):
        antigravity_client.discover()


def test_missing_executable_asks_for_setup(monkeypatch, tmp_path):
    monkeypatch.setattr(paths, "settings", lambda: {"agy_executable": str(tmp_path / "missing.exe")})
    with pytest.raises(RuntimeError, match=r"llm\.configure"):
        antigravity_client.command()


def test_the_provider_is_offered_next_to_the_others():
    provider = providers.get("google_subscription")
    assert provider.label == "Google"
    assert provider.start is antigravity_client.start
    others = [item for item in providers.PROVIDERS.values() if item is not provider]
    assert provider.events_file not in {item.events_file for item in others}

    def signed_out():
        raise RuntimeError(antigravity_client.SIGN_IN_STEPS)

    fake = {
        "google_subscription": replace(provider, discover=signed_out),
        "claude_subscription": replace(providers.PROVIDERS["claude_subscription"], discover=lambda: {"id": "x"}),
    }
    entry = providers.discover_all(fake)["unavailable"][0]
    assert entry == {
        "id": "google_subscription",
        "playerName": "Google",
        "reason": antigravity_client.SIGN_IN_STEPS,
    }
