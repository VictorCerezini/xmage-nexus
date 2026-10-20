"""Z.ai GLM Coding Plan played through the locked-down OpenCode player (see opencode_client).

The plan may only be used within officially supported tools; OpenCode is one, and its built-in
zai-coding-plan provider sends requests to the plan's own endpoint. The key is only sent to api.z.ai.
"""

from llm import opencode_client, paths

PROVIDER = "zai_coding_plan"
OPENCODE_PROVIDER = "zai-coding-plan"
MODELS_URL = "https://api.z.ai/api/coding/paas/v4/models"
DEFAULT_EFFORT = opencode_client.DEFAULT_EFFORT
MODEL = "glm-5.3"
# Z.ai's model list is account-wide rather than per plan, and the plan silently routes older IDs
# (GLM-5.2/5.1 to GLM-5.3, GLM-4.7 to GLM-5.3-Flash). Only the models every plan tier documents are
# offered, so a seat never plays as a different model than the one shown.
GUI_MODELS = [
    {"id": "glm-5.3", "name": "GLM-5.3"},
    {"id": "glm-5.3-flash", "name": "GLM-5.3-Flash"},
]


def start(prompt, stdout, stderr, mcp_url=None, *, model=MODEL, reasoning=DEFAULT_EFFORT, tool_timeout_seconds=180):
    game_config = opencode_client.config(mcp_url, OPENCODE_PROVIDER, tool_timeout_seconds=tool_timeout_seconds)
    cmd = opencode_client.command(OPENCODE_PROVIDER, model, reasoning)
    return opencode_client.launch(prompt, stdout, stderr, game_config, cmd)


def failure_reason(events):
    message = opencode_client.error_message(events)
    if message is None:
        return None
    lowered = message.lower()
    if "limit" in lowered or "quota" in lowered or "insufficient" in lowered:
        return opencode_client.with_hint(
            message, "Your GLM Coding Plan's 5-hour or weekly allowance may be used up; check usage at z.ai."
        )
    return message


def read_catalog(listing, known_efforts):
    available = {item.get("id") for item in listing.get("data", [])}
    models = [
        {
            "id": item["id"],
            "name": item["name"],
            "efforts": [DEFAULT_EFFORT, *known_efforts.get(item["id"], [])],
            "defaultEffort": DEFAULT_EFFORT,
            "isDefault": item["id"] == MODEL,
        }
        for item in GUI_MODELS
        if item["id"] in available
    ]
    if not models:
        raise RuntimeError("The Z.ai GLM Coding Plan offers neither GLM-5.3 nor GLM-5.3-Flash to this key")
    return {
        "id": PROVIDER,
        "playerType": "Z.ai GLM Coding Plan",
        "playerName": "GLM",
        "auth": "Z.ai GLM Coding Plan key in the isolated OpenCode sign-in",
        "models": models,
    }


def discover():
    # Listing models is not inference and uses no plan allowance; it confirms the key works.
    key = opencode_client.read_key(OPENCODE_PROVIDER, paths.sign_in_command("zai"))
    return read_catalog(opencode_client.get_json(MODELS_URL, key), opencode_client.read_efforts(OPENCODE_PROVIDER))


def validate_selection(catalog, model, reasoning):
    if model not in {item["id"] for item in GUI_MODELS}:
        raise ValueError(
            f"{model} is not offered for model seats; choose " + " or ".join(m["name"] for m in GUI_MODELS)
        )
    opencode_client.validate_selection(catalog, model, reasoning, "Z.ai GLM Coding Plan")
