"""A desktop install keeps its state in the launcher's data folder and finds the CLIs on every refresh."""

import json

import pytest

from llm import claude_client, executables, opencode_client, paths, subscription_client


@pytest.fixture
def desktop_home(tmp_path, monkeypatch):
    monkeypatch.setattr(paths, "DATA_HOME", tmp_path)
    monkeypatch.setattr(paths, "SETTINGS_PATH", tmp_path / "settings.local.json")
    monkeypatch.setattr(paths, "DESKTOP_PATH", tmp_path / "desktop.json")
    return tmp_path


def installed(monkeypatch, **found):
    detected = {"codex_executable": None, "claude_executable": None, "opencode_executable": None}
    monkeypatch.setattr(executables, "detect", lambda: {**detected, **found})


def test_desktop_settings_need_no_configure_and_use_the_bundled_java(desktop_home, monkeypatch):
    installed(monkeypatch, codex_executable="C:/npm/codex.exe")
    (desktop_home / "desktop.json").write_text(
        json.dumps({"java_home": "C:/Nexus/jre", "server_dir": "C:/Nexus/server"})
    )
    assert paths.settings() == {
        "codex_executable": "C:/npm/codex.exe",
        "claude_executable": None,
        "opencode_executable": None,
        "java_home": "C:/Nexus/jre",
        "server_dir": "C:/Nexus/server",
    }


def test_desktop_settings_follow_newly_installed_clis(desktop_home, monkeypatch):
    installed(monkeypatch)
    assert paths.settings()["claude_executable"] is None
    installed(monkeypatch, claude_executable="C:/Claude/claude.exe")
    assert paths.settings()["claude_executable"] == "C:/Claude/claude.exe"


def test_local_settings_override_detection_only_where_they_name_a_path(desktop_home, monkeypatch):
    installed(monkeypatch, codex_executable="C:/npm/codex.exe", opencode_executable="C:/npm/opencode.exe")
    (desktop_home / "settings.local.json").write_text(
        json.dumps({"codex_executable": "D:/codex.exe", "opencode_executable": None})
    )
    settings = paths.settings()
    assert settings["codex_executable"] == "D:/codex.exe"
    assert settings["opencode_executable"] == "C:/npm/opencode.exe"


def test_a_checkout_still_needs_configure(tmp_path, monkeypatch):
    monkeypatch.setattr(paths, "DATA_HOME", None)
    monkeypatch.setattr(paths, "SETTINGS_PATH", tmp_path / "settings.local.json")
    with pytest.raises(RuntimeError, match=r"llm\.configure"):
        paths.settings()


@pytest.mark.parametrize(
    ("command", "message"),
    [
        (subscription_client.executable, "Codex is not installed: install it, sign in with codex login,"),
        (claude_client.executable, "Claude Code is not installed: install it, sign in with claude auth login"),
        (opencode_client.executable, "OpenCode is not installed: install it, then refresh the model list"),
    ],
)
def test_desktop_explains_a_missing_cli_without_configure(desktop_home, monkeypatch, command, message):
    installed(monkeypatch)
    with pytest.raises(RuntimeError) as error:
        command()
    assert message in str(error.value)
    assert "llm.configure" not in str(error.value)


def test_desktop_key_sign_in_names_the_bundled_python_and_data_folder(desktop_home):
    command = paths.sign_in_command("openrouter")
    assert f"$env:NEXUS_LLM_HOME = '{desktop_home}'" in command
    assert "-m llm.sign_in openrouter" in command
    assert "python -m llm.sign_in" not in command


def test_checkout_key_sign_in_is_the_module_command(monkeypatch):
    monkeypatch.setattr(paths, "DATA_HOME", None)
    assert paths.sign_in_command("zai") == "python -m llm.sign_in zai"
