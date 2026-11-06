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


@pytest.fixture
def opencode2(fake_local, monkeypatch):
    monkeypatch.setattr(opencode_client, "major_version", lambda _exe: 2)
    return fake_local


def test_command_selects_the_game_agent_and_effort(fake_local):
    # The fake executable cannot report a version, so it is taken for OpenCode 1.
    assert opencode_client.major_version(str(fake_local)) == 1
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


def test_a_missing_model_or_refused_key_is_a_lasting_error():
    def error(status, *, retryable):
        return [{"type": "error", "error": {"data": {"message": "x", "statusCode": status, "isRetryable": retryable}}}]

    assert opencode_client.lasting_error(error(404, retryable=False))
    assert opencode_client.lasting_error(error(401, retryable=False))
    assert not opencode_client.lasting_error(error(429, retryable=True))
    assert not opencode_client.lasting_error(error(500, retryable=False))
    assert not opencode_client.lasting_error(error(404, retryable=True))
    assert not opencode_client.lasting_error([{"type": "error", "error": {"data": {"message": "x"}}}])
    assert not opencode_client.lasting_error([tool_event("mage_x")])


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
        opencode_client.read_key("openrouter", "OpenRouter", "openrouter")


def test_stored_key_is_read_only_for_its_provider(fake_local):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"zai-coding-plan": {"type": "api", "key": FAKE_KEY}}))
    assert opencode_client.read_key("zai-coding-plan", "Z.ai GLM Coding Plan", "zai") == FAKE_KEY
    with pytest.raises(RuntimeError, match=r"llm\.sign_in openrouter"):
        opencode_client.read_key("openrouter", "OpenRouter", "openrouter")


def test_opencode2_names_the_variant_after_the_model_without_pure(opencode2):
    command = opencode_client.command("opencode", "deepseek-v4.1-flash", "low")
    assert command == [
        str(opencode2),
        "run",
        "--format",
        "json",
        "--agent",
        "mage",
        "--model",
        "opencode/deepseek-v4.1-flash#low",
    ]
    default = opencode_client.command("opencode", "deepseek-v4.1-flash", "default")
    assert default[default.index("--model") + 1] == "opencode/deepseek-v4.1-flash"
    assert "--auto" not in default


@pytest.mark.parametrize(("printed", "major"), [("opencode v2.2.5\n", 2), ("1.19.3\n", 1), ("", 1)])
def test_major_version_is_read_once_per_executable(fake_local, monkeypatch, printed, major):
    calls = []
    monkeypatch.setattr(opencode_client, "_MAJOR_VERSIONS", {})
    monkeypatch.setattr(
        opencode_client, "run_cli", lambda args, **_kwargs: calls.append(args) or SimpleNamespace(stdout=printed)
    )
    assert opencode_client.major_version(str(fake_local)) == major
    assert opencode_client.major_version(str(fake_local)) == major
    assert calls == [["--version"]]
    assert opencode_client.major_version(str(fake_local.parent / "missing.exe")) == 1


def test_a_shell_folder_is_not_passed_to_opencode(fake_local, monkeypatch):
    monkeypatch.setenv("PWD", "C:/Users/someone")
    assert "PWD" not in opencode_client.clean_env()


class FakeServer:
    def __init__(self, exit_code=None):
        self.exit_code = exit_code
        self.returncode = exit_code

    def poll(self):
        return self.exit_code


@pytest.fixture
def fake_api(opencode2, monkeypatch):
    """OpenCode 2's server, started with subprocess.Popen and asked with api(); nothing runs."""
    state = {
        "mcp": [None, {"data": []}, {"data": [{"name": "mage", "status": {"status": "connected"}}]}],
        "session": {"data": {"id": "ses_test"}},
        "server": FakeServer(),
        "requests": [],
        "stopped": [],
    }

    def popen(cmd, **kwargs):
        state.update(serve=cmd, serve_env=dict(kwargs["env"]))
        return state["server"]

    def api(exe, url, env, method, path, body=None):
        state["requests"].append((method, path, body, env.get("OPENCODE_PASSWORD")))
        if path == "/api/mcp":
            return state["mcp"].pop(0) if len(state["mcp"]) > 1 else state["mcp"][0]
        return state["session"]

    monkeypatch.setattr(opencode_client.subprocess, "Popen", popen)
    monkeypatch.setattr(opencode_client, "api", api)
    monkeypatch.setattr(opencode_client, "stop_process", state["stopped"].append)
    monkeypatch.setattr(opencode_client.time, "sleep", lambda _seconds: None)
    return state


