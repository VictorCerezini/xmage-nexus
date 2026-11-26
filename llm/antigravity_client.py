"""Antigravity CLI (agy) player using its existing Google sign-in; no provider API keys.

Each run gets a home folder of its own (USERPROFILE), so nothing of this computer's agy setup reaches the
player: no global MCP servers, rules, skills, plugins, hooks or saved conversations, and the player's runs
never change that setup. The Google sign-in is in the system keyring (Windows Credential Manager), not in the
home folder, so the player still plays on the subscription. In that home the player is a custom agent without
the CLI's built-in tools (files, terminal, web search, browser, subagents): only the mage game tools are offered.
"""

import json
import os
import re
import subprocess
from pathlib import Path

from llm import paths
from llm.claude_client import SYSTEM_PROMPT
from llm.subscription_client import CHAT_TOOL, TOOLS

PROVIDER = "google_subscription"
NAME = "Google"
AUTH = "your Google login in the Antigravity CLI"
MODEL = "gemini-4-hydrogen"
# agy names one model per reasoning level ("gemini-4-hydrogen-high"); the picker shows the model once with
# its levels, and a model without levels gets this one.
DEFAULT_EFFORT = "default"
EFFORTS = ["minimal", "low", "medium", "high", "xhigh", "max"]
AGENT = "mage-player"
SERVER = "mage"
HOME_FOLDER = "antigravity-home"
# Credential, provider and endpoint overrides would bypass the subscription sign-in.
CREDENTIAL_OVERRIDES = {
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "GOOGLE_GEMINI_BASE_URL",
    "GOOGLE_APPLICATION_CREDENTIALS",
    "GOOGLE_CLOUD_PROJECT",
    "GOOGLE_CLOUD_QUOTA_PROJECT",
    "GOOGLE_CLOUD_LOCATION",
    "AGY_ADC_AUTH",
    "AGY_LLM_GATEWAY_API_KEY",
    "AGY_LLM_GATEWAY_HEADERS",
    "AGY_LLM_GATEWAY_MODELS",
    "OPENAI_API_KEY",
    "CODEX_API_KEY",
    "CODEX_ACCESS_TOKEN",
    "ANTHROPIC_API_KEY",
    "OPENROUTER_API_KEY",
    "OPENAI_BASE_URL",
}
# A second guard under the agent's missing tools: what the CLI's rules can name stays denied.
PERMISSIONS = {
    "allow": [f"mcp({SERVER}/*)"],
    "deny": ["command(*)", "read_file(*)", "write_file(*)", "read_url(*)", "execute_url(*)"],
}
SIGNED_OUT = ("not logged in", "authentication required", "unauthenticated", "log in", "sign in")
# Starting agy on its own asks for the Google sign-in; model seats read the same sign-in.
SIGN_IN_STEPS = (
    "The Antigravity CLI on this computer is not signed in with your Google account. "
    "Open PowerShell, run agy and sign in, then press Refresh models."
)
LISTED = re.compile(r"^(\S+)\t(.+)$")


def mcp_tools(tools=TOOLS):
    # Each game tool is loaded up front and offered by this name; nothing else is.
    return [f"mcp_{SERVER}_{name}" for name in tools]


def clean_env(home):
    env = {key: value for key, value in os.environ.items() if key.upper() not in CREDENTIAL_OVERRIDES}
    env.update({"USERPROFILE": str(home), "HOME": str(home)})
    return env


def executable():
    agy = paths.settings().get("agy_executable")
    if not agy or not Path(agy).is_file():
        raise RuntimeError(paths.missing_cli("Antigravity CLI", "--agy", "agy"))
    return str(agy)


def agent_definition():
    """The custom agent the player runs as: no built-in tools (excludeDefaultComponents), no skills, rules or
    plugins, and its body as the whole system prompt."""
    return (
        "---\n"
        f"name: {AGENT}\n"
        "description: Plays one Magic The Gathering game in XMage through the mage game tools.\n"
        "mainAgent: true\n"
        "subagent: false\n"
        "excludeDefaultComponents: true\n"
        "inheritCustomizations: false\n"
        "commandExecutionPolicy: off\n"
        "---\n\n"
        "# Game player\n\n"
        f"{SYSTEM_PROMPT}\n"
    )


