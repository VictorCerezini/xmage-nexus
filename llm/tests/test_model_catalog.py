"""Account-safe discovery and model-specific reasoning validation; no inference."""

import json

import pytest

from llm import model_catalog, paths
from llm.model_catalog import read_catalog, validate_selection


def model(name, efforts, *, hidden=False):
    return {
        "model": name,
        "displayName": name,
        "hidden": hidden,
        "supportedReasoningEfforts": [{"reasoningEffort": effort} for effort in efforts],
        "defaultReasoningEffort": efforts[0],
        "isDefault": True,
    }


def test_catalog_paginates_and_excludes_identity_hidden_and_unwanted_models():
    calls = []

    def call(method, params):
        calls.append((method, params))
        if method == "account/read":
            return {"account": {"type": "chatgpt", "email": "private@example.invalid", "planType": "private-plan"}}
        if params["cursor"] is None:
            return {
                "data": [model("gpt-6-astra", ["low", "high"]), model("gpt-5.6-sol", ["low"])],
                "nextCursor": "second-page",
            }
        return {
            "data": [
                model("gpt-6-luna", ["medium"]),
                model("gpt-6.1-sol", ["high"]),
                model("gpt-6.1-terra", ["low", "ultra"]),
                model("gpt-5.6-terra", ["medium"]),
                model("gpt-6-sol", ["low"]),
                model("gpt-5.6-astra", ["high"]),
                model("gpt-6-astra", ["low"], hidden=True),
            ],
            "nextCursor": None,
        }

    catalog = read_catalog(call)
    assert [item["id"] for item in catalog["models"]] == ["gpt-6-astra", "gpt-6-luna", "gpt-6.1-sol", "gpt-6.1-terra"]
    assert catalog["models"][0]["efforts"] == ["low", "high"]
    assert "private" not in json.dumps(catalog)
    assert [method for method, _ in calls] == ["account/read", "model/list", "model/list"]


@pytest.mark.parametrize("account", [None, {"type": "apiKey"}, {"type": "amazonBedrock"}])
def test_catalog_rejects_other_authentication_modes(account):
    with pytest.raises(RuntimeError, match="Sign into Codex with ChatGPT"):
        read_catalog(lambda *_: {"account": account})


@pytest.mark.parametrize(
    "selection",
    [
        ("missing", "low"),
        ("gpt-6-astra", "ultra"),
        ("gpt-6-luna", "medium"),
        ("gpt-5.6-sol", "high"),
        ("gpt-6-sol", "high"),
    ],
)
def test_unavailable_model_or_effort_is_rejected(selection):
    with pytest.raises(ValueError, match=r"not offered|not in the available|does not support"):
        # Even a stale catalog cannot start one of the removed models.
        validate_selection(
            {
                "models": [
                    {"id": name, "efforts": ["low", "high"]} for name in ("gpt-6-astra", "gpt-5.6-sol", "gpt-6-sol")
                ]
            },
            *selection,
        )


def test_valid_model_specific_effort_is_accepted():
    validate_selection({"models": [{"id": "gpt-6-astra", "efforts": ["low", "high"]}]}, "gpt-6-astra", "high")


def test_empty_requested_model_intersection_reports_missing_access():
    def call(method, _params):
        if method == "account/read":
            return {"account": {"type": "chatgpt"}}
        return {"data": [model("gpt-5.6-sol", ["high"])], "nextCursor": None}

    with pytest.raises(RuntimeError, match="No GPT-6 Astra"):
        read_catalog(call)


def test_catalog_without_codex_explains_the_setup(monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"codex_executable": None})
    with pytest.raises(RuntimeError, match=r"llm\.configure with Codex installed"), model_catalog.CatalogClient():
        pass
