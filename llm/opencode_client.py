"""Shared OpenCode CLI player for API-key providers, locked to the mage game tools.

OpenCode runs from its own home (paths.OPENCODE_HOME, under the ignored .run/llm unless configured), so it
never sees your user profile, skills, CLAUDE.md, your normal OpenCode setup, or the Claude and ChatGPT sign-ins.
Provider keys live in that home, written by python -m llm.sign_in: OpenCode 1 keeps them in auth.json and
OpenCode 2 in its own database. They are only sent to their own provider and never copied into catalogs or logs.

OpenCode 1 plays with one run --pure process. OpenCode 2 plays through a private, password-protected server
of its own for each player (see start_server), never the background service that uses your normal setup.
"""

import json
import os
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

from llm import paths

DEFAULT_EFFORT = "default"
GAME_TOOL_PREFIX = "mage_"
# OpenCode turns a call to a tool that does not exist into its no-op "invalid" tool; nothing runs.
HARMLESS_TOOLS = {"invalid"}
OPENCODE_HOME = paths.OPENCODE_HOME
AUTH_PATH = OPENCODE_HOME / "data/opencode/auth.json"
# OpenCode's own services (Go, Zen) refuse Python's default user agent and ask every client to name itself.
USER_AGENT = "xmage-nexus-model-seats/1.0"
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
# OpenCode 2 appends rules of its own after every agent's (for its browser), and the last rule naming a tool
# decides whether the model is offered it, so its skill tool stayed offered. Rules given to the session come
# after those, so the game's rules are repeated there.
SESSION_PERMISSIONS = [{"action": action, "resource": "*", "effect": effect} for action, effect in PERMISSIONS.items()]
# A named session saves OpenCode 2 a model request to give it a title.
SESSION_TITLE = "XMage game"
SERVER_START_SECONDS = 60
_MAJOR_VERSIONS = {}


def executable():
    opencode = paths.settings().get("opencode_executable")
    if not opencode or not Path(opencode).is_file():
        raise RuntimeError(paths.missing_cli("OpenCode", "--opencode"))
    return str(opencode)


