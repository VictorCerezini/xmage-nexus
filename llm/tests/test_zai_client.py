"""Z.ai GLM Coding Plan discovery and play setup with fake settings and no model invocation."""

import json

import pytest

from llm import opencode_client, paths, zai_client

FAKE_KEY = "test-key-not-a-secret"
ACCOUNT_LISTING = {
    "data": [{"id": name, "object": "model"} for name in ("glm-4.7", "glm-5.2", "glm-5.3", "glm-5.3-flash", "glm-x")]
}
EFFORTS = {"glm-5.3": ["low", "high", "max"], "glm-5.3-flash": ["low", "high", "max"], "glm-5.2": ["high"]}


@pytest.fixture
def fake_local(tmp_path, monkeypatch):
    executable = tmp_path / "opencode.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(executable)})
    monkeypatch.setattr(opencode_client, "OPENCODE_HOME", tmp_path / "opencode-home")
    monkeypatch.setattr(opencode_client, "AUTH_PATH", tmp_path / "opencode-home/data/opencode/auth.json")
    return executable


def test_catalog_offers_only_models_every_plan_documents():
    catalog = zai_client.read_catalog(ACCOUNT_LISTING, EFFORTS)
    # GLM-5.2 and GLM-4.7 are listed but silently routed to other models on the plan.
    assert [item["id"] for item in catalog["models"]] == ["glm-5.3", "glm-5.3-flash"]
    assert catalog["models"][0]["efforts"] == ["default", "low", "high", "max"]
    assert [item["isDefault"] for item in catalog["models"]] == [True, False]
    assert catalog["id"] == "zai_coding_plan"
    assert catalog["playerName"] == "GLM"


def test_documented_model_missing_from_the_account_is_not_offered():
    catalog = zai_client.read_catalog({"data": [{"id": "glm-5.3-flash"}]}, {})
    assert [item["id"] for item in catalog["models"]] == ["glm-5.3-flash"]
    assert catalog["models"][0]["efforts"] == ["default"]
    with pytest.raises(RuntimeError, match=r"neither GLM-5\.3"):
        zai_client.read_catalog({"data": [{"id": "glm-4.7"}]}, {})


def test_discovery_sends_the_key_only_to_the_plan_model_list(fake_local, monkeypatch):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"zai-coding-plan": {"type": "api", "key": FAKE_KEY}}))
    calls = []
    monkeypatch.setattr(opencode_client, "get_json", lambda url, key=None: calls.append((url, key)) or ACCOUNT_LISTING)
    monkeypatch.setattr(
        opencode_client, "read_efforts", lambda provider: EFFORTS if provider == "zai-coding-plan" else {}
    )
    catalog = zai_client.discover()
    assert calls == [("https://api.z.ai/api/coding/paas/v4/models", FAKE_KEY)]
    assert FAKE_KEY not in json.dumps(catalog)


def test_missing_sign_in_names_the_zai_script(fake_local):
    with pytest.raises(RuntimeError, match=r"llm\.sign_in zai"):
        zai_client.discover()


def test_start_uses_the_plan_provider_without_extra_model_entries(fake_local, monkeypatch):
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(config=args[3], cmd=args[4]))
    zai_client.start("play", None, None, "http://127.0.0.1:1/mcp", model="glm-5.3-flash", reasoning="max")
    assert launched["config"]["enabled_providers"] == ["zai-coding-plan"]
    assert "provider" not in launched["config"]
    assert launched["config"]["permission"] == opencode_client.PERMISSIONS
    cmd = launched["cmd"]
    assert cmd[cmd.index("--model") + 1] == "zai-coding-plan/glm-5.3-flash"
    assert cmd[cmd.index("--variant") + 1] == "max"


@pytest.mark.parametrize("selection", [("glm-5.2", "high"), ("glm-4.7", "default"), ("glm-5.3", "xhigh")])
def test_routed_models_and_unsupported_efforts_are_rejected(selection):
    catalog = zai_client.read_catalog(ACCOUNT_LISTING, EFFORTS)
    with pytest.raises(ValueError, match=r"not offered|does not support"):
        zai_client.validate_selection(catalog, *selection)


def test_failure_reason_points_at_the_plan_allowance():
    message = "Usage limit reached for 5 hour. Your limit will reset at 2026-10-18 23:00:00"
    reason = zai_client.failure_reason([{"type": "error", "error": {"data": {"message": message}}}])
    assert reason.startswith(message)
    assert "5-hour or weekly allowance" in reason
    assert zai_client.failure_reason([{"type": "error", "error": {"data": {"message": "Bad gateway"}}}]) == (
        "Bad gateway"
    )
    assert zai_client.failure_reason([]) is None
