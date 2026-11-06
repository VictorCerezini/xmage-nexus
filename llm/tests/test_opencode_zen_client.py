"""OpenCode Zen discovery and play setup with fake settings and no model invocation."""

import dataclasses
import json
from pathlib import Path

import pytest

from llm import opencode_client, opencode_zen_client, paths, providers, sign_in

FAKE_KEY = "test-key-not-a-secret"


def model(model_id, name, *, tools=True, kind="chat"):
    # The shape of https://opencode.ai/inference/v1/models on 2026-11-06, trimmed to what is read.
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
        model("deepseek-v4-pro", "DeepSeek V4 Pro"),
        model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash"),
        model("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash Free"),
        model("brand-new", "Brand New"),
        model("no-tools", "No Tools", tools=False),
    ],
}
# What opencode models opencode --verbose listed with a key on 2026-11-06 (OpenCode 1.19.3), trimmed.
EFFORTS = {
    "deepseek-v4-pro": ["high", "max"],
    "deepseek-v4.1-flash": ["low", "high", "max"],
    "deepseek-v4.1-flash-free": ["low", "high", "max"],
    "no-tools": [],
    "gemini-3-flash": [],
}


@pytest.fixture
def fake_local(tmp_path, monkeypatch):
    executable = tmp_path / "opencode.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(executable)})
    monkeypatch.setattr(opencode_client, "OPENCODE_HOME", tmp_path / "opencode-home")
    monkeypatch.setattr(opencode_client, "AUTH_PATH", tmp_path / "opencode-home/data/opencode/auth.json")
    return executable


def store(auth):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps(auth))


def test_catalog_offers_zen_models_this_opencode_can_play_with_deepseek_flash_first():
    catalog = opencode_zen_client.read_catalog(LISTING, EFFORTS)
    # A model OpenCode does not know yet and one without tools are left out, and a model OpenCode still
    # knows but Zen no longer lists (gemini-3-flash) is not offered.
    assert [item["id"] for item in catalog["models"]] == [
        "deepseek-v4-pro",
        "deepseek-v4.1-flash",
        "deepseek-v4.1-flash-free",
    ]
    assert [item["isDefault"] for item in catalog["models"]] == [False, True, False]
    assert catalog["models"][1]["efforts"] == ["default", "low", "high", "max"]
    assert catalog["models"][1]["defaultEffort"] == "default"
    assert catalog["id"] == "opencode_zen"
    # The seat is named "OpenCode Zen 2": the proxy allows at most 14 characters.
    assert catalog["playerName"] == "OpenCode Zen"
    assert len(catalog["playerName"] + " 9") <= 14
    assert "credits" in catalog["auth"]


def test_the_first_model_is_preselected_without_deepseek_flash():
    catalog = opencode_zen_client.read_catalog({"data": [model("deepseek-v4-pro", "DeepSeek V4 Pro")]}, EFFORTS)
    assert [(item["id"], item["isDefault"]) for item in catalog["models"]] == [("deepseek-v4-pro", True)]
    with pytest.raises(RuntimeError, match=r"OpenCode Zen lists no model .* update OpenCode"):
        opencode_zen_client.read_catalog({"data": [model("brand-new", "Brand New")]}, EFFORTS)


def test_discovery_sends_no_key_and_names_itself(fake_local, monkeypatch):
    store({"opencode": {"type": "api", "key": FAKE_KEY}})
    calls = []
    monkeypatch.setattr(
        opencode_client,
        "get_json",
        lambda url, key=None, user_agent=None: calls.append((url, key, user_agent)) or LISTING,
    )
    monkeypatch.setattr(opencode_client, "read_efforts", lambda provider: EFFORTS if provider == "opencode" else {})
    catalog = opencode_zen_client.discover()
    # Zen's model list is public: the key stays with OpenCode.
    assert calls == [("https://opencode.ai/inference/v1/models", None, "xmage-nexus-model-seats/1.0")]
    assert FAKE_KEY not in json.dumps(catalog)
    assert [item["id"] for item in catalog["models"]][1] == "deepseek-v4.1-flash"


def test_a_go_key_is_not_a_zen_sign_in(fake_local):
    # Each service keeps its own key; signing into Go does not spend Zen credits, or the other way round.
    store({"opencode-go": {"type": "api", "key": FAKE_KEY}})
    with pytest.raises(opencode_client.MissingKeyError, match=r"No OpenCode Zen key .*llm\.sign_in opencode-zen"):
        opencode_zen_client.discover()


