"""Write llm/settings.local.json: where Java and the player CLIs are. It never holds credentials.

python -m llm.configure [--codex PATH] [--claude PATH] [--opencode PATH] [--java-home PATH]
                        [--opencode-home PATH] [--player-cwd PATH] [--from-mage-bench REPO]

CLIs not given are looked up on PATH, and Claude Code also in the Claude desktop app. --from-mage-bench
takes a mage-bench-windows checkout's executables and its isolated OpenCode sign-in folder, so nothing
has to be signed in again and no key is copied. Each CLI keeps its own sign-in: codex login,
claude auth login --claudeai, and python -m llm.sign_in for OpenRouter and Z.ai.
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from llm import paths

# npm installs a launcher script; the players need the native executable it wraps.
CODEX_NPM_EXE = (
    Path("node_modules/@openai/codex/node_modules/@openai/codex-win32-x64")
    / "vendor/x86_64-pc-windows-msvc/bin/codex.exe"
)
OPENCODE_NPM_EXE = Path("node_modules/opencode-ai/bin/opencode.exe")


def native_executable(command, npm_layout):
    found = shutil.which(command)
    if not found:
        return None
    if Path(found).suffix.lower() == ".exe":
        return found
    candidate = Path(found).parent / npm_layout
    return str(candidate) if candidate.is_file() else None


def find_claude():
    found = shutil.which("claude.exe")
    if found:
        return found
    if not os.environ.get("APPDATA"):
        return None
    # The desktop app bundles Claude Code in a versioned folder that moves when the app updates.
    bundled = sorted(
        Path(os.environ["APPDATA"]).glob("Claude/claude-code/*/*/claude.exe"), key=lambda p: p.stat().st_mtime
    )
    if bundled:
        print(f"Using the Claude desktop app's Claude Code ({bundled[-1]}); configure again after the app updates.")
        return str(bundled[-1])
    return None


def from_mage_bench(repo):
    local = Path(repo) / ".windows-local"
    source = json.loads((local / "settings.json").read_text(encoding="utf-8-sig"))
    values = {key: source.get(key) for key in ("codex_executable", "claude_executable", "opencode_executable")}
    if (local / "opencode-home").is_dir():
        values["opencode_home"] = str(local / "opencode-home")
    return values


def resolve(args):
    values = {
        "codex_executable": native_executable("codex", CODEX_NPM_EXE),
        "claude_executable": find_claude(),
        "opencode_executable": native_executable("opencode", OPENCODE_NPM_EXE),
        "java_home": None,
        "opencode_home": None,
        "player_cwd": None,
    }
    if args.from_mage_bench:
        values.update({key: value for key, value in from_mage_bench(args.from_mage_bench).items() if value})
    given = {
        "codex_executable": args.codex,
        "claude_executable": args.claude,
        "opencode_executable": args.opencode,
        "java_home": args.java_home,
        "opencode_home": args.opencode_home,
        "player_cwd": args.player_cwd,
    }
    values.update({key: value for key, value in given.items() if value})
    for key in ("codex_executable", "claude_executable", "opencode_executable"):
        if values[key]:
            if not Path(values[key]).is_file():
                raise ValueError(f"{key} does not exist: {values[key]}")
            values[key] = str(Path(values[key]).resolve())
    if not any(values[key] for key in ("codex_executable", "claude_executable", "opencode_executable")):
        raise ValueError("Install the Codex CLI, Claude Code or OpenCode, or pass --codex / --claude / --opencode")
    if values["java_home"] and not (Path(values["java_home"]) / "bin").is_dir():
        raise ValueError(f"java_home has no bin folder: {values['java_home']}")
    for key in ("java_home", "opencode_home", "player_cwd"):
        if values[key]:
            values[key] = str(Path(values[key]).resolve())
    return values


def main(argv):
    parser = argparse.ArgumentParser(prog="python -m llm.configure", description=__doc__.splitlines()[0])
    parser.add_argument("--codex", help="native codex.exe")
    parser.add_argument("--claude", help="claude.exe")
    parser.add_argument("--opencode", help="native opencode.exe")
    parser.add_argument("--java-home", help="JDK 17+ for the bridge (default: JAVA_HOME, then java on PATH)")
    parser.add_argument("--opencode-home", help="OpenCode's isolated home with the OpenRouter/Z.ai keys")
    parser.add_argument("--player-cwd", help="empty working folder for the player CLIs")
    parser.add_argument("--from-mage-bench", metavar="REPO", help="reuse a mage-bench-windows checkout's setup")
    args = parser.parse_args(argv)
    try:
        values = resolve(args)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    paths.SETTINGS_PATH.write_text(json.dumps(values, indent=2) + "\n", encoding="utf-8")
    Path(values["player_cwd"] or paths.RUN_ROOT / "player-sandbox").mkdir(parents=True, exist_ok=True)
    for key, value in values.items():
        print(f"{key}: {value or '-'}")
    print(f"Wrote {paths.SETTINGS_PATH}. No credentials were read or copied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
