"""Store an OpenRouter or Z.ai key in OpenCode's isolated home for model seats.

python -m llm.sign_in openrouter|zai runs OpenCode's own key prompt with its home redirected to
paths.OPENCODE_HOME, so your normal OpenCode setup and the Claude and ChatGPT sign-ins are never read or
changed, and the key never passes through this program. ChatGPT and Claude seats use the CLIs' own
sign-ins: codex login, and claude auth login --claudeai.
"""

import json
import subprocess
import sys

from llm import opencode_client, paths

PROVIDERS = {
    "openrouter": ("openrouter", "OpenRouter", "https://openrouter.ai/settings/keys"),
    # The GLM Coding Plan key, not a pay-as-you-go Z.ai API key: only the plan endpoint is used.
    "zai": ("zai-coding-plan", "the Z.ai GLM Coding Plan", "https://z.ai/manage-apikey/apikey-list"),
}


def signed_in(provider):
    # Only provider names are read; the stored key is never printed.
    if not opencode_client.AUTH_PATH.is_file():
        return False
    return provider in json.loads(opencode_client.AUTH_PATH.read_text(encoding="utf-8"))


def main(argv):
    if len(argv) != 1 or argv[0] not in PROVIDERS:
        print("Usage: python -m llm.sign_in openrouter|zai", file=sys.stderr)
        return 2
    provider, label, key_page = PROVIDERS[argv[0]]
    if signed_in(provider):
        print(f"Model seats are already signed into {label} ({opencode_client.OPENCODE_HOME}).")
        return 0
    print(f"Create an API key at {key_page}, then paste it when OpenCode asks.")
    print(f"The key is stored only in {opencode_client.OPENCODE_HOME}.")
    paths.PLAYER_CWD.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [opencode_client.executable(), "auth", "login", "--provider", provider],
        cwd=paths.PLAYER_CWD,
        env=opencode_client.clean_env(),
        check=False,
    )
    if signed_in(provider):
        print(f"Signed into {label}.")
        return 0
    print(f"{label} sign-in did not complete.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
