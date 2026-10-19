"""Shared OpenCode CLI player for API-key providers, locked to the mage game tools.

OpenCode runs from its own home (paths.OPENCODE_HOME, under the ignored .run/llm unless configured), so it
never sees your user profile, skills, CLAUDE.md, your normal OpenCode setup, or the Claude and ChatGPT sign-ins.
Provider keys live in that home's auth.json, written by python -m llm.sign_in; they are only sent to their own
provider and never copied into catalogs or logs.
"""

import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

from llm import paths

DEFAULT_EFFORT = "default"
GAME_TOOL_PREFIX = "mage_"
# OpenCode turns a call to a tool that does not exist into its no-op "invalid" tool; nothing runs.
HARMLESS_TOOLS = {"invalid"}
OPENCODE_HOME = paths.OPENCODE_HOME
AUTH_PATH = OPENCODE_HOME / "data/opencode/auth.json"
SYSTEM_PROMPT = (
    "You are a Magic: The Gathering player in a live XMage game. Your only tools are the mage MCP game tools. "
    "This is a game, not a coding task."
)
CREDENTIAL_OVERRIDES = {
    "OPENROUTER_API_KEY",
    "ZHIPU_API_KEY",
    "ZAI_API_KEY",
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "CODEX_API_KEY",
    "CODEX_ACCESS_TOKEN",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
}
# Built-in OpenCode tools stay off and every permission except the game tools is denied. doom_loop is
# allowed because repeating an identical pass_priority call is normal play.
TOOL_SWITCHES = {"*": False, GAME_TOOL_PREFIX + "*": True}
PERMISSIONS = {"*": "deny", GAME_TOOL_PREFIX + "*": "allow", "doom_loop": "allow"}


def executable():
    opencode = paths.settings().get("opencode_executable")
    if not opencode or not Path(opencode).is_file():
        raise RuntimeError("Run python -m llm.configure with OpenCode installed (or --opencode) before OpenCode play")
    return str(opencode)


def clean_env():
    # Inherited OPENCODE_* settings could add config, credentials or plugins, so none are passed through.
    env = {
        key: value
        for key, value in os.environ.items()
        if key not in CREDENTIAL_OVERRIDES and not key.startswith("OPENCODE_")
    }
    home = OPENCODE_HOME / "home"
    home.mkdir(parents=True, exist_ok=True)
    env.update(
        {
            "HOME": str(home),
            "USERPROFILE": str(home),
            **{f"XDG_{name.upper()}_HOME": str(OPENCODE_HOME / name) for name in ("config", "data", "cache", "state")},
            "OPENCODE_DISABLE_AUTOUPDATE": "1",
            "OPENCODE_DISABLE_CLAUDE_CODE": "1",
            "OPENCODE_DISABLE_EXTERNAL_SKILLS": "1",
            "OPENCODE_DISABLE_PROJECT_CONFIG": "1",
            "OPENCODE_DISABLE_LSP_DOWNLOAD": "1",
        }
    )
    return env


def config(mcp_url, provider, models=None, tool_timeout_seconds=180):
    """Inline OpenCode config for one game; nothing from the user's own OpenCode setup is loaded.

    models registers model entries OpenCode's catalog may not have yet, keyed by model ID.
    """
    result = {
        "$schema": "https://opencode.ai/config.json",
        "username": "player",
        "share": "disabled",
        "autoupdate": False,
        "snapshot": False,
        "lsp": False,
        "formatter": False,
        "plugin": [],
        "instructions": [],
        "enabled_providers": [provider],
        "tools": TOOL_SWITCHES,
        "permission": PERMISSIONS,
        "agent": {
            "mage": {"mode": "primary", "prompt": SYSTEM_PROMPT, "tools": TOOL_SWITCHES, "permission": PERMISSIONS}
        },
    }
    if models:
        result["provider"] = {provider: {"models": models}}
    if mcp_url:
        result["mcp"] = {
            "mage": {
                "type": "remote",
                "url": mcp_url,
                "enabled": True,
                "oauth": False,
                # Also the per-call limit: pass_priority waits while the human thinks.
                "timeout": tool_timeout_seconds * 1000,
            }
        }
    return result