def write_home(home, mcp_url=None, *, tools=TOOLS, tool_timeout_seconds=180):
    """The player's own agy home: its agent, its permission rules and the seat's game server."""
    cli = home / ".gemini" / "antigravity-cli"
    config = home / ".gemini" / "config"
    (config / "agents").mkdir(parents=True, exist_ok=True)
    cli.mkdir(parents=True, exist_ok=True)
    (cli / "settings.json").write_text(json.dumps({"permissions": PERMISSIONS}, indent=2), encoding="utf-8")
    (config / "agents" / f"{AGENT}.md").write_text(agent_definition(), encoding="utf-8")
    servers = {}
    if mcp_url:
        servers[SERVER] = {
            "serverUrl": mcp_url,
            # One value sets both the connect and the per-call limit; pass_priority waits for the others.
            "timeoutSeconds": tool_timeout_seconds,
            "tools": {name: {"eager": True} for name in tools},
        }
    (config / "mcp_config.json").write_text(json.dumps({"mcpServers": servers}, indent=2), encoding="utf-8")


def model_id(model, reasoning):
    return model if reasoning == DEFAULT_EFFORT else f"{model}-{reasoning}"


def command(*, model=MODEL, reasoning=DEFAULT_EFFORT):
    return [
        executable(),
        "--output-format",
        "stream-json",
        # The prompt comes on stdin as one user message, never on the command line.
        "--input-format",
        "stream-json",
        "--disable-slash-commands",
        "--agent",
        AGENT,
        "--model",
        model_id(model, reasoning),
        # Print mode; with stream-json input its prompt must be empty.
        "--print=",
    ]


