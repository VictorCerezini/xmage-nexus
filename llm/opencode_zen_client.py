"""OpenCode Zen played through the locked-down OpenCode player (see opencode_client).

OpenCode Zen is OpenCode's pay-as-you-go service: each request is charged to the OpenCode console's credit
balance, without a subscription. OpenCode's built-in opencode provider sends the requests with the stored
key to opencode.ai. Zen's model list is public, so this module only checks that a key is stored and never
sends it anywhere itself.
"""

from llm import opencode_client, paths

PROVIDER = "opencode_zen"
OPENCODE_PROVIDER = "opencode"
NAME = "OpenCode Zen"
AUTH = "your OpenCode Zen key, saved only for this app's model seats; each request spends your Zen credits"
MODELS_URL = "https://opencode.ai/inference/v1/models"
DEFAULT_EFFORT = opencode_client.DEFAULT_EFFORT
# Preselected when Zen offers it: an inexpensive model that plays with tools.
MODEL = "deepseek-v4.1-flash"
BALANCE_WORDS = ("balance", "credit", "insufficient", "payment")


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
            message, "OpenCode Zen refused the stored key; sign in again: " + paths.sign_in_command("opencode-zen")
        )
    if any(word in message.lower() for word in BALANCE_WORDS):
        return opencode_client.with_hint(
            message,
            "Your OpenCode Zen credits may be used up; add credits at opencode.ai/console, or choose a cheaper model.",
        )
    return message


def read_catalog(listing, known_efforts):
    """Zen's current models that this OpenCode knows how to call, with the effort levels OpenCode has for them."""
    return opencode_client.listed_catalog(
        listing, known_efforts, provider=PROVIDER, name=NAME, auth=AUTH, preferred=MODEL
    )


def discover():
    # The key is only checked for here; OpenCode sends it when a game is played.
    opencode_client.read_key(OPENCODE_PROVIDER, NAME, "opencode-zen")
    listing = opencode_client.get_json(MODELS_URL, user_agent=opencode_client.USER_AGENT)
    return read_catalog(listing, opencode_client.read_efforts(OPENCODE_PROVIDER))


def validate_selection(catalog, model, reasoning):
    opencode_client.validate_selection(catalog, model, reasoning, NAME)