def test_opencode2_server_is_private_and_the_session_repeats_the_game_rules(fake_api):
    env = {}
    server, url, session = opencode_client.start_server("opencode.exe", env, None)
    serve = fake_api["serve"]
    assert serve[:2] == ["opencode.exe", "serve"]
    assert serve[serve.index("--hostname") + 1] == "127.0.0.1"
    assert url == "http://127.0.0.1:" + serve[serve.index("--port") + 1]
    # A random password, which run and api get through the same environment.
    password = env["OPENCODE_PASSWORD"]
    assert len(password) >= 24
    assert fake_api["serve_env"]["OPENCODE_PASSWORD"] == password
    # The player starts only once the game tools are connected, and its session denies everything else.
    assert [request[1] for request in fake_api["requests"]] == ["/api/mcp"] * 3 + ["/api/session"]
    assert {request[3] for request in fake_api["requests"]} == {password}
    method, _, body, _ = fake_api["requests"][-1]
    assert method == "POST"
    assert body == {"title": "XMage game", "permissions": opencode_client.SESSION_PERMISSIONS}
    assert opencode_client.SESSION_PERMISSIONS == [
        {"action": "*", "resource": "*", "effect": "deny"},
        {"action": "mage_*", "resource": "*", "effect": "allow"},
        {"action": "doom_loop", "resource": "*", "effect": "allow"},
    ]
    assert (server, session, fake_api["stopped"]) == (fake_api["server"], "ses_test", [])


def test_opencode2_server_that_stops_or_never_connects_the_tools_is_stopped(fake_api, monkeypatch):
    fake_api["server"] = FakeServer(exit_code=3)
    with pytest.raises(RuntimeError, match="stopped while starting"):
        opencode_client.start_server("opencode.exe", {}, None)
    assert fake_api["stopped"] == [fake_api["server"]]
    fake_api["server"] = FakeServer()
    fake_api["mcp"] = [{"data": [{"name": "mage", "status": {"status": "failed"}}]}]
    monkeypatch.setattr(opencode_client, "SERVER_START_SECONDS", 0)
    with pytest.raises(RuntimeError, match="did not connect to the game tools"):
        opencode_client.start_server("opencode.exe", {}, None)
    assert fake_api["stopped"][-1] is fake_api["server"]


def test_opencode2_server_without_a_session_is_stopped(fake_api):
    fake_api["session"] = None
    with pytest.raises(RuntimeError, match="did not open a game session"):
        opencode_client.start_server("opencode.exe", {}, None)
    assert fake_api["stopped"] == [fake_api["server"]]


def test_opencode2_launch_attaches_the_run_to_its_server(opencode2, monkeypatch):
    server = FakeServer()
    started = {}
    monkeypatch.setattr(
        opencode_client,
        "start_server",
        lambda _exe, env, _stderr, wait_for_tools: (
            started.update(wait=wait_for_tools, env=env) or (server, "http://127.0.0.1:5", "ses_test")
        ),
    )
    tied = []
    monkeypatch.setattr(opencode_client, "stop_after", lambda run, srv: tied.append((run, srv)))
    written = []
    launched = {}

    def popen(cmd, **kwargs):
        launched.update(cmd=cmd, **kwargs)
        return SimpleNamespace(stdin=SimpleNamespace(write=written.append, close=lambda: None))

    monkeypatch.setattr(opencode_client.subprocess, "Popen", popen)
    proc = opencode_client.launch("play", None, None, {"mcp": {}}, [str(opencode2), "run", "--format", "json"])
    assert launched["cmd"] == [
        str(opencode2),
        "run",
        "--server",
        "http://127.0.0.1:5",
        "--session",
        "ses_test",
        "--format",
        "json",
    ]
    assert launched["env"] is started["env"]
    assert started["wait"] is True
    assert written == [b"play"]
    # The server is stopped once the run ends.
    assert tied == [(proc, server)]


def test_opencode2_server_is_stopped_when_the_run_cannot_start(opencode2, monkeypatch):
    server = FakeServer()
    monkeypatch.setattr(opencode_client, "start_server", lambda *_args, **_kwargs: (server, "http://x", "ses_test"))
    stopped = []
    monkeypatch.setattr(opencode_client, "stop_process", stopped.append)

    def popen(cmd, **kwargs):
        raise OSError("cannot start")

    monkeypatch.setattr(opencode_client.subprocess, "Popen", popen)
    with pytest.raises(OSError, match="cannot start"):
        opencode_client.launch("play", None, None, {}, [str(opencode2), "run"])
    assert stopped == [server]


def test_the_server_is_stopped_after_the_run_ends(monkeypatch):
    stopped = []
    monkeypatch.setattr(opencode_client, "stop_process", stopped.append)
    server = FakeServer()
    opencode_client.stop_after(SimpleNamespace(wait=lambda: 0), server)
    for thread in [t for t in __import__("threading").enumerate() if t.name == "opencode-server"]:
        thread.join(timeout=5)
    assert stopped == [server]


