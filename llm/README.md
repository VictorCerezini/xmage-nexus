# llm — model seats

`llm/` seats a language model at an XMage table. Each seat runs its own
`Mage.Client.Bridge` (a headless XMage client that joins the seat and serves
the game as MCP tools on loopback) and starts one CLI player per game:

| Provider | CLI | Sign-in |
|---|---|---|
| ChatGPT (`chatgpt_subscription`) | Codex CLI | your Codex ChatGPT login (`codex login`) |
| Claude (`claude_subscription`) | Claude Code | your Claude subscription login (`claude auth login --claudeai`) |
| OpenRouter free models (`openrouter_free`) | OpenCode | key in the isolated OpenCode home (`python -m llm.sign_in openrouter`) |
| Z.ai GLM Coding Plan (`zai_coding_plan`) | OpenCode | key in the isolated OpenCode home (`python -m llm.sign_in zai`) |

No API key is read from or written to the repository. The supervisor came from
`VictorCerezini/mage-bench-windows` (`windows/`); the design and status are in
`docs/plans/2026-10-19-llm-seats-design.md`.

## Requirements

- Windows (the supervisor uses `CREATE_NO_WINDOW`).
- Python 3.11 or later and [uv](https://docs.astral.sh/uv/).
- Java 17 or later for the bridge.
- An XMage server built from the engine branch `llm-seats-engine` (short IDs,
  sequence counter, structured events, lazy card loading). The bridge does not
  compile against stock 1.4.62 artifacts.
- At least one of the three CLIs, signed in.

## Setup

From the repository root:

```bash
uv sync --project llm
node scripts/build.mjs bridge
llm/.venv/Scripts/python -m llm.configure
```

`llm.configure` writes `llm/settings.local.json` (ignored): the CLI executables,
and optionally `java_home`, `opencode_home` and `player_cwd`. It never reads or
copies a credential. CLIs not given with `--codex`, `--claude` or `--opencode`
are looked up on `PATH`, and Claude Code also in the Claude desktop app.

`--from-mage-bench <checkout>` reuses a mage-bench-windows setup: its
executables and its isolated OpenCode home, so OpenRouter and Z.ai need no new
sign-in and no key is copied.

Then `node scripts/play-vs-llm.mjs` starts the dev stack with model seats on
and opens the web client, where Create Table offers them (the root README's
"Variant C"). `node scripts/ctl.mjs start|restart [target] --llm` starts the
proxy the same way, and stops early if the Python environment or the bridge jar
is missing.

## Commands

All run from the repository root with `llm/.venv/Scripts/python`.

- `-m llm.catalog [out.json]`: the signed-in providers with their models and
  reasoning levels. It reads each CLI's sign-in and model list and starts no
  model turn. Account identity and keys are never included.
- `-m llm.seat`: seats one model. The seat request JSON comes on stdin:

  ```json
  {
    "provider": "chatgpt_subscription",
    "model": "gpt-6-luna",
    "reasoning": "low",
    "catalog": ".run/llm/catalog.json",
    "table_id": "<table UUID>",
    "table_password": "",
    "seat": 2,
    "username": "ChatGPT 2",
    "server_port": 17171,
    "deck": "<path to a .dck file>",
    "game_type": "Two Player Duel",
    "deck_type": "Constructed - Freeform Unlimited",
    "player_count": 2,
    "wins_needed": 1,
    "owner_pid": 1234,
    "run": ".run/llm/<table>-seat-2"
  }
  ```

  The seat stops when the `owner_pid` process exits or when a
  `cancel.requested` file appears in the run folder. It exits 0 for a finished
  or cancelled seat and 1 for a failure. The server username limit is 14
  characters. `table_password` is optional; it reaches only the bridge, in its
  `XMAGE_BRIDGE_TABLE_PASSWORD` environment variable, and is never written to
  the manifest, a log or a command line. Mage.Proxy starts these seats for
  `createTable` with `llmSeats` when run with `--llm on`.
- `-m llm.sign_in openrouter|zai`: runs OpenCode's own key prompt with its home
  redirected to the isolated OpenCode home.
- `-m llm.subscription_client`, `-m llm.claude_client`: one-shot connection
  checks that ask the model for a fixed reply without game tools.

## Run folder

Each seat writes to its `run` folder:

- `manifest.json`: status (`joining`, `waiting_for_start`, `playing`,
  `waiting_for_next_game`, `completed`, `cancelled`, `failed`), each game's
  result, tool calls, token usage and whether cleanup finished.
- `bridge.log`, `bridge.jsonl` and `bridge-errors.log`: the bridge's console,
  structured log and errors. The supervisor reads game starts and results from
  `bridge.log`.
- `player-prompt.txt`, `player-tools.jsonl` and the CLI's event and stderr
  logs, with a `-game<N>` suffix from game 2 of a match.

The bridge's lazily loaded card database is shared by all seats in
`.run/llm/bridge/db`.

## Boundaries

- The CLI gets only the eight game tools, through a loopback gateway that
  counts calls, auto-passes empty priority windows and drops unchanged boards
  and repeated rules text.
- Each CLI runs from an empty folder (`.run/llm/player-sandbox` unless
  configured), with built-in tools, project instructions and user settings off.
  Credential overrides are removed from its environment.
- Codex pre-approves only `concede`, which the gateway marks destructive, and
  only through that run's `-c` overrides; the user's Codex config is not read
  or changed.
- A seat stops after 2,000 tool calls, after two hours per game, or on any
  non-game tool use.

## Tests

```bash
cd llm
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

The tests use fake processes and never start a model or a server.