def clean_env():
    # Inherited OPENCODE_* settings could add config, credentials or plugins, so none are passed through.
    # OpenCode 2 also takes its working folder from PWD, which a shell may have set to your own folder.
    env = {
        key: value
        for key, value in os.environ.items()
        if key not in CREDENTIAL_OVERRIDES and not key.startswith("OPENCODE_") and key.upper() != "PWD"
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


def run_cli(args, *, env=None, exe=None, timeout=120):
    """One short OpenCode command from the isolated home; its output is captured, never shown."""
    paths.PLAYER_CWD.mkdir(parents=True, exist_ok=True)
    return subprocess.run(
        [exe or executable(), *args],
        cwd=paths.PLAYER_CWD,
        env=env or clean_env(),
        stdin=subprocess.DEVNULL,
        capture_output=True,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )


def major_version(exe):
    """OpenCode's major version, read once per executable; 1 when it cannot be read, so nothing changes for it."""
    path = Path(exe)
    try:
        key = (str(path.resolve()), path.stat().st_mtime_ns)
    except OSError:
        return 1
    if key not in _MAJOR_VERSIONS:
        try:
            match = re.search(r"(\d+)\.\d+", run_cli(["--version"], exe=exe, timeout=60).stdout)
        except (OSError, subprocess.SubprocessError):
            match = None
        _MAJOR_VERSIONS[key] = int(match.group(1)) if match else 1
    return _MAJOR_VERSIONS[key]


def served(exe=None):
    """Whether this OpenCode (2 or later) plays through a private server instead of one run --pure process."""
    return major_version(exe or executable()) >= 2


def command(provider, model, reasoning=DEFAULT_EFFORT):
    exe = executable()
    if served(exe):
        # OpenCode 2 has no --pure or --variant: the variant follows the model, and launch() adds the server.
        spec = f"{provider}/{model}" if reasoning == DEFAULT_EFFORT else f"{provider}/{model}#{reasoning}"
        return [exe, "run", "--format", "json", "--agent", "mage", "--model", spec]
    cmd = [exe, "run", "--pure", "--format", "json", "--agent", "mage", "--model", f"{provider}/{model}"]
    if reasoning != DEFAULT_EFFORT:
        cmd.extend(["--variant", reasoning])
    return cmd


def launch(prompt, stdout, stderr, game_config, cmd):
    env = clean_env()
    env["OPENCODE_CONFIG_CONTENT"] = json.dumps(game_config)
    server = None
    if served(cmd[0]):
        server, url, session = start_server(cmd[0], env, stderr, wait_for_tools="mcp" in game_config)
        cmd = [*cmd[:2], "--server", url, "--session", session, *cmd[2:]]
    try:
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
    except BaseException:
        if server:
            stop_process(server)
        raise
    if server:
        stop_after(proc, server)
    return proc


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def api(exe, url, env, method, path, body=None):
    """One request to an OpenCode 2 server; its JSON answer, or None while the server cannot answer."""
    args = ["api", "--server", url, method, path]
    if body is not None:
        args += ["-d", json.dumps(body)]
    result = run_cli(args, env=env, exe=exe, timeout=60)
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def start_server(exe, env, stderr, *, wait_for_tools=True):
    """OpenCode 2's private server for one player, with its session ready: (server process, url, session id).

    It listens only on this computer, behind a random password that env now carries for run and api. The
    player starts once the game tools are connected: run --standalone sends the first model request before
    they are, so the model would begin without them.
    """
    env["OPENCODE_PASSWORD"] = secrets.token_urlsafe(24)
    port = free_port()
    url = f"http://127.0.0.1:{port}"
    server = subprocess.Popen(
        [exe, "serve", "--hostname", "127.0.0.1", "--port", str(port)],
        cwd=paths.PLAYER_CWD,
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=stderr,
        stderr=stderr,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    try:
        wait_for_server(exe, url, env, server, game_tools=wait_for_tools)
        body = {"title": SESSION_TITLE, "permissions": SESSION_PERMISSIONS}
        session = ((api(exe, url, env, "POST", "/api/session", body) or {}).get("data") or {}).get("id")
        if not session:
            raise RuntimeError("OpenCode's server did not open a game session")
    except BaseException:
        stop_process(server)
        raise
    return server, url, session


def wait_for_server(exe, url, env, server, *, game_tools):
    """Wait until the server answers and, for a game, has connected the game tools."""
    deadline = time.monotonic() + SERVER_START_SECONDS
    while True:
        if server.poll() is not None:
            raise RuntimeError(f"OpenCode's server stopped while starting (exit code {server.returncode})")
        answer = api(exe, url, env, "GET", "/api/mcp")
        if answer is not None and (
            not game_tools
            or any(
                item.get("name") == "mage" and (item.get("status") or {}).get("status") == "connected"
                for item in answer.get("data") or []
            )
        ):
            return
        if time.monotonic() > deadline:
            if game_tools:
                raise RuntimeError("OpenCode did not connect to the game tools within a minute")
            raise RuntimeError("OpenCode's server did not start within a minute")
        time.sleep(0.25)


def stop_process(proc):
    from llm import runtime  # noqa: PLC0415 - psutil is only needed once a server ran

    runtime.stop_tree(proc)


def stop_after(run, server):
    """Stop the private server once the player's run ends, however it ends. Python waits for this thread
    before it exits (it is not a daemon), so the server never outlives the seat."""

    def watch():
        run.wait()
        stop_process(server)

    threading.Thread(target=watch, name="opencode-server", daemon=False).start()


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


def error_data(events):
    """OpenCode's last error event's details (message, statusCode, isRetryable), or None.

    OpenCode 1 nests them under error.data. OpenCode 2 gives message, status and type (provider.auth,
    provider.quota, provider.rate-limit...) in error itself, after retrying what may pass.
    """
    errors = [event for event in events if event.get("type") == "error"]
    if not errors:
        return None
    error = errors[-1].get("error") or {}
    if "data" in error or not ("status" in error or "message" in error):
        return error.get("data") or {}
    return {"message": error.get("message"), "statusCode": error.get("status"), "type": error.get("type")}


def error_message(events):
    """The provider's own message from OpenCode's last error event, or None."""
    data = error_data(events)
    if data is None:
        return None
    return data.get("message") or "OpenCode reported an error"


# A missing model, a refused key or no credit fails the same way on every try; a busy model may not.
LASTING_STATUSES = (401, 402, 403, 404)


def lasting_error(events):
    data = error_data(events) or {}
    return data.get("statusCode") in LASTING_STATUSES and data.get("isRetryable") is not True


def with_hint(message, hint):
    return message.rstrip(". ") + ". " + hint


class MissingKeyError(RuntimeError):
    """No key is stored for a provider. The sign-in command is kept apart, so the app can offer it to copy."""

    def __init__(self, label, sign_in):
        self.message = f"No {label} key is saved for this app yet."
        self.command, self.shell = paths.sign_in_parts(sign_in)
        super().__init__(f"{self.message} Sign in with: {paths.sign_in_command(sign_in)}")


def read_key(provider, label, sign_in):
    """The stored key, used only for that provider's own requests; never returned to catalogs or logs."""
    if served():
        # OpenCode 2 keeps keys in its own database. When it first opened a home OpenCode 1 had used, it
        # imported that home's auth.json keys (its import_legacy_credentials step), so no new sign-in was needed.
        keys = [
            item["value"]["key"]
            for item in stored_credentials(provider)
            if item.get("active", True) and (item.get("value") or {}).get("type") == "key" and item["value"].get("key")
        ]
        if not keys:
            raise MissingKeyError(label, sign_in)
        return keys[0]
    entry = json.loads(AUTH_PATH.read_text(encoding="utf-8")).get(provider) if AUTH_PATH.is_file() else None
    if not entry or entry.get("type") != "api" or not entry.get("key"):
        raise MissingKeyError(label, sign_in)
    return entry["key"]


def stored_credentials(provider):
    """OpenCode 2's saved credentials for one provider, keys included; kept in memory, never printed."""
    result = run_cli(["auth", "export", provider, "--standalone"])
    try:
        items = json.loads(result.stdout) if result.returncode == 0 else []
    except json.JSONDecodeError:
        return []
    return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []


def has_credential(provider):
    """Whether OpenCode 2 has a sign-in saved for a provider, from its list of names (no key is read)."""
    result = run_cli(["auth", "list", "--standalone", "--format", "json"])
    try:
        listed = json.loads(result.stdout) if result.returncode == 0 else []
    except json.JSONDecodeError:
        return False
    if not isinstance(listed, list):
        return False
    return any(isinstance(item, dict) and item.get("id") == provider and item.get("connections") for item in listed)


def get_json(url, key=None, user_agent=None):
    headers = {"Accept": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    if user_agent:
        headers["User-Agent"] = user_agent
    request = urllib.request.Request(url, headers=headers)  # noqa: S310 - callers pass their provider's fixed https URL
    with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 - same fixed https URL
        return json.loads(response.read())


def read_efforts(provider):
    """Reasoning variants OpenCode knows for each of a provider's models, from its refreshed catalog."""
    if served():
        return parse_model_list(run_cli(["api", "--standalone", "GET", "/api/model"]).stdout, provider)
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


def parse_model_list(text, provider):
    """OpenCode 2's model list (GET /api/model): each of the provider's models with its variant names."""
    try:
        listed = json.loads(text).get("data") or []
    except (json.JSONDecodeError, AttributeError):
        return {}
    return {
        item["id"]: [variant["id"] for variant in item.get("variants") or [] if variant.get("id")]
        for item in listed
        if isinstance(item, dict) and item.get("providerID") == provider and item.get("id")
    }


def listed_playable(item, known_efforts):
    """A chat model with tools from an OpenCode service's list, that this OpenCode knows how to call."""
    capabilities = item.get("capabilities") or {}
    return (
        item.get("id") in known_efforts
        and item.get("type", "chat") == "chat"
        and (capabilities.get("tools") or {}).get("supported", True) is not False
    )


def listed_catalog(listing, known_efforts, *, provider, name, auth, preferred):
    """The current models of OpenCode's own services (Go, Zen) this OpenCode can play, with its effort levels."""
    offered = sorted(
        (item for item in listing.get("data", []) if listed_playable(item, known_efforts)),
        key=lambda item: (item.get("name") or item["id"]).lower(),
    )
    if not offered:
        raise RuntimeError(f"{name} lists no model this OpenCode version can play; update OpenCode")
    default = preferred if any(item["id"] == preferred for item in offered) else offered[0]["id"]
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
    return {"id": provider, "playerType": name, "playerName": name, "auth": auth, "models": models}


def validate_selection(catalog, model, reasoning, label):
    for item in catalog["models"]:
        if item["id"] == model:
            if reasoning not in item["efforts"]:
                raise ValueError(f"{model} does not support effort level {reasoning}")
            return
    raise ValueError(f"{model} is not in the {label} catalog; refresh the model list")
