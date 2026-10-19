"""Shared OpenCode player isolation and lockdown with fake settings and no model invocation."""

import json
import os
from types import SimpleNamespace

import pytest

from llm import opencode_client, paths

FAKE_KEY = "test-key-not-a-secret"


@pytest.fixture
def fake_local(tmp_path, monkeypatch):
    executable = tmp_path / "opencode.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(executable)})
    monkeypatch.setattr(opencode_client, "OPENCODE_HOME", tmp_path / "opencode-home")
    monkeypatch.setattr(opencode_client, "AUTH_PATH", tmp_path / "opencode-home/data/opencode/auth.json")
    return executable


def test_config_keeps_builtin_tools_off_and_allows_only_game_tools():
    config = opencode_client.config("http://127.0.0.1:12345/mcp", "zai-coding-plan", tool_timeout_seconds=7260)
    assert config["tools"] == {"*": False, "mage_*": True}
    assert config["permission"] == {"*": "deny", "mage_*": "allow", "doom_loop": "allow"}
    assert config["agent"]["mage"]["tools"] == config["tools"]
    assert config["agent"]["mage"]["permission"] == config["permission"]
    assert config["mcp"] == {
        "mage": {
            "type": "remote",
            "url": "http://127.0.0.1:12345/mcp",
            "enabled": True,
            "oauth": False,
            "timeout": 7260000,
        }
    }
    assert config["enabled_providers"] == ["zai-coding-plan"]
    assert "provider" not in config
    assert (config["plugin"], config["instructions"], config["share"]) == ([], [], "disabled")
    assert config["username"] == "player"


def test_config_can_register_models_missing_from_the_catalog():
    config = opencode_client.config(None, "openrouter", {"a/alpha:free": {"tool_call": True}})
    assert config["provider"] == {"openrouter": {"models": {"a/alpha:free": {"tool_call": True}}}}
    assert "mcp" not in config


def test_command_selects_the_game_agent_and_effort(fake_local):
    command = opencode_client.command("zai-coding-plan", "glm-5.3", "high")
    assert command[:2] == [str(fake_local), "run"]
    assert command[command.index("--agent") + 1] == "mage"
    assert command[command.index("--model") + 1] == "zai-coding-plan/glm-5.3"
    assert command[command.index("--variant") + 1] == "high"
    assert "--pure" in command
    # Auto-approval would let an "ask" permission through.
    assert "--auto" not in command
    assert "--variant" not in opencode_client.command("zai-coding-plan", "glm-5.3", "default")


def test_missing_executable_asks_for_setup(monkeypatch, tmp_path):
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(tmp_path / "missing.exe")})
    with pytest.raises(RuntimeError, match=r"llm\.configure"):
        opencode_client.command("openrouter", "a/alpha:free")


def test_child_environment_is_isolated_from_the_user_profile(fake_local, monkeypatch):
    for name in opencode_client.CREDENTIAL_OVERRIDES | {"OPENCODE_CONFIG_CONTENT", "OPENCODE_API_KEY"}:
        monkeypatch.setenv(name, "test-value-not-a-secret")
    cleaned = opencode_client.clean_env()
    assert not opencode_client.CREDENTIAL_OVERRIDES & set(cleaned)
    assert {"ZHIPU_API_KEY", "OPENROUTER_API_KEY"} <= opencode_client.CREDENTIAL_OVERRIDES
    assert "OPENCODE_CONFIG_CONTENT" not in cleaned
    assert "OPENCODE_API_KEY" not in cleaned
    assert os.environ["OPENCODE_API_KEY"] == "test-value-not-a-secret"
    home = opencode_client.OPENCODE_HOME
    assert cleaned["HOME"] == cleaned["USERPROFILE"] == str(home / "home")
    assert cleaned["XDG_DATA_HOME"] == str(home / "data")
    assert cleaned["XDG_CONFIG_HOME"] == str(home / "config")
    for flag in ("CLAUDE_CODE", "EXTERNAL_SKILLS", "PROJECT_CONFIG", "AUTOUPDATE"):
        assert cleaned[f"OPENCODE_DISABLE_{flag}"] == "1"