def start(
    prompt,
    stdout,
    stderr,
    mcp_url=None,
    *,
    model=MODEL,
    reasoning=DEFAULT_EFFORT,
    tool_timeout_seconds=180,
    tools=TOOLS,
):
    # The home sits next to the run's event file, so each seat has its own and nothing is shared between seats.
    home = Path(stdout.name).resolve().parent / HOME_FOLDER
    write_home(home, mcp_url, tools=tools, tool_timeout_seconds=tool_timeout_seconds)
    proc = subprocess.Popen(
        command(model=model, reasoning=reasoning),
        cwd=paths.PLAYER_CWD,
        env=clean_env(home),
        stdin=subprocess.PIPE,
        stdout=stdout,
        stderr=stderr,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    message = {"event": "user", "message": {"content": prompt}}
    proc.stdin.write((json.dumps(message) + "\n").encode("utf-8"))
    # A closed stdin ends the session once this turn completes.
    proc.stdin.close()
    return proc


def steps(events):
    return [event["step_update"] for event in events if event.get("event") == "step_update"]


def require_game_only_events(events, tools=(*TOOLS, CHAT_TOOL)):
    # The chat tool too: the seat offers it only at a table whose host lets the model talk.
    allowed = set(mcp_tools(tools))
    for step in steps(events):
        if step.get("step_type") == "tool" and step.get("tool_name") not in allowed:
            raise RuntimeError("Information boundary violation: the player used a non-game tool")


def usage(events):
    results = [event["result"] for event in events if event.get("event") == "result"]
    if results:
        return [
            {
                "status": result.get("status"),
                "num_turns": result.get("num_turns"),
                "duration_seconds": result.get("duration_seconds"),
                **(result.get("usage") or {}),
            }
            for result in results
        ]
    # A player stopped after its game ended never prints a result; its model requests still counted.
    counted = [step["usage"] for step in steps(events) if step.get("usage")]
    if not counted:
        return []
    total = {}
    for item in counted:
        for key, value in item.items():
            if isinstance(value, int):
                total[key] = total.get(key, 0) + value
    return [{"status": "stopped", "model_requests": len(counted), **total}]


def failure_reason(events):
    """The CLI's error for a run that did not end well, such as a reached quota."""
    for event in reversed(events):
        if event.get("event") == "result":
            result = event["result"]
            if result.get("status") == "SUCCESS":
                return None
            return result.get("error") or result.get("finished_reason") or result.get("status") or None
    return None


def read_listing(text):
    return [match.groups() for match in map(LISTED.match, text.splitlines()) if match]


def split_effort(model, name):
    """('gemini-4-hydrogen', 'Gemini 4 Hydrogen', 'high') for gemini-4-hydrogen-high; a model without a level
    in both its id and its name keeps them, with the default level."""
    for effort in EFFORTS:
        label = f" ({'XHigh' if effort == 'xhigh' else effort.capitalize()})"
        if model.endswith("-" + effort) and name.endswith(label):
            return model[: -len(effort) - 1], name[: -len(label)], effort
    return model, name, DEFAULT_EFFORT


def read_catalog(listing):
    grouped = {}
    for listed_id, listed_name in listing:
        model, name, effort = split_effort(listed_id, listed_name)
        entry = grouped.setdefault(model, {"name": name, "efforts": set()})
        entry["efforts"].add(effort)
    if not grouped:
        raise RuntimeError("The Antigravity CLI lists no models for this Google sign-in")
    order = [DEFAULT_EFFORT, *EFFORTS]
    default_model = MODEL if MODEL in grouped else next(iter(grouped))
    models = []
    for model, entry in grouped.items():
        efforts = sorted(entry["efforts"], key=order.index)
        models.append(
            {
                "id": model,
                "name": entry["name"],
                "efforts": efforts,
                "defaultEffort": "medium" if "medium" in efforts else efforts[0],
                "isDefault": model == default_model,
            }
        )
    return {"id": PROVIDER, "playerType": "Google subscription", "playerName": NAME, "auth": AUTH, "models": models}


def discover():
    # Email, plan and account details are never asked for or saved; only the model list is read.
    agy = executable()
    home = paths.RUN_ROOT / HOME_FOLDER
    home.mkdir(parents=True, exist_ok=True)
    paths.PLAYER_CWD.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [agy, "models"],
        cwd=paths.PLAYER_CWD,
        env=clean_env(home),
        capture_output=True,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=90,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    listing = read_listing(result.stdout or "")
    if result.returncode == 0 and listing:
        return read_catalog(listing)
    output = (result.stderr or "") + (result.stdout or "")
    if any(words in output.lower() for words in SIGNED_OUT):
        # A plain reason, not a SignInError: the app words a sign-in command as "add your key" unless the
        # desktop app has a sign-in window for it, and agy has none.
        raise RuntimeError(SIGN_IN_STEPS)
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    raise RuntimeError("The Antigravity CLI could not list its models" + (f": {lines[-1][:300]}" if lines else ""))


def validate_selection(catalog, model, reasoning):
    for item in catalog["models"]:
        if item["id"] == model:
            if reasoning not in item["efforts"]:
                raise ValueError(f"{model} does not support reasoning level {reasoning}")
            return
    raise ValueError(f"{model} is not in the Google model catalog; refresh the model list")


if __name__ == "__main__":
    folder = paths.RUN_ROOT / "preflight/antigravity"
    folder.mkdir(parents=True, exist_ok=True)
    with (
        (folder / "agy-events.jsonl").open("w", encoding="utf-8") as out,
        (folder / "agy-stderr.log").open("w", encoding="utf-8") as err,
    ):
        proc = start(
            "This is a subscription connection test. Do not use tools. Reply exactly SUBSCRIPTION_READY.",
            out,
            err,
            model="gemini-3.8-flash",
            reasoning="low",
        )
        result = proc.wait(timeout=120)
    print(f"Antigravity connection test exit code: {result}")
    raise SystemExit(result)
