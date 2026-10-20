"""OpenRouter free models played through the locked-down OpenCode player (see opencode_client).

The OpenRouter key is only ever sent to openrouter.ai and never copied into catalogs or logs.
"""

import json

from llm import opencode_client, paths

PROVIDER = "openrouter_free"
OPENCODE_PROVIDER = "openrouter"
MODELS_URL = "https://openrouter.ai/api/v1/models"
KEY_URL = "https://openrouter.ai/api/v1/key"
# The free router picks a different model for each request, so one game would mix several players.
EXCLUDED_MODELS = {"openrouter/free"}
DEFAULT_EFFORT = opencode_client.DEFAULT_EFFORT
# Context limits of the free models found when the catalog was last read.
MODELS_PATH = paths.RUN_ROOT / "openrouter-models.json"


def catalog_model(model):
    models = json.loads(MODELS_PATH.read_text(encoding="utf-8")) if MODELS_PATH.is_file() else {}
    if model not in models:
        raise ValueError(f"{model} is not in the OpenRouter free catalog; refresh the model list")
    return models[model]


def config(mcp_url, model, tool_timeout_seconds=180):
    # A newly rotated free model may not be in OpenCode's catalog yet; this registers it either way.
    entry = {
        "name": model["name"],
        "tool_call": True,
        "limit": {"context": model["contextLength"], "output": model["maxOutput"]},
    }
    return opencode_client.config(mcp_url, OPENCODE_PROVIDER, {model["id"]: entry}, tool_timeout_seconds)


def start(prompt, stdout, stderr, mcp_url=None, *, model, reasoning=DEFAULT_EFFORT, tool_timeout_seconds=180):
    game_config = config(mcp_url, catalog_model(model), tool_timeout_seconds)
    cmd = opencode_client.command(OPENCODE_PROVIDER, model, reasoning)
    return opencode_client.launch(prompt, stdout, stderr, game_config, cmd)


def failure_reason(events):
    message = opencode_client.error_message(events)
    if message is None:
        return None
    lowered = message.lower()
    if "per-day" in lowered:
        return opencode_client.with_hint(
            message, "The daily free-model request limit is used up; it resets at midnight UTC."
        )
    if "rate-limit" in lowered or "rate limit" in lowered:
        return opencode_client.with_hint(
            message, "Free models are often busy: choose another free model, or try again later."
        )
    return message


def is_free(item):
    pricing = item.get("pricing", {})
    try:
        if any(float(pricing.get(key) or 0) != 0 for key in ("prompt", "completion", "request")):
            return False
    except ValueError:
        return False
    return (
        "tools" in item.get("supported_parameters", [])
        and "text" in item.get("architecture", {}).get("output_modalities", ["text"])
        and item["id"] not in EXCLUDED_MODELS
    )


def read_catalog(listing, known_efforts):
    models = []
    for item in sorted(filter(is_free, listing["data"]), key=lambda entry: entry["name"].lower()):
        top = item.get("top_provider") or {}
        context = int(item.get("context_length") or top.get("context_length") or 0)
        if context <= 0:
            continue
        models.append(
            {
                "id": item["id"],
                "name": item["name"],
                "efforts": [DEFAULT_EFFORT, *known_efforts.get(item["id"], [])],
                "defaultEffort": DEFAULT_EFFORT,
                "isDefault": not models,
                "contextLength": context,
                "maxOutput": int(top.get("max_completion_tokens") or min(context, 32768)),
            }
        )
    if not models:
        raise RuntimeError("OpenRouter lists no free models with tool support right now")
    return {
        "id": PROVIDER,
        "playerType": "OpenRouter free",
        "playerName": "OpenRouter",
        "auth": "OpenRouter key in the isolated OpenCode sign-in",
        "models": models,
    }


def daily_free_requests(key):
    # Only the free-request counter is kept; credit and account fields in the response are ignored.
    counter = opencode_client.get_json(KEY_URL, key).get("data", {}).get("free_model_daily_requests") or {}
    return {name: counter.get(name) for name in ("used", "limit", "remaining")}


def discover():
    key = opencode_client.read_key(OPENCODE_PROVIDER, paths.sign_in_command("openrouter"))
    catalog = read_catalog(opencode_client.get_json(MODELS_URL), opencode_client.read_efforts(OPENCODE_PROVIDER))
    # start() runs in a separate process and needs the context limits, which the Java client never passes.
    MODELS_PATH.write_text(json.dumps({item["id"]: item for item in catalog["models"]}, indent=2), encoding="utf-8")
    try:
        catalog["freeRequestsToday"] = daily_free_requests(key)
    except OSError:
        catalog["freeRequestsToday"] = None
    return catalog


def validate_selection(catalog, model, reasoning):
    opencode_client.validate_selection(catalog, model, reasoning, "OpenRouter free")
