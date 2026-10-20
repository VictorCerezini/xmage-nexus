"""Local settings hold executable paths and folders only, never credentials."""

import json
from types import SimpleNamespace

import pytest

from llm import catalog, configure, executables, paths, sign_in


def arguments(**values):
    names = ("codex", "claude", "opencode", "java_home", "opencode_home", "player_cwd", "from_mage_bench")
    return SimpleNamespace(**{name: values.get(name) for name in names})


@pytest.fixture
def nothing_on_path(monkeypatch, tmp_path):
    monkeypatch.setattr(executables.shutil, "which", lambda _name: None)
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))
    monkeypatch.setattr(paths, "SETTINGS_PATH", tmp_path / "settings.local.json")
    monkeypatch.setattr(paths, "RUN_ROOT", tmp_path / "run")


def test_mage_bench_setup_is_reused_without_copying_its_sign_ins(nothing_on_path, tmp_path):
    local = tmp_path / "mage-bench" / ".windows-local"
    (local / "opencode-home" / "data" / "opencode").mkdir(parents=True)
    (local / "opencode-home" / "data" / "opencode" / "auth.json").write_text('{"openrouter": {"key": "secret"}}')
    codex = tmp_path / "codex.exe"
    codex.write_text("")
    (local / "settings.json").write_text(
        json.dumps({"codex_executable": str(codex), "claude_executable": None, "maven_path": "mvn.cmd"})
    )
    values = configure.resolve(arguments(from_mage_bench=str(tmp_path / "mage-bench")))
    assert values["codex_executable"] == str(codex.resolve())
    assert values["opencode_home"] == str((local / "opencode-home").resolve())
    assert values["claude_executable"] is None
    assert "maven_path" not in values
    assert "secret" not in json.dumps(values)


def test_an_explicit_path_overrides_discovery(nothing_on_path, tmp_path):
    claude = tmp_path / "claude.exe"
    claude.write_text("")
    assert configure.resolve(arguments(claude=str(claude)))["claude_executable"] == str(claude.resolve())


def test_a_missing_executable_is_rejected(nothing_on_path, tmp_path):
    with pytest.raises(ValueError, match="does not exist"):
        configure.resolve(arguments(codex=str(tmp_path / "missing.exe")))


def test_at_least_one_player_cli_is_required(nothing_on_path):
    with pytest.raises(ValueError, match="Install the Codex CLI"):
        configure.resolve(arguments())


def test_npm_launcher_resolves_to_the_native_codex(monkeypatch, tmp_path):
    native = tmp_path / executables.CODEX_NPM_EXE
    native.parent.mkdir(parents=True)
    native.write_text("")
    monkeypatch.setattr(executables.shutil, "which", lambda _name: str(tmp_path / "codex.cmd"))
    assert executables.native_executable("codex", executables.CODEX_NPM_EXE) == str(native)


def test_main_writes_the_settings_and_the_player_folder(nothing_on_path, tmp_path):
    opencode = tmp_path / "opencode.exe"
    opencode.write_text("")
    assert configure.main(["--opencode", str(opencode)]) == 0
    settings = json.loads(paths.SETTINGS_PATH.read_text())
    assert settings["opencode_executable"] == str(opencode.resolve())
    assert settings["codex_executable"] is None
    assert (paths.RUN_ROOT / "player-sandbox").is_dir()


def test_catalog_lists_the_signed_in_providers(monkeypatch, tmp_path):
    monkeypatch.setattr(paths, "RUN_ROOT", tmp_path / "run")
    monkeypatch.setattr(paths, "PLAYER_CWD", tmp_path / "run" / "player-sandbox")
    listing = {"providers": [{"id": "claude_subscription", "models": []}], "unavailable": []}
    monkeypatch.setattr(catalog.providers, "discover_all", lambda: listing)
    out = tmp_path / "catalog.json"
    assert catalog.main([str(out)]) == 0
    assert json.loads(out.read_text()) == listing


def test_catalog_without_any_sign_in_fails(monkeypatch, tmp_path, capsys):
    monkeypatch.setattr(paths, "RUN_ROOT", tmp_path / "run")
    monkeypatch.setattr(paths, "PLAYER_CWD", tmp_path / "run" / "player-sandbox")

    def discover_all():
        raise RuntimeError("No subscription player is available: test")

    monkeypatch.setattr(catalog.providers, "discover_all", discover_all)
    assert catalog.main([]) == 1
    assert "No subscription player" in capsys.readouterr().err


def test_sign_in_reads_only_provider_names_and_skips_a_done_sign_in(monkeypatch, tmp_path, capsys):
    auth = tmp_path / "auth.json"
    auth.write_text(json.dumps({"openrouter": {"type": "api", "key": "sk-or-test"}}))
    monkeypatch.setattr(sign_in.opencode_client, "AUTH_PATH", auth)
    monkeypatch.setattr(sign_in.subprocess, "run", lambda *_args, **_kwargs: pytest.fail("no login needed"))
    assert sign_in.main(["openrouter"]) == 0
    assert "sk-or-test" not in capsys.readouterr().out
    assert sign_in.signed_in("zai-coding-plan") is False
    assert sign_in.main(["other"]) == 2
