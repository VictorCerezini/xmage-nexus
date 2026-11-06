"""Where the player CLIs are installed: PATH, npm's launchers and the Claude desktop app's own Claude Code."""

import os
import shutil
from pathlib import Path

CODEX_NPM_EXE = (
    Path("node_modules/@openai/codex/node_modules/@openai/codex-win32-x64")
    / "vendor/x86_64-pc-windows-msvc/bin/codex.exe"
)
OPENCODE_NPM_EXE = Path("node_modules/opencode-ai/bin/opencode.exe")
# OpenCode 2 moved to the @opencode/cli package.
OPENCODE2_NPM_EXE = Path("node_modules/@opencode/cli/bin/opencode.exe")


def native_executable(command, *npm_layouts):
    """The native executable behind a command on PATH; npm installs a launcher script that wraps it."""
    found = shutil.which(command)
    if not found:
        return None
    if Path(found).suffix.lower() == ".exe":
        return found
    candidates = (Path(found).parent / layout for layout in npm_layouts)
    return next((str(candidate) for candidate in candidates if candidate.is_file()), None)


def desktop_app_claude():
    """The Claude desktop app's bundled Claude Code, in a versioned folder that moves when the app updates."""
    if not os.environ.get("APPDATA"):
        return None
    bundled = sorted(
        Path(os.environ["APPDATA"]).glob("Claude/claude-code/*/*/claude.exe"), key=lambda p: p.stat().st_mtime
    )
    return str(bundled[-1]) if bundled else None


def find_claude():
    return shutil.which("claude.exe") or desktop_app_claude()


def detect():
    return {
        "codex_executable": native_executable("codex", CODEX_NPM_EXE),
        "claude_executable": find_claude(),
        "opencode_executable": native_executable("opencode", OPENCODE2_NPM_EXE, OPENCODE_NPM_EXE),
    }
