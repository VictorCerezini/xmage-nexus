"""OpenCode Go discovery and play setup with fake settings and no model invocation."""

import dataclasses
import json
from pathlib import Path

import pytest

from llm import opencode_client, opencode_go_client, paths, providers, sign_in

FAKE_KEY = "test-key-not-a-secret"


def model(model_id, name, *, tools=True, kind="chat"):
    # The shape of https://opencode.ai/inference/go/v1/models on 2026-11-05, trimmed to what is read.
    return {
        "id": model_id,
        "object": "model",
        "name": name,
        "type": kind,
        "capabilities": {"tools": {"supported": tools}},
    }


LISTING = {
    "object": "list",
    "data": [
        model("kimi-k3", "Kimi K3"),
        model("glm-5.3", "GLM-5.3"),
        model("beam-free", "Beam Free"),
        model("new-model", "New Model"),
        model("no-tools", "No Tools", tools=False),
        model("embedder", "Embedder", kind="embedding"),
    ],
}
# What opencode models opencode-go --verbose lists: Go's models this OpenCode knows, with their variants.
EFFORTS = {
    "kimi-k3": ["max"],
    "glm-5.3": ["low", "high", "max"],
    "beam-free": [],
    "no-tools": [],
    "embedder": [],
    "mimo-v2.5": [],
}


@pytest.fixture
def fake_local(tmp_path, monkeypatch):
    executable = tmp_path / "opencode.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(executable)})
    monkeypatch.setattr(opencode_client, "OPENCODE_HOME", tmp_path / "opencode-home")
    monkeypatch.setattr(opencode_client, "AUTH_PATH", tmp_path / "opencode-home/data/opencode/auth.json")
    return executable


def test_catalog_offers_go_models_this_opencode_can_play():
    catalog = opencode_go_client.read_catalog(LISTING, EFFORTS)
    # A model OpenCode does not know yet, one without tools and a non-chat model are left out, and a
    # model OpenCode still knows but Go no longer serves (mimo-v2.5) is not offered.
    assert [item["id"] for item in catalog["models"]] == ["beam-free", "glm-5.3", "kimi-k3"]
    assert [item["isDefault"] for item in catalog["models"]] == [False, True, False]
    glm = catalog["models"][1]
    assert glm["efforts"] == ["default", "low", "high", "max"]
    assert glm["defaultEffort"] == "default"
    assert catalog["models"][0]["efforts"] == ["default"]
    assert catalog["id"] == "opencode_go"
    assert catalog["playerName"] == "OpenCode Go"


def test_the_first_model_is_preselected_without_glm():
    catalog = opencode_go_client.read_catalog(
        {"data": [model("kimi-k3", "Kimi K3"), model("beam-free", "Beam Free")]}, EFFORTS
    )
    assert [(item["id"], item["isDefault"]) for item in catalog["models"]] == [("beam-free", True), ("kimi-k3", False)]
    with pytest.raises(RuntimeError, match="update OpenCode"):
        opencode_go_client.read_catalog({"data": [model("new-model", "New Model")]}, EFFORTS)


def test_discovery_sends_no_key_and_names_itself(fake_local, monkeypatch):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"opencode-go": {"type": "api", "key": FAKE_KEY}}))
    calls = []
    monkeypatch.setattr(
        opencode_client,
        "get_json",
        lambda url, key=None, user_agent=None: calls.append((url, key, user_agent)) or LISTING,
    )
    monkeypatch.setattr(opencode_client, "read_efforts", lambda provider: EFFORTS if provider == "opencode-go" else {})
    catalog = opencode_go_client.discover()
    # Go's model list is public: the key stays with OpenCode, and Go refuses Python's default user agent.
    assert calls == [("https://opencode.ai/inference/go/v1/models", None, "xmage-nexus-model-seats/1.0")]
    assert FAKE_KEY not in json.dumps(catalog)
    assert [item["id"] for item in catalog["models"]] == ["beam-free", "glm-5.3", "kimi-k3"]


