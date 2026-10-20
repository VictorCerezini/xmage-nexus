"""Where the player CLIs are installed: PATH, npm's launchers and the Claude desktop app's own Claude Code."""

import os
import shutil
from pathlib import Path

CODEX_NPM_EXE = (
    Path("node_modules/@openai/codex/node_modules/@openai/codex-win32-x64")
    / "vendor/x86_64-pc-windows-msvc/bin/codex.exe"
)
OPENCODE_NPM_EXE = Path("node_modules/opencode-ai/bin/opencode.exe")


def native_executable(command, npm_layout):
    """The native executable behind a command on PATH; npm installs a launcher script that wraps it."""
    found = shutil.which(command)
    if not found:
        return None
    if Path(found).suffix.lower() == ".exe":
        return found
    candidate = Path(found).parent / npm_layout
    return str(candidate) if candidate.is_file() else None


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
        "opencode_executable": native_executable("opencode", OPENCODE_NPM_EXE),
    }
