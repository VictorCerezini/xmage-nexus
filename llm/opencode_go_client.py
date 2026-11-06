"""OpenCode Go played through the locked-down OpenCode player (see opencode_client).

OpenCode Go is OpenCode's own subscription, and OpenCode's built-in opencode-go provider sends the requests
with the Go key to opencode.ai. Go's model list is public, so this module only checks that a key is stored
and never sends it anywhere itself.
"""

from llm import opencode_client, paths

PROVIDER = "opencode_go"
OPENCODE_PROVIDER = "opencode-go"
MODELS_URL = "https://opencode.ai/inference/go/v1/models"
# Go refuses Python's default user agent and asks every client to name itself.
USER_AGENT = "xmage-nexus-model-seats/1.0"
DEFAULT_EFFORT = opencode_client.DEFAULT_EFFORT
# Preselected when Go offers it: the GLM model this app's games have been played with most.
MODEL = "glm-5.3"
ALLOWANCE_WORDS = ("limit", "quota", "allowance", "insufficient")


def start(prompt, stdout, stderr, mcp_url=None, *, model=MODEL, reasoning=DEFAULT_EFFORT, tool_timeout_seconds=180):
    game_config = opencode_client.config(mcp_url, OPENCODE_PROVIDER, tool_timeout_seconds=tool_timeout_seconds)
    cmd = opencode_client.command(OPENCODE_PROVIDER, model, reasoning)
    return opencode_client.launch(prompt, stdout, stderr, game_config, cmd)


def failure_reason(events):
    message = opencode_client.error_message(events)
    if message is None:
        return None
    if opencode_client.error_data(events).get("statusCode") == 401:
        return opencode_client.with_hint(
            message, "OpenCode Go refused the stored key; sign in again: " + paths.sign_in_command("opencode-go")
        )
    if any(word in message.lower() for word in ALLOWANCE_WORDS):
        return opencode_client.with_hint(
            message,
            "Your OpenCode Go allowance for this model (5-hour, weekly or monthly) may be used up; "
            "check usage at opencode.ai/console, or choose another model.",
        )
    return message


def playable(item, known_efforts):
    capabilities = item.get("capabilities") or {}
    return (
        item.get("id") in known_efforts
        and item.get("type", "chat") == "chat"
        and (capabilities.get("tools") or {}).get("supported", True) is not False
    )


def read_catalog(listing, known_efforts):
    """Go's current models that this OpenCode knows how to call, with the effort levels OpenCode has for them."""
    offered = sorted(
        (item for item in listing.get("data", []) if playable(item, known_efforts)),
        key=lambda item: (item.get("name") or item["id"]).lower(),
    )
    if not offered:
        raise RuntimeError("OpenCode Go lists no model this OpenCode version can play; update OpenCode")
    default = MODEL if any(item["id"] == MODEL for item in offered) else offered[0]["id"]
    models = [
        {
            "id": item["id"],
            "name": item.get("name") or item["id"],
            "efforts": [DEFAULT_EFFORT, *known_efforts[item["id"]]],
            "defaultEffort": DEFAULT_EFFORT,
            "isDefault": item["id"] == default,
        }
        for item in offered
    ]
    return {
        "id": PROVIDER,
        "playerType": "OpenCode Go",
        "playerName": "OpenCode Go",
        "auth": "your OpenCode Go key, saved only for this app's model seats",
        "models": models,
    }


def discover():
    # The key is only checked for here; OpenCode sends it when a game is played.
    opencode_client.read_key(OPENCODE_PROVIDER, "OpenCode Go", "opencode-go")
    listing = opencode_client.get_json(MODELS_URL, user_agent=USER_AGENT)
    return read_catalog(listing, opencode_client.read_efforts(OPENCODE_PROVIDER))


def validate_selection(catalog, model, reasoning):
    opencode_client.validate_selection(catalog, model, reasoning, "OpenCode Go")
