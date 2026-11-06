"""OpenCode Go played through the locked-down OpenCode player (see opencode_client).

OpenCode Go is OpenCode's own subscription, and OpenCode's built-in opencode-go provider sends the requests
with the Go key to opencode.ai. Go's model list is public, so this module only checks that a key is stored
and never sends it anywhere itself. Console credits without a Go plan pay for OpenCode Zen instead
(opencode_zen_client).
"""

from llm import opencode_client, paths

PROVIDER = "opencode_go"
OPENCODE_PROVIDER = "opencode-go"
NAME = "OpenCode Go"
AUTH = "your OpenCode Go subscription key, saved only for this app's model seats"
MODELS_URL = "https://opencode.ai/inference/go/v1/models"
DEFAULT_EFFORT = opencode_client.DEFAULT_EFFORT
# Preselected when Go offers it: the GLM model this app's games have been played with most.
MODEL = "glm-5.3"
ALLOWANCE_WORDS = ("limit", "quota", "allowance", "insufficient")
# Go serves some models (DeepSeek's) from outside Europe and the United States, and only to a workspace whose
# owner allowed Global regions. Asking again does not change that, so the seat stops instead of retrying.
REGION_WORDS = ("hosted outside", "select global")


def start(prompt, stdout, stderr, mcp_url=None, *, model=MODEL, reasoning=DEFAULT_EFFORT, tool_timeout_seconds=180):
    game_config = opencode_client.config(mcp_url, OPENCODE_PROVIDER, tool_timeout_seconds=tool_timeout_seconds)
    cmd = opencode_client.command(OPENCODE_PROVIDER, model, reasoning)
    return opencode_client.launch(prompt, stdout, stderr, game_config, cmd)


def region_refused(events):
    message = opencode_client.error_message(events)
    return message is not None and any(word in message.lower() for word in REGION_WORDS)


def lasting_failure(events):
    return opencode_client.lasting_error(events) or region_refused(events)


def failure_reason(events):
    message = opencode_client.error_message(events)
    if message is None:
        return None
    if opencode_client.error_data(events).get("statusCode") == 401:
        return opencode_client.with_hint(
            message, "OpenCode Go refused the stored key; sign in again: " + paths.sign_in_command("opencode-go")
        )
    if region_refused(events):
        return opencode_client.with_hint(
            message,
            "OpenCode Go serves this model only after a workspace owner allows Global regions "
            "(OpenCode console: Settings, Privacy, Regions); or choose another model.",
        )
    if any(word in message.lower() for word in ALLOWANCE_WORDS):
        return opencode_client.with_hint(
            message,
            "Your OpenCode Go allowance for this model (5-hour, weekly or monthly) may be used up; "
            "check usage at opencode.ai/console, or choose another model.",
        )
    return message


def read_catalog(listing, known_efforts):
    """Go's current models that this OpenCode knows how to call, with the effort levels OpenCode has for them."""
    return opencode_client.listed_catalog(
        listing, known_efforts, provider=PROVIDER, name=NAME, auth=AUTH, preferred=MODEL
    )


def discover():
    # The key is only checked for here; OpenCode sends it when a game is played.
    opencode_client.read_key(OPENCODE_PROVIDER, NAME, "opencode-go")
    listing = opencode_client.get_json(MODELS_URL, user_agent=opencode_client.USER_AGENT)
    return read_catalog(listing, opencode_client.read_efforts(OPENCODE_PROVIDER))


def validate_selection(catalog, model, reasoning):
    opencode_client.validate_selection(catalog, model, reasoning, NAME)
