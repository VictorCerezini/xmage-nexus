"""Claude subscription isolation with fake settings and no model invocation."""

import json
import os
from dataclasses import replace

import pytest

from llm import claude_client, paths, providers


@pytest.fixture
def fake_claude(tmp_path, monkeypatch):
    executable = tmp_path / "claude.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"claude_executable": str(executable)})
    return executable


def flag(command, name):
    return command[command.index(name) + 1]


def test_command_disables_every_builtin_tool_and_allows_only_game_tools(fake_claude):
    command = claude_client.command("http://127.0.0.1:12345/mcp", model="claude-sonnet-5-5", reasoning="high")
    assert command[0] == str(fake_claude)
    assert flag(command, "--tools") == ""
    assert flag(command, "--setting-sources") == ""
    assert flag(command, "--permission-mode") == "dontAsk"
    assert flag(command, "--allowedTools").split(",") == claude_client.MCP_TOOLS
    assert len(claude_client.MCP_TOOLS) == 8
    assert "--strict-mcp-config" in command
    assert "--no-session-persistence" in command
    assert "--disable-slash-commands" in command
    assert json.loads(flag(command, "--mcp-config")) == {
        "mcpServers": {"mage": {"type": "http", "url": "http://127.0.0.1:12345/mcp"}}
    }
    assert (flag(command, "--model"), flag(command, "--effort")) == ("claude-sonnet-5-5", "high")
    # API-key-only and permission-bypass modes are never used.
    assert not {"--bare", "--dangerously-skip-permissions", "--allow-dangerously-skip-permissions"} & set(command)


def test_missing_executable_asks_for_setup(monkeypatch, tmp_path):
    monkeypatch.setattr(paths, "settings", lambda: {"claude_executable": str(tmp_path / "missing.exe")})
    with pytest.raises(RuntimeError, match=r"llm\.configure"):
        claude_client.command()


def test_credential_overrides_are_removed_only_from_child_environment(monkeypatch):
    for name in claude_client.CREDENTIAL_OVERRIDES:
        monkeypatch.setenv(name, "test-value-not-a-secret")
    cleaned = claude_client.clean_env(tool_timeout_seconds=7260)
    assert not claude_client.CREDENTIAL_OVERRIDES & set(cleaned)
    assert all(os.environ[name] == "test-value-not-a-secret" for name in claude_client.CREDENTIAL_OVERRIDES)
    assert cleaned["MCP_TOOL_TIMEOUT"] == "7260000"
    assert cleaned["CLAUDE_CODE_DISABLE_CLAUDE_MDS"] == "1"
    assert cleaned["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] == "1"
    assert cleaned["CLAUDE_CODE_DISABLE_MODEL_ACCESS_FALLBACK"] == "1"


def assistant(*blocks):
    return {"type": "assistant", "message": {"content": list(blocks)}}


def test_game_tool_calls_are_accepted():
    claude_client.require_game_only_events(
        [
            {"type": "system", "subtype": "init", "tools": claude_client.MCP_TOOLS},
            assistant({"type": "text", "text": "Attacking."}, {"type": "tool_use", "name": "mcp__mage__choose_action"}),
        ]
    )


@pytest.mark.parametrize(
    "event",
    [
        {"type": "system", "subtype": "init", "tools": [*claude_client.MCP_TOOLS, "Bash"]},
        assistant({"type": "tool_use", "name": "Read"}),
        assistant({"type": "tool_use", "name": "mcp__other__get_game_state"}),
        assistant({"type": "server_tool_use", "name": "web_search"}),
    ],
)
def test_non_game_tools_are_rejected(event):
    with pytest.raises(RuntimeError, match="boundary"):
        claude_client.require_game_only_events([event])


def test_usage_comes_from_result_events():
    usage = claude_client.usage(
        [
            assistant({"type": "text", "text": "x"}),
            {"type": "result", "num_turns": 3, "is_error": False, "usage": {"input_tokens": 10, "output_tokens": 2}},
        ]
    )
    assert usage == [{"num_turns": 3, "duration_ms": None, "is_error": False, "input_tokens": 10, "output_tokens": 2}]


def test_catalog_requires_subscription_login_and_saves_no_identity():
    catalog = claude_client.read_catalog(
        {
            "loggedIn": True,
            "authMethod": "claude.ai",
            "apiProvider": "firstParty",
            "email": "private@example.invalid",
            "orgId": "private-org",
            "orgName": "private-org-name",
            "subscriptionType": "private-plan",
        }
    )
    assert catalog["id"] == "claude_subscription"
    assert [item["id"] for item in catalog["models"]] == ["claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"]
    assert sum(item["isDefault"] for item in catalog["models"]) == 1
    assert "private" not in json.dumps(catalog)


@pytest.mark.parametrize(
    "status",
    [
        {"loggedIn": False, "authMethod": "none", "apiProvider": "firstParty"},
        {"loggedIn": True, "authMethod": "api_key", "apiProvider": "firstParty"},
        {"loggedIn": True, "authMethod": "claude.ai", "apiProvider": "bedrock"},
    ],
)
def test_catalog_rejects_other_authentication_modes(status):
    with pytest.raises(RuntimeError, match="claude auth login"):
        claude_client.read_catalog(status)


@pytest.mark.parametrize(
    "selection", [("gpt-6-astra", "high"), ("claude-opus-5-5", "ultra"), ("claude-fable-5-1", "low")]
)
def test_unavailable_model_or_effort_is_rejected(selection):
    catalog = claude_client.read_catalog({"loggedIn": True, "authMethod": "claude.ai", "apiProvider": "firstParty"})
    with pytest.raises(ValueError, match=r"not offered|does not support"):
        claude_client.validate_selection(catalog, *selection)


def test_one_missing_sign_in_does_not_hide_the_other_provider():
    def signed_out():
        raise RuntimeError("Sign into Codex with ChatGPT before opening subscription matches (codex login).")

    fake = {
        "chatgpt_subscription": replace(providers.PROVIDERS["chatgpt_subscription"], discover=signed_out),
        "claude_subscription": replace(
            providers.PROVIDERS["claude_subscription"], discover=lambda: {"id": "claude_subscription"}
        ),
    }
    catalog = providers.discover_all(fake)
    assert catalog["providers"] == [{"id": "claude_subscription"}]
    assert catalog["unavailable"][0]["id"] == "chatgpt_subscription"


def test_no_signed_in_provider_is_an_error():
    def signed_out():
        raise RuntimeError("signed out")

    fake = {key: replace(value, discover=signed_out) for key, value in providers.PROVIDERS.items()}
    with pytest.raises(RuntimeError, match="No subscription player"):
        providers.discover_all(fake)
