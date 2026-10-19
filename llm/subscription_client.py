"""Codex CLI player using its existing ChatGPT login; no provider API keys."""

import json
import os
import subprocess

from llm import paths

PLAYER_CWD = paths.PLAYER_CWD
MODEL = "gpt-6-astra"
TOOLS = [
    "get_game_state",
    "get_action_choices",
    "choose_action",
    "pass_priority",
    "get_oracle_text",
    "get_game_log",
    "get_game_history",
    "concede",
]


def clean_env():
    env = os.environ.copy()
    for key in list(env):
        if key in {
            "OPENAI_API_KEY",
            "CODEX_API_KEY",
            "CODEX_ACCESS_TOKEN",
            "ANTHROPIC_API_KEY",
            "GEMINI_API_KEY",
            "GOOGLE_API_KEY",
            "OPENROUTER_API_KEY",
            "OPENAI_BASE_URL",
        }:
            env.pop(key)
    return env


def command(mcp_url=None, *, model=MODEL, reasoning="medium", tool_timeout_seconds=180):
    codex = paths.settings()["codex_executable"]
    if not codex:
        raise RuntimeError("Run python -m llm.configure with the native Codex executable before ChatGPT play")
    cmd = [
        str(codex),
        "-a",
        "never",
        "exec",
        "--ignore-user-config",
        "--ephemeral",
        "--json",
        "--skip-git-repo-check",
        "--sandbox",
        "read-only",
        "--model",
        model,
        "--cd",
        str(PLAYER_CWD),
    ]
    configs = {
        "forced_login_method": "chatgpt",
        "model_provider": "openai",
        "model_reasoning_effort": reasoning,
        "web_search": "disabled",
        "project_doc_max_bytes": 0,
        "history.persistence": "none",
        "features.shell_tool": False,
        "features.apps": False,
        "features.plugins": False,
        "features.browser_use": False,
        "features.browser_use_external": False,
        "features.computer_use": False,
        "features.in_app_browser": False,
        "features.view_image": False,
        "features.image_generation": False,
        "features.multi_agent": False,
        "features.memories": False,
        "features.code_mode_host": True,
        "features.skip_host_skill_discovery": True,
        # Coding-agent prompt sections the game player cannot use; each is resent with every request.
        "skills.include_instructions": False,
        "agents.enabled": False,
        "include_collaboration_mode_instructions": False,
        "include_permissions_instructions": False,
        "include_environment_context": False,
    }
    if mcp_url:
        configs.update(
            {
                # Called through code mode's JavaScript cell, every result reached the model twice and a
                # long human turn turned into 30-second polls that cut the board short.
                "features.code_mode.direct_only_tool_namespaces": ["mcp__mage"],
                "mcp_servers.mage.url": mcp_url,
                "mcp_servers.mage.required": True,
                "mcp_servers.mage.enabled_tools": TOOLS,
                "mcp_servers.mage.tool_timeout_sec": tool_timeout_seconds,
                "mcp_servers.mage.startup_timeout_sec": 30,
                "mcp_servers.mage.default_tools_approval_mode": "auto",
            }
        )
    for key, value in configs.items():
        cmd.extend(["-c", key + "=" + json.dumps(value)])
    cmd.append("-")
    return cmd


def start(prompt, stdout, stderr, mcp_url=None, *, model=MODEL, reasoning="medium", tool_timeout_seconds=180):
    proc = subprocess.Popen(
        command(mcp_url, model=model, reasoning=reasoning, tool_timeout_seconds=tool_timeout_seconds),
        cwd=PLAYER_CWD,
        env=clean_env(),
        stdin=subprocess.PIPE,
        stdout=stdout,
        stderr=stderr,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    proc.stdin.write(prompt.encode("utf-8"))
    proc.stdin.close()
    return proc


if __name__ == "__main__":
    folder = paths.RUN_ROOT / "preflight/codex"
    folder.mkdir(parents=True, exist_ok=True)
    with (
        (folder / "codex-events.jsonl").open("w", encoding="utf-8") as out,
        (folder / "codex-stderr.log").open("w", encoding="utf-8") as err,
    ):
        proc = start(
            "This is a subscription connection test. Do not use tools. Reply exactly SUBSCRIPTION_READY.", out, err
        )
        result = proc.wait(timeout=120)
    print(f"Codex connection test exit code: {result}")
    raise SystemExit(result)