def test_get_json_sends_the_user_agent_it_is_given(monkeypatch):
    sent = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self):
            return b'{"data": []}'

    def urlopen(request, timeout):
        sent.update(request.header_items())
        return Response()

    monkeypatch.setattr(opencode_client.urllib.request, "urlopen", urlopen)
    assert opencode_client.get_json("https://example.test/models", user_agent="xmage-nexus-model-seats/1.0") == {
        "data": []
    }
    assert sent["User-agent"] == "xmage-nexus-model-seats/1.0"
    assert "Authorization" not in sent


def test_missing_sign_in_names_the_opencode_go_script(fake_local):
    with pytest.raises(RuntimeError, match=r"llm\.sign_in opencode-go"):
        opencode_go_client.discover()


def test_a_key_of_another_provider_is_not_a_go_sign_in(fake_local):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"opencode": {"type": "api", "key": FAKE_KEY}}))
    with pytest.raises(RuntimeError, match=r"llm\.sign_in opencode-go"):
        opencode_go_client.discover()


def test_start_uses_the_go_provider_without_extra_model_entries(fake_local, monkeypatch):
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(config=args[3], cmd=args[4]))
    opencode_go_client.start("play", None, None, "http://127.0.0.1:1/mcp", model="kimi-k3", reasoning="max")
    assert launched["config"]["enabled_providers"] == ["opencode-go"]
    assert "provider" not in launched["config"]
    assert launched["config"]["permission"] == opencode_client.PERMISSIONS
    assert launched["config"]["tools"] == opencode_client.TOOL_SWITCHES
    cmd = launched["cmd"]
    assert cmd[cmd.index("--model") + 1] == "opencode-go/kimi-k3"
    assert cmd[cmd.index("--variant") + 1] == "max"


def test_default_effort_passes_no_variant(fake_local, monkeypatch):
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(cmd=args[4]))
    opencode_go_client.start("play", None, None, None, model="glm-5.3")
    assert "--variant" not in launched["cmd"]


@pytest.mark.parametrize("selection", [("new-model", "default"), ("mimo-v2.5", "default"), ("kimi-k3", "low")])
def test_unoffered_models_and_unsupported_efforts_are_rejected(selection):
    catalog = opencode_go_client.read_catalog(LISTING, EFFORTS)
    with pytest.raises(ValueError, match=r"not in the OpenCode Go catalog|does not support"):
        opencode_go_client.validate_selection(catalog, *selection)
    opencode_go_client.validate_selection(catalog, "glm-5.3", "high")


def error(message, status=None):
    data = {"message": message}
    if status is not None:
        data.update(statusCode=status, isRetryable=False)
    return [{"type": "error", "error": {"data": data}}]


def test_failure_reason_points_at_the_allowance_and_the_sign_in():
    limit = "Usage limit reached for this model. Try again later."
    reason = opencode_go_client.failure_reason(error(limit, 429))
    assert reason.startswith("Usage limit reached for this model")
    assert "5-hour, weekly or monthly" in reason
    # What Go answered to a made-up key on 2026-11-05, through OpenCode 1.19.3.
    refused = opencode_go_client.failure_reason(error("Upstream request failed: Invalid credential", 401))
    assert refused.startswith("Upstream request failed: Invalid credential. ")
    assert "llm.sign_in opencode-go" in refused
    assert opencode_client.lasting_error(error("Upstream request failed: Invalid credential", 401))
    assert opencode_go_client.failure_reason(error("Bad gateway", 502)) == "Bad gateway"
    assert opencode_go_client.failure_reason([]) is None


# What Go answered on 2026-11-06 to a workspace that had not allowed Global regions (workspace id left out).
REGION = (
    "Upstream request failed: This Go model is hosted outside Europe and the United States. "
    "Select Global at https://opencode.ai/console/wrk_TEST/settings/privacy#regions to use it."
)