def test_without_a_key_the_list_names_zen_and_the_command_to_copy(fake_local, monkeypatch):
    data_home = Path("D:/nexus-data/llm")
    monkeypatch.setattr(paths, "DATA_HOME", data_home)
    claude = providers.get("claude_subscription")
    working = dataclasses.replace(claude, discover=lambda: {"id": claude.id, "models": []})
    catalog = providers.discover_all({claude.id: working, "opencode_zen": providers.get("opencode_zen")})
    (entry,) = catalog["unavailable"]
    assert entry["playerName"] == "OpenCode Zen"
    assert entry["reason"] == "No OpenCode Zen key is saved for this app yet."
    assert entry["signIn"].endswith("-m llm.sign_in opencode-zen")
    assert entry["signInShell"] == "PowerShell"


def test_start_uses_the_zen_provider_without_extra_model_entries(fake_local, monkeypatch):
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(config=args[3], cmd=args[4]))
    opencode_zen_client.start(
        "play", None, None, "http://127.0.0.1:1/mcp", model="deepseek-v4.1-flash", reasoning="low"
    )
    assert launched["config"]["enabled_providers"] == ["opencode"]
    assert "provider" not in launched["config"]
    assert launched["config"]["permission"] == opencode_client.PERMISSIONS
    assert launched["config"]["tools"] == opencode_client.TOOL_SWITCHES
    cmd = launched["cmd"]
    assert cmd[cmd.index("--model") + 1] == "opencode/deepseek-v4.1-flash"
    assert cmd[cmd.index("--variant") + 1] == "low"


def test_default_effort_passes_no_variant(fake_local, monkeypatch):
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(cmd=args[4]))
    opencode_zen_client.start("play", None, None, None)
    assert launched["cmd"][launched["cmd"].index("--model") + 1] == "opencode/deepseek-v4.1-flash"
    assert "--variant" not in launched["cmd"]


@pytest.mark.parametrize(
    "selection", [("brand-new", "default"), ("gemini-3-flash", "default"), ("deepseek-v4-pro", "low")]
)
def test_unoffered_models_and_unsupported_efforts_are_rejected(selection):
    catalog = opencode_zen_client.read_catalog(LISTING, EFFORTS)
    with pytest.raises(ValueError, match=r"not in the OpenCode Zen catalog|does not support"):
        opencode_zen_client.validate_selection(catalog, *selection)
    opencode_zen_client.validate_selection(catalog, "deepseek-v4.1-flash", "max")


def error(message, status=None):
    data = {"message": message}
    if status is not None:
        data.update(statusCode=status, isRetryable=False)
    return [{"type": "error", "error": {"data": data}}]


def test_failure_reason_points_at_the_credits_and_the_sign_in():
    broke = opencode_zen_client.failure_reason(error("Insufficient balance. Please add credits.", 402))
    assert broke.startswith("Insufficient balance")
    assert "add credits at opencode.ai/console" in broke
    # A used-up balance does not come back within the game: the seat stops or hands over to its backup.
    assert opencode_client.lasting_error(error("Insufficient balance. Please add credits.", 402))
    assert providers.lasting_limit(broke)
    refused = opencode_zen_client.failure_reason(error("Invalid API key", 401))
    assert "llm.sign_in opencode-zen" in refused
    assert opencode_zen_client.failure_reason(error("Bad gateway", 502)) == "Bad gateway"
    assert opencode_zen_client.failure_reason([]) is None


def test_zen_is_a_provider_with_its_own_trace_files_and_sign_in():
    provider = providers.get("opencode_zen")
    assert provider.discover is opencode_zen_client.discover
    assert provider.start is opencode_zen_client.start
    assert provider.label == "OpenCode Zen"
    trace_files = [item.events_file for item in providers.PROVIDERS.values()]
    assert len(trace_files) == len(set(trace_files))
    assert sign_in.PROVIDERS["opencode-zen"] == ("opencode", "OpenCode Zen", "https://opencode.ai/console")


def test_opencode2_reasoning_follows_the_model(fake_local, monkeypatch):
    monkeypatch.setattr(opencode_client, "major_version", lambda _exe: 2)
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(cmd=args[4]))
    opencode_zen_client.start("play", None, None, None, model="deepseek-v4.1-flash", reasoning="low")
    assert launched["cmd"][launched["cmd"].index("--model") + 1] == "opencode/deepseek-v4.1-flash#low"
    assert "--variant" not in launched["cmd"]
    assert "--pure" not in launched["cmd"]


def test_opencode2_errors_point_at_the_credits_and_the_sign_in():
    def opencode2_error(kind, status, message):
        return [{"type": "error", "error": {"type": kind, "message": message, "status": status}}]

    broke = opencode_zen_client.failure_reason(opencode2_error("provider.quota", 402, "Insufficient balance"))
    assert "add credits at opencode.ai/console" in broke
    assert opencode_client.lasting_error(opencode2_error("provider.quota", 402, "Insufficient balance"))
    refused = opencode_zen_client.failure_reason(opencode2_error("provider.auth", 401, "Invalid API key"))
    assert "llm.sign_in opencode-zen" in refused