def command(provider, model, reasoning=DEFAULT_EFFORT):
    cmd = [executable(), "run", "--pure", "--format", "json", "--agent", "mage", "--model", f"{provider}/{model}"]
    if reasoning != DEFAULT_EFFORT:
        cmd.extend(["--variant", reasoning])
    return cmd


def launch(prompt, stdout, stderr, game_config, cmd):
    env = clean_env()
    env["OPENCODE_CONFIG_CONTENT"] = json.dumps(game_config)
    proc = subprocess.Popen(
        cmd,
        cwd=paths.PLAYER_CWD,
        env=env,
        stdin=subprocess.PIPE,
        stdout=stdout,
        stderr=stderr,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    proc.stdin.write(prompt.encode("utf-8"))
    proc.stdin.close()
    return proc


def require_game_only_events(events):
    for event in events:
        part = event.get("part") or {}
        if part.get("type") == "tool":
            name = part.get("tool", "")
            if not name.startswith(GAME_TOOL_PREFIX) and name not in HARMLESS_TOOLS:
                raise RuntimeError("Information boundary violation: the player used a non-game tool")


def usage(events):
    steps = [event["part"] for event in events if (event.get("part") or {}).get("type") == "step-finish"]
    if not steps:
        return []
    totals = {"steps": len(steps), "input": 0, "output": 0, "reasoning": 0, "cache_read": 0, "cost": 0}
    for step in steps:
        tokens = step.get("tokens", {})
        for key in ("input", "output", "reasoning"):
            totals[key] += tokens.get(key, 0)
        totals["cache_read"] += tokens.get("cache", {}).get("read", 0)
        totals["cost"] += step.get("cost", 0)
    return [totals]


def error_message(events):
    """The provider's own message from OpenCode's last error event, or None."""
    errors = [event for event in events if event.get("type") == "error"]
    if not errors:
        return None
    return (errors[-1].get("error") or {}).get("data", {}).get("message") or "OpenCode reported an error"


def with_hint(message, hint):
    return message.rstrip(". ") + ". " + hint


def read_key(provider, sign_in):
    """The stored key, used only for that provider's own requests; never returned to catalogs or logs."""
    entry = json.loads(AUTH_PATH.read_text(encoding="utf-8")).get(provider) if AUTH_PATH.is_file() else None
    if not entry or entry.get("type") != "api" or not entry.get("key"):
        raise RuntimeError(f"Sign in with {sign_in} before opening these matches.")
    return entry["key"]


def get_json(url, key=None):
    headers = {"Accept": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    request = urllib.request.Request(url, headers=headers)  # noqa: S310 - callers pass their provider's fixed https URL
    with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - same fixed https URL
        return json.loads(response.read())


def read_efforts(provider):
    """Reasoning variants OpenCode knows for each of a provider's models, from its refreshed catalog."""
    result = subprocess.run(
        [executable(), "models", provider, "--verbose", "--refresh", "--pure"],
        cwd=paths.PLAYER_CWD,
        env=clean_env(),
        capture_output=True,
        check=False,
        text=True,
        encoding="utf-8",
        timeout=120,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    return parse_models(result.stdout)


def parse_models(text):
    decoder = json.JSONDecoder()
    efforts = {}
    for match in re.finditer(r"^\{", text, re.MULTILINE):
        try:
            item, _ = decoder.raw_decode(text, match.start())
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict) and "id" in item:
            efforts[item["id"]] = list(item.get("variants") or {})
    return efforts


def validate_selection(catalog, model, reasoning, label):
    for item in catalog["models"]:
        if item["id"] == model:
            if reasoning not in item["efforts"]:
                raise ValueError(f"{model} does not support effort level {reasoning}")
            return
    raise ValueError(f"{model} is not in the {label} catalog; refresh the model list")