# OpenCode 2.2.5 error events for a model server's answers, recorded on 2026-11-06 (messages made up).
def opencode2_error(kind, status, message="Fake message"):
    return [
        {"type": "error", "error": {"type": kind, "message": message, "status": status, "response": {"body": "{}"}}}
    ]


def test_opencode2_errors_give_the_message_and_status():
    events = opencode2_error("provider.quota", 402, "Insufficient balance")
    assert opencode_client.error_message(events) == "Insufficient balance"
    assert opencode_client.error_data(events)["statusCode"] == 402
    for kind, status in (
        ("provider.auth", 401),
        ("provider.quota", 402),
        ("provider.auth", 403),
        ("provider.invalid-request", 404),
    ):
        assert opencode_client.lasting_error(opencode2_error(kind, status))
    # OpenCode 2 retried these about ten times before it gave up; another try later may pass.
    assert not opencode_client.lasting_error(opencode2_error("provider.rate-limit", 429))
    assert not opencode_client.lasting_error(opencode2_error("provider.internal", 500))
    assert opencode_client.error_message([{"type": "error", "error": {}}]) == "OpenCode reported an error"


def exported(*credentials, returncode=0):
    return SimpleNamespace(returncode=returncode, stdout=json.dumps(list(credentials)))


def credential(key=FAKE_KEY, *, kind="key", active=True):
    return {"id": "cred_test", "integrationID": "openrouter", "active": active, "value": {"type": kind, "key": key}}


def test_opencode2_key_comes_from_its_own_store_of_this_home(opencode2, monkeypatch):
    calls = []
    monkeypatch.setattr(
        opencode_client, "run_cli", lambda args, **_kwargs: calls.append(args) or exported(credential())
    )
    # auth.json is not read with OpenCode 2, which keeps keys in its own store (imported from auth.json once).
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"openrouter": {"type": "api", "key": "old-key-not-a-secret"}}))
    assert opencode_client.read_key("openrouter", "OpenRouter", "openrouter") == FAKE_KEY
    # Always a private server of the isolated home, never the background service with your own sign-ins.
    assert calls == [["auth", "export", "openrouter", "--standalone"]]


@pytest.mark.parametrize(
    "answer",
    [
        exported(),
        exported(credential(), returncode=1),
        exported(credential(active=False)),
        exported(credential(kind="oauth")),
        SimpleNamespace(returncode=0, stdout="not json"),
    ],
)
def test_opencode2_without_a_stored_key_asks_to_sign_in(opencode2, monkeypatch, answer):
    monkeypatch.setattr(opencode_client, "run_cli", lambda _args, **_kwargs: answer)
    with pytest.raises(opencode_client.MissingKeyError, match=r"llm\.sign_in openrouter"):
        opencode_client.read_key("openrouter", "OpenRouter", "openrouter")


def test_opencode2_sign_in_check_reads_only_the_list_of_names(opencode2, monkeypatch):
    # The shape of opencode auth list --format json (2.2.5); it holds no key.
    listed = [
        {
            "id": "opencode-go",
            "name": "OpenCode Go",
            "connections": [{"type": "credential", "id": "cred_test", "label": "OpenCode Go", "method": "key"}],
        }
    ]
    calls = []
    monkeypatch.setattr(
        opencode_client,
        "run_cli",
        lambda args, **_kwargs: calls.append(args) or SimpleNamespace(returncode=0, stdout=json.dumps(listed)),
    )
    assert opencode_client.has_credential("opencode-go")
    assert not opencode_client.has_credential("opencode")
    assert calls[0] == ["auth", "list", "--standalone", "--format", "json"]


def test_opencode2_model_list_gives_each_models_variants(opencode2, monkeypatch):
    # The shape of GET /api/model (2.2.5), trimmed to what is read.
    listing = {
        "location": {"directory": "x"},
        "data": [
            {
                "id": "deepseek-v4.1-flash",
                "providerID": "opencode",
                "variants": [{"id": "low", "settings": {"reasoningEffort": "low"}}, {"id": "high", "settings": {}}],
            },
            {"id": "big-pickle", "providerID": "opencode", "variants": []},
            {"id": "glm-5.3", "providerID": "zai-coding-plan", "variants": [{"id": "max", "settings": {}}]},
        ],
    }
    calls = []
    monkeypatch.setattr(
        opencode_client,
        "run_cli",
        lambda args, **_kwargs: calls.append(args) or SimpleNamespace(returncode=0, stdout=json.dumps(listing)),
    )
    assert opencode_client.read_efforts("opencode") == {"deepseek-v4.1-flash": ["low", "high"], "big-pickle": []}
    assert calls == [["api", "--standalone", "GET", "/api/model"]]
    assert opencode_client.parse_model_list("not json", "opencode") == {}
