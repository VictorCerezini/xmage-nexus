"""Verify subscription isolation with fake settings and no model invocation."""

import json
import os

from llm import paths, subscription_client


def test_subscription_command_forces_chatgpt_and_restricts_tools(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": "C:/Tools/codex.exe"})
    command = subscription_client.command("http://127.0.0.1:12345/mcp")
    configs = {
        command[index + 1].split("=", 1)[0]: json.loads(command[index + 1].split("=", 1)[1])
        for index, value in enumerate(command)
        if value == "-c"
    }
    assert configs["forced_login_method"] == "chatgpt"
    assert configs["model_provider"] == "openai"
    assert configs["features.shell_tool"] is False
    assert configs["features.browser_use"] is False
    assert configs["features.apps"] is False
    assert configs["features.multi_agent"] is False
    assert configs["project_doc_max_bytes"] == 0
    assert configs["mcp_servers.mage.enabled_tools"] == subscription_client.TOOLS
    assert "--ignore-user-config" in command
    assert "--ephemeral" in command


def test_game_tools_are_called_directly_with_a_trimmed_prompt(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": "C:/Tools/codex.exe"})
    command = subscription_client.command("http://127.0.0.1:12345/mcp")
    configs = dict(command[index + 1].split("=", 1) for index, value in enumerate(command) if value == "-c")
    assert json.loads(configs["features.code_mode.direct_only_tool_namespaces"]) == ["mcp__mage"]
    # A plain code_mode boolean conflicts with the table that holds the namespace list.
    assert "features.code_mode" not in configs
    for key in (
        "skills.include_instructions",
        "agents.enabled",
        "include_collaboration_mode_instructions",
        "include_permissions_instructions",
        "include_environment_context",
    ):
        assert configs[key] == "false"
    # Every setting is passed for this run only; the user's own Codex config is never read or written.
    assert all(not value.endswith(".toml") for value in command)


def test_provider_credentials_are_removed_only_from_child_environment(monkeypatch):
    names = [
        "OPENAI_API_KEY",
        "CODEX_API_KEY",
        "CODEX_ACCESS_TOKEN",
        "ANTHROPIC_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY",
        "OPENROUTER_API_KEY",
        "OPENAI_BASE_URL",
    ]
    for name in names:
        monkeypatch.setenv(name, "test-value-not-a-secret")
    cleaned = subscription_client.clean_env()
    assert all(name not in cleaned for name in names)
    assert all(os.environ[name] == "test-value-not-a-secret" for name in names)


def test_gui_selected_model_and_reasoning_reach_the_cli(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": "C:/Tools/codex.exe"})
    command = subscription_client.command(model="test-catalog-model", reasoning="high")
    assert command[command.index("--model") + 1] == "test-catalog-model"
    assert 'model_reasoning_effort="high"' in command


def test_gui_tool_wait_can_span_the_human_game_without_changing_model_or_effort(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": "C:/Tools/codex.exe"})
    command = subscription_client.command(
        "http://127.0.0.1:12345/mcp", model="test-model", reasoning="high", tool_timeout_seconds=7260
    )
    assert "mcp_servers.mage.tool_timeout_sec=7260" in command
    assert command[command.index("--model") + 1] == "test-model"
    assert 'model_reasoning_effort="high"' in command
