"""Repository paths and the ignored, machine-local settings for model seats."""

import json
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent
REPO = PACKAGE.parent
# Executable paths and optional folders, written by python -m llm.configure; never credentials.
SETTINGS_PATH = PACKAGE / "settings.local.json"
# Runtime state, ignored with the rest of .run/.
RUN_ROOT = REPO / ".run" / "llm"
# The bridge keeps its lazily loaded card database in db/ here; H2's AUTO_SERVER lets seats share it.
BRIDGE_HOME = RUN_ROOT / "bridge"
BRIDGE_TARGET = REPO / "Mage.Client.Bridge" / "target"


def settings():
    if not SETTINGS_PATH.is_file():
        raise RuntimeError("Run python -m llm.configure before seating a model")
    return json.loads(SETTINGS_PATH.read_text(encoding="utf-8-sig"))


def _configured_folder(key, default):
    # Read at import, so a missing settings file means the default rather than an error.
    if SETTINGS_PATH.is_file():
        value = json.loads(SETTINGS_PATH.read_text(encoding="utf-8-sig")).get(key)
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
        # Anything the client writes under the user's home stays in the run folder.
        f"-Duser.home={(BRIDGE_HOME / 'home').as_posix()}",
    ]
