"""Repository paths and the ignored, machine-local settings for model seats."""

import json
import os
import sys
from pathlib import Path

from llm import executables

PACKAGE = Path(__file__).resolve().parent
REPO = PACKAGE.parent
DATA_HOME = Path(os.environ["NEXUS_LLM_HOME"]) if os.environ.get("NEXUS_LLM_HOME") else None
# Executable paths and optional folders, written by python -m llm.configure; never credentials.
SETTINGS_PATH = (DATA_HOME or PACKAGE) / "settings.local.json"
DESKTOP_PATH = (DATA_HOME or PACKAGE) / "desktop.json"
COMPONENT_PATH = REPO / "llm.json"
# Runtime state, ignored with the rest of .run/.
RUN_ROOT = DATA_HOME or REPO / ".run" / "llm"
# The bridge's user.home; each seat's lazily loaded card database stays in that seat's run folder.
BRIDGE_HOME = RUN_ROOT / "bridge"
BRIDGE_TARGET = REPO / "Mage.Client.Bridge" / "target"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def desktop():
    return DATA_HOME is not None


def settings():
    """The configured settings; a desktop install finds the installed CLIs on every call and uses its bundled Java."""
    if not desktop():
        if not SETTINGS_PATH.is_file():
            raise RuntimeError("Run python -m llm.configure before seating a model")
        return read_json(SETTINGS_PATH)
    values = executables.detect()
    if SETTINGS_PATH.is_file():
        values.update({key: value for key, value in read_json(SETTINGS_PATH).items() if value})
    if DESKTOP_PATH.is_file():
        values.update(read_json(DESKTOP_PATH))
    return values


def component():
    """The desktop component's description of its bridge, or None in a checkout."""
    return read_json(COMPONENT_PATH) if COMPONENT_PATH.is_file() else None


def missing_cli(cli, flag, sign_in=None):
    """What to do about a player CLI that is not installed, for a checkout or the desktop app."""
    if desktop():
        steps = f"install it, sign in with {sign_in}," if sign_in else "install it,"
        return f"{cli} is not installed: {steps} then refresh the model list"
    return f"Run python -m llm.configure with {cli} installed (or {flag}) before seating it"


def sign_in_command(provider):
    """How to store an OpenRouter or Z.ai key: the checkout's command, or a PowerShell line for the desktop app."""
    if not desktop():
        return f"python -m llm.sign_in {provider}"
    return (
        f"cd '{REPO}'; $env:NEXUS_LLM_HOME = '{DATA_HOME}'; & '{sys.executable}' -m llm.sign_in {provider} "
        "(in PowerShell)"
    )


def _configured_folder(key, default):
    # Read at import, so a missing settings file means the default rather than an error.
    if SETTINGS_PATH.is_file():
        value = read_json(SETTINGS_PATH).get(key)
        if value:
            return Path(value)
    return default


# The CLIs run from this empty folder, so no project instructions or config are near them.
PLAYER_CWD = _configured_folder("player_cwd", RUN_ROOT / "player-sandbox")
# OpenCode's isolated home with the OpenRouter and Z.ai keys; it can point at an existing one.
OPENCODE_HOME = _configured_folder("opencode_home", RUN_ROOT / "opencode-home")


def bridge_java_arguments():
    return [
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        # The card database's AUTO_SERVER listens here only, not on the LAN or a VPN address.
        "-Dh2.bindAddress=127.0.0.1",
        # Anything the client writes under the user's home stays in the run folder.
        f"-Duser.home={(BRIDGE_HOME / 'home').as_posix()}",
    ]
