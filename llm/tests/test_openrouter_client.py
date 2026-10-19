"""OpenRouter free-model discovery and play setup with fake settings and no model invocation."""

import json

import pytest

from llm import opencode_client, openrouter_client, paths

FAKE_KEY = "test-key-not-a-secret"
MODEL = {
    "id": "google/gemma-4-31b-it:free",
    "name": "Google: Gemma 4 31B (free)",
    "efforts": ["default", "low", "medium", "high"],
    "defaultEffort": "default",
    "isDefault": True,
    "contextLength": 262144,
    "maxOutput": 32768,
}


@pytest.fixture
def fake_local(tmp_path, monkeypatch):
    executable = tmp_path / "opencode.exe"
    executable.write_text("not a real executable")
    monkeypatch.setattr(paths, "settings", lambda: {"opencode_executable": str(executable)})
    monkeypatch.setattr(opencode_client, "OPENCODE_HOME", tmp_path / "opencode-home")
    monkeypatch.setattr(opencode_client, "AUTH_PATH", tmp_path / "opencode-home/data/opencode/auth.json")
    monkeypatch.setattr(openrouter_client, "MODELS_PATH", tmp_path / "openrouter-models.json")
    return executable


def listing(*items):
    return {"data": list(items)}


def model_listing(model_id, name, *, price="0", parameters=("tools", "reasoning"), output=("text",)):
    return {
        "id": model_id,
        "name": name,
        "pricing": {"prompt": price, "completion": price},
        "supported_parameters": list(parameters),
        "architecture": {"output_modalities": list(output)},
        "context_length": 131072,
        "top_provider": {"context_length": 131072, "max_completion_tokens": 8192},
    }


def test_config_registers_the_selected_model_with_its_limits():
    config = openrouter_client.config("http://127.0.0.1:12345/mcp", MODEL)
    assert config["enabled_providers"] == ["openrouter"]
    assert config["provider"]["openrouter"]["models"][MODEL["id"]]["limit"] == {"context": 262144, "output": 32768}
    assert config["permission"] == opencode_client.PERMISSIONS


def test_start_uses_the_launch_catalog_and_the_openrouter_model(fake_local, monkeypatch):
    openrouter_client.MODELS_PATH.write_text(json.dumps({MODEL["id"]: MODEL}))
    launched = {}
    monkeypatch.setattr(opencode_client, "launch", lambda *args: launched.update(config=args[3], cmd=args[4]))
    openrouter_client.start("play", None, None, "http://127.0.0.1:1/mcp", model=MODEL["id"], reasoning="low")
    assert launched["config"]["mcp"]["mage"]["url"] == "http://127.0.0.1:1/mcp"
    assert launched["cmd"][launched["cmd"].index("--model") + 1] == "openrouter/google/gemma-4-31b-it:free"


def test_model_missing_from_launch_catalog_is_rejected(fake_local):
    with pytest.raises(ValueError, match="refresh the model list"):
        openrouter_client.start("play", None, None, model="vanished/model:free")


def test_failure_reason_adds_rate_limit_and_daily_limit_hints():
    def error(message):
        return [{"type": "error", "error": {"data": {"message": message}}}]

    busy = "[Google AI Studio] google/gemma-4-31b-it:free is temporarily rate-limited upstream."
    reason = openrouter_client.failure_reason(error(busy))
    assert reason.startswith(busy)
    assert "choose another free model" in reason
    daily = "Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day"
    assert openrouter_client.failure_reason(error(daily)) == (
        daily + ". The daily free-model request limit is used up; it resets at midnight UTC."
    )
    assert openrouter_client.failure_reason(error("Model not found")) == "Model not found"
    assert openrouter_client.failure_reason([]) is None


def test_catalog_lists_only_free_text_models_with_tools():
    catalog = openrouter_client.read_catalog(
        listing(
            model_listing("z/zeta:free", "Zeta (free)"),
            model_listing("a/alpha:free", "Alpha (free)"),
            model_listing("p/paid", "Paid", price="0.000001"),
            model_listing("n/no-tools:free", "No tools (free)", parameters=("reasoning",)),
            model_listing("m/music", "Music", output=("audio",)),
            model_listing("openrouter/free", "Free Models Router"),
        ),
        {"a/alpha:free": ["low", "high"]},
    )
    assert [item["id"] for item in catalog["models"]] == ["a/alpha:free", "z/zeta:free"]
    alpha, zeta = catalog["models"]
    assert alpha["efforts"] == ["default", "low", "high"]
    # A model OpenCode does not know yet still plays, at the provider's default effort.
    assert zeta["efforts"] == ["default"]
    assert (alpha["isDefault"], zeta["isDefault"]) == (True, False)
    assert (alpha["contextLength"], alpha["maxOutput"]) == (131072, 8192)
    assert catalog["id"] == "openrouter_free"


def test_no_free_model_is_an_error():
    with pytest.raises(RuntimeError, match="no free models"):
        openrouter_client.read_catalog(listing(model_listing("p/paid", "Paid", price="1")), {})


def test_discovery_never_copies_the_key_or_account_details(fake_local, monkeypatch):
    opencode_client.AUTH_PATH.parent.mkdir(parents=True)
    opencode_client.AUTH_PATH.write_text(json.dumps({"openrouter": {"type": "api", "key": FAKE_KEY}}))
    calls = []

    def get_json(url, key=None):
        calls.append((url, key))
        if url == openrouter_client.KEY_URL:
            return {
                "data": {
                    "label": "private-label",
                    "limit_remaining": 9,
                    "free_model_daily_requests": {"used": 3, "limit": 50, "remaining": 47},
                }
            }
        return listing(model_listing("a/alpha:free", "Alpha (free)"))

    monkeypatch.setattr(opencode_client, "get_json", get_json)
    monkeypatch.setattr(opencode_client, "read_efforts", lambda _provider: {})
    catalog = openrouter_client.discover()
    assert catalog["freeRequestsToday"] == {"used": 3, "limit": 50, "remaining": 47}
    saved = json.dumps(catalog) + openrouter_client.MODELS_PATH.read_text()
    assert FAKE_KEY not in saved
    assert "private-label" not in saved
    # The key goes only to the account endpoint; the public model list is fetched without it.
    assert calls == [(openrouter_client.MODELS_URL, None), (openrouter_client.KEY_URL, FAKE_KEY)]


@pytest.mark.parametrize("selection", [("vanished/model:free", "default"), (MODEL["id"], "xhigh")])
def test_unavailable_model_or_effort_is_rejected(selection):
    with pytest.raises(ValueError, match=r"not in the OpenRouter|does not support"):
        openrouter_client.validate_selection({"models": [MODEL]}, *selection)