def test_launch_sends_the_prompt_on_stdin_with_inline_config(fake_local, monkeypatch):
    written = []
    stdin = SimpleNamespace(write=written.append, close=lambda: None)
    launched = {}

    def popen(cmd, **kwargs):
        launched.update(cmd=cmd, **kwargs)
        return SimpleNamespace(stdin=stdin)

    monkeypatch.setattr(opencode_client.subprocess, "Popen", popen)
    opencode_client.launch("play", None, None, {"mcp": {"mage": {"url": "x"}}}, ["opencode", "run"])
    assert json.loads(launched["env"]["OPENCODE_CONFIG_CONTENT"]) == {"mcp": {"mage": {"url": "x"}}}
    assert launched["cwd"] == paths.PLAYER_CWD
    assert written == [b"play"]


def tool_event(name, status="completed"):
    return {"type": "tool_use", "part": {"type": "tool", "tool": name, "state": {"status": status}}}


def test_game_tools_and_the_no_op_invalid_tool_are_accepted():
    opencode_client.require_game_only_events(
        [tool_event("mage_get_game_state"), tool_event("mage_pass_priority"), tool_event("invalid", "error")]
    )


@pytest.mark.parametrize("name", ["bash", "read", "webfetch", "edit", "task", "skill"])
def test_non_game_tools_are_rejected(name):
    with pytest.raises(RuntimeError, match="boundary"):
        opencode_client.require_game_only_events([tool_event(name, "error")])


def test_usage_totals_step_finish_events():
    step = {
        "type": "step-finish",
        "tokens": {"input": 10, "output": 2, "reasoning": 3, "cache": {"read": 4, "write": 0}},
        "cost": 0,
    }
    events = [{"type": "step_finish", "part": step}, {"type": "step_finish", "part": step}, tool_event("mage_x")]
    assert opencode_client.usage(events) == [
        {"steps": 2, "input": 20, "output": 4, "reasoning": 6, "cache_read": 8, "cost": 0}
    ]
    assert opencode_client.usage([]) == []


def test_error_message_is_the_last_error_event():
    events = [
        {"type": "error", "error": {"data": {"message": "first"}}},
        tool_event("mage_x"),
        {"type": "error", "error": {"data": {"message": "last"}}},
    ]
    assert opencode_client.error_message(events) == "last"
    assert opencode_client.error_message([tool_event("mage_x")]) is None


def test_opencode_model_listing_is_parsed():
    text = (
        "openrouter/a/alpha:free\n"
        + json.dumps({"id": "a/alpha:free", "variants": {"low": {}, "high": {}}}, indent=2)
        + "\nopenrouter/b/beta:free\n"
        + json.dumps({"id": "b/beta:free", "variants": {}}, indent=2)
        + "\n"
    )
    assert opencode_client.parse_models(text) == {"a/alpha:free": ["low", "high"], "b/beta:free": []}


@pytest.mark.parametrize("auth", [None, {}, {"opencode": {"type": "api", "key": FAKE_KEY}}])
def test_missing_provider_sign_in_is_reported(fake_local, auth):
    if auth is not None:
        opencode_client.AUTH_PATH.parent.mkdir(parents=True)
        opencode_client.AUTH_PATH.write_text(json.dumps(auth))
    with pytest.raises(RuntimeError, match=r"llm\.sign_in openrouter"):
        opencode_client.read_key("openrouter", "python -m llm.sign_in openrouter")


def test_stored_key_is_read_only_for_its_provider(fake_local):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"zai-coding-plan": {"type": "api", "key": FAKE_KEY}}))
    assert opencode_client.read_key("zai-coding-plan", "python -m llm.sign_in zai") == FAKE_KEY
    with pytest.raises(RuntimeError, match=r"llm\.sign_in openrouter"):
        opencode_client.read_key("openrouter", "python -m llm.sign_in openrouter")
