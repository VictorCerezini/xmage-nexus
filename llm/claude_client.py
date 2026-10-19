"""Claude Code CLI player using its existing Claude subscription login; no provider API keys."""

import json
import os
import subprocess
from pathlib import Path

from llm import paths
from llm.subscription_client import TOOLS

PROVIDER = "claude_subscription"
MODEL = "claude-opus-5-5"
# Claude Code has no account model-list call, so the GUI offers these documented IDs. Each is passed
# verbatim, and model-access fallback is disabled: an unavailable model fails instead of silently
# playing as a different one.
GUI_MODELS = [
    {"id": "claude-opus-5-5", "name": "Claude Opus 5.5"},
    {"id": "claude-sonnet-5-5", "name": "Claude Sonnet 5.5"},
    {"id": "claude-haiku-5-5", "name": "Claude Haiku 5.5"},
]
EFFORTS = ["low", "medium", "high", "xhigh", "max"]
DEFAULT_EFFORT = "medium"
MCP_TOOLS = ["mcp__mage__" + name for name in TOOLS]
SYSTEM_PROMPT = (
    "You are a Magic: The Gathering player in a live XMage game. Your only tools are the mage MCP game tools. "
    "This is a game, not a coding task."
)
# Credential, provider and endpoint overrides would bypass the subscription sign-in.
CREDENTIAL_OVERRIDES = {
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_MODEL",
    "ANTHROPIC_CUSTOM_HEADERS",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
    "CLAUDE_CODE_USE_GATEWAY",
    "CLAUDE_CODE_USE_MANTLE",
    "CLAUDE_CONFIG_DIR",
    "OPENAI_API_KEY",
    "CODEX_API_KEY",
    "CODEX_ACCESS_TOKEN",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "OPENROUTER_API_KEY",
    "OPENAI_BASE_URL",
}


def clean_env(tool_timeout_seconds=180):
    env = {key: value for key, value in os.environ.items() if key not in CREDENTIAL_OVERRIDES}
    env.update(
        {
            "CLAUDE_CODE_DISABLE_CLAUDE_MDS": "1",
            "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1",
            "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS": "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
            "CLAUDE_CODE_DISABLE_MODEL_ACCESS_FALLBACK": "1",
            "DISABLE_AUTOUPDATER": "1",
            "MCP_TIMEOUT": "30000",
            "MCP_TOOL_TIMEOUT": str(tool_timeout_seconds * 1000),
        }
    )
    return env


def executable():
    claude = paths.settings().get("claude_executable")
    if not claude or not Path(claude).is_file():
        raise RuntimeError("Run python -m llm.configure with Claude Code installed (or --claude) before Claude play")
    return str(claude)


def command(mcp_url=None, *, model=MODEL, reasoning=DEFAULT_EFFORT):
    cmd = [
        executable(),
        "-p",
        "--output-format",
        "stream-json",
        "--verbose",
        "--model",
        model,
        "--effort",
        reasoning,
        "--system-prompt",
        SYSTEM_PROMPT,
        # No user/project/local settings: no hooks, plugins, permissions or apiKeyHelper from this machine.
        "--setting-sources",
        "",
        "--tools",
        "",
        "--strict-mcp-config",
        "--permission-mode",
        "dontAsk",
        "--permission-prompts",
        "none",
        "--disable-slash-commands",
        "--no-session-persistence",
        "--no-chrome",
    ]
    if mcp_url:
        server = {"mcpServers": {"mage": {"type": "http", "url": mcp_url}}}
        cmd.extend(["--mcp-config", json.dumps(server), "--allowedTools", ",".join(MCP_TOOLS)])
    return cmd


def start(prompt, stdout, stderr, mcp_url=None, *, model=MODEL, reasoning=DEFAULT_EFFORT, tool_timeout_seconds=180):
    proc = subprocess.Popen(
        command(mcp_url, model=model, reasoning=reasoning),
        cwd=paths.PLAYER_CWD,
        env=clean_env(tool_timeout_seconds),
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
        if event.get("type") == "system" and event.get("subtype") == "init":
            extra = set(event.get("tools", [])) - set(MCP_TOOLS)
            if extra:
                raise RuntimeError("Information boundary violation: the player was offered a non-game tool")
        if event.get("type") == "assistant":
            for block in event["message"].get("content", []):
                if block.get("type") in {"tool_use", "server_tool_use"} and block.get("name") not in MCP_TOOLS:
                    raise RuntimeError("Information boundary violation: the player used a non-game tool")


def usage(events):
    return [
        {
            "num_turns": event.get("num_turns"),
            "duration_ms": event.get("duration_ms"),
            "is_error": event.get("is_error"),
            **event.get("usage", {}),
        }
        for event in events
        if event.get("type") == "result"
    ]


def read_auth_status():
    result = subprocess.run(
        [executable(), "auth", "status", "--json"],
        cwd=paths.PLAYER_CWD,
        env=clean_env(),
        capture_output=True,
        # A signed-out CLI exits non-zero but still reports its status as JSON.
        check=False,
        text=True,
        encoding="utf-8",
        timeout=60,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    return json.loads(result.stdout)


def read_catalog(status):
    # Email, organization and plan details are returned by the CLI but never copied or saved.
    if (
        not status.get("loggedIn")
        or status.get("authMethod") != "claude.ai"
        or status.get("apiProvider") != "firstParty"
    ):
        raise RuntimeError(
            "Sign into Claude Code with your Claude subscription before opening Claude matches (claude auth login)."
        )
    return {
        "id": PROVIDER,
        "playerType": "Claude subscription",
        "playerName": "Claude",
        "auth": "existing Claude Code subscription login",
        "models": [
            {
                "id": item["id"],
                "name": item["name"],
                "efforts": EFFORTS,
                "defaultEffort": DEFAULT_EFFORT,
                "isDefault": item["id"] == MODEL,
            }
            for item in GUI_MODELS
        ],
    }


def discover():
    return read_catalog(read_auth_status())


def validate_selection(catalog, model, reasoning):
    if model not in {item["id"] for item in GUI_MODELS}:
        raise ValueError(f"{model} is not offered for model seats; choose " + ", ".join(m["name"] for m in GUI_MODELS))
    for item in catalog["models"]:
        if item["id"] == model:
            if reasoning not in item["efforts"]:
                raise ValueError(f"{model} does not support effort level {reasoning}")
            return
    raise ValueError(f"{model} is not in the Claude model catalog; refresh the model list")


if __name__ == "__main__":
    folder = paths.RUN_ROOT / "preflight/claude"
    folder.mkdir(parents=True, exist_ok=True)
    with (
        (folder / "claude-events.jsonl").open("w", encoding="utf-8") as out,
        (folder / "claude-stderr.log").open("w", encoding="utf-8") as err,
    ):
        proc = start(
            "This is a subscription connection test. Do not use tools. Reply exactly SUBSCRIPTION_READY.",
            out,
            err,
            model="claude-sonnet-5-5",
            reasoning="low",
        )
        result = proc.wait(timeout=120)
    print(f"Claude connection test exit code: {result}")
    raise SystemExit(result)