def test_a_region_refusal_stops_the_seat_and_says_where_to_allow_it():
    events = error(REGION, 400)
    # Asking again gets the same answer, so the seat stops (or hands over to its backup) instead of retrying.
    assert opencode_go_client.lasting_failure(events)
    assert providers.get("opencode_go").lasting_failure(events)
    assert not opencode_client.lasting_error(events)
    reason = opencode_go_client.failure_reason(events)
    assert reason.startswith("Upstream request failed: This Go model is hosted outside Europe")
    assert "Settings, Privacy, Regions" in reason
    # A busy model is still retried.
    assert not opencode_go_client.lasting_failure(error("Bad gateway", 502))


def test_go_is_a_provider_with_its_own_trace_files_and_sign_in():
    provider = providers.get("opencode_go")
    assert provider.discover is opencode_go_client.discover
    assert provider.start is opencode_go_client.start
    assert provider.require_game_only_events is opencode_client.require_game_only_events
    trace_files = [item.events_file for item in providers.PROVIDERS.values()]
    assert len(trace_files) == len(set(trace_files))
    assert sign_in.PROVIDERS["opencode-go"] == ("opencode-go", "OpenCode Go", "https://opencode.ai/console")


def test_inherited_opencode_settings_and_keys_are_not_passed_to_the_player(fake_local, monkeypatch):
    monkeypatch.setenv("OPENCODE_API_KEY", FAKE_KEY)
    monkeypatch.setenv("OPENCODE_CONFIG", "C:/somewhere/opencode.json")
    env = opencode_client.clean_env()
    assert "OPENCODE_API_KEY" not in env
    assert "OPENCODE_CONFIG" not in env
    assert FAKE_KEY not in json.dumps(env)


def test_without_a_key_the_list_names_go_and_the_command_to_copy(fake_local, monkeypatch):
    data_home = Path("D:/nexus-data/llm")
    monkeypatch.setattr(paths, "DATA_HOME", data_home)
    claude = providers.get("claude_subscription")
    working = dataclasses.replace(claude, discover=lambda: {"id": claude.id, "models": []})
    catalog = providers.discover_all({claude.id: working, "opencode_go": providers.get("opencode_go")})
    (entry,) = catalog["unavailable"]
    # The app shows the name on a greyed-out button, and the command on its own line, to copy into PowerShell.
    assert entry == {
        "id": "opencode_go",
        "playerName": "OpenCode Go",
        "reason": "No OpenCode Go key is saved for this app yet.",
        "signIn": entry["signIn"],
        "signInShell": "PowerShell",
    }
    assert entry["signIn"].endswith("-m llm.sign_in opencode-go")
    assert f"$env:NEXUS_LLM_HOME = '{data_home}'" in entry["signIn"]
    assert "(in PowerShell)" not in entry["signIn"]


def test_other_reasons_keep_their_words_and_a_name(fake_local):
    def missing():
        raise RuntimeError("Claude Code is not installed: install it, then refresh the model list")

    claude = providers.get("claude_subscription")
    go = providers.get("opencode_go")
    working = dataclasses.replace(go, discover=lambda: {"id": go.id, "models": []})
    catalog = providers.discover_all({claude.id: dataclasses.replace(claude, discover=missing), go.id: working})
    assert catalog["unavailable"] == [
        {
            "id": "claude_subscription",
            "playerName": "Claude",
            "reason": "Claude Code is not installed: install it, then refresh the model list",
        }
    ]
    # With nothing playable, the one message still says how to sign in.
    with pytest.raises(
        RuntimeError, match=r"No OpenCode Go key is saved for this app yet\. Sign in with: .*opencode-go"
    ):
        providers.discover_all({go.id: go})


def test_opencode2_region_refusal_also_stops_the_seat():
    events = [{"type": "error", "error": {"type": "provider.invalid-request", "message": REGION, "status": 400}}]
    assert opencode_go_client.lasting_failure(events)
    assert "Settings, Privacy, Regions" in opencode_go_client.failure_reason(events)
    refused = [{"type": "error", "error": {"type": "provider.auth", "message": "Invalid credential", "status": 401}}]
    assert "llm.sign_in opencode-go" in opencode_go_client.failure_reason(refused)
