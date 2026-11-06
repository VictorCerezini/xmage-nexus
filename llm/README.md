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
| OpenCode Go (`opencode_go`) | OpenCode | Go subscription key in the isolated OpenCode home (`python -m llm.sign_in opencode-go`) |
| OpenCode Zen (`opencode_zen`) | OpenCode | pay-as-you-go key in the isolated OpenCode home (`python -m llm.sign_in opencode-zen`); requests spend console credits |

No API key is read from or written to the repository. The supervisor came from
`VictorCerezini/mage-bench-windows` (`windows/`); the design and status are in
`docs/plans/2026-10-19-llm-seats-design.md`.

## Requirements

- Windows (the supervisor uses `CREATE_NO_WINDOW`).
- Python 3.11 or later and [uv](https://docs.astral.sh/uv/).
- Java 17 or later for the bridge.
- An XMage server built from the engine branch `llm-seats-engine` (short IDs,
  sequence counter, structured events, lazy card loading). The bridge does not
  compile against stock 1.4.63 artifacts.
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

## Desktop app (Windows)

The launcher's `llm` component (`win-x64` only) holds a standalone CPython 3.13
with psutil, this package without its tests, and the bridge's thin jar.
`node scripts/assemble-llm.mjs [stagingDir]` builds it into `<stagingDir>/llm`
from uv's managed CPython 3.13 and `llm/uv.lock`, after
`node scripts/build.mjs bridge`. The component's `llm.json` names its Python
and how the bridge starts: the thin jar first, then the server component's
`classes/Mage`, `classes/Mage.Common`, `classes/Mage.Sets` and `lib/*`, so the
83 MB shaded jar is not shipped twice.

When the release has the component, the launcher writes `data/llm/desktop.json`
(the bundled `java_home` and the server component's `server_dir`) and starts
the main proxy with `--llm on --llmRoot <component> --llmPython
<component>/python/python.exe --llmData <data>/llm`. The proxy passes that
folder to every supervisor process as `NEXUS_LLM_HOME`, and then:

- the catalog, the seats' run folders, the bridge's card database, the player
  sandbox and the isolated OpenCode home all live in it;
- the CLIs are looked up on every catalog refresh and every seat, the same way
  `llm.configure` looks them up, so installing one and pressing Refresh models
  is enough. A `settings.local.json` in that folder overrides a path the lookup
  found, and `desktop.json` always wins for Java and the server;
- a missing CLI is reported as "install it, sign in with ..., then refresh the
  model list", and a missing OpenRouter, Z.ai, OpenCode Go or OpenCode Zen key with the PowerShell line
  that runs `llm.sign_in` with the bundled Python and that folder.

The CLIs are not bundled: Codex, Claude Code and OpenCode come from the
machine. An older Codex lists fewer ChatGPT models (0.153 offers only
`gpt-6-astra`; 0.163 also offers `gpt-6-luna` and `gpt-6.1-sol`; 0.164 adds
`gpt-6.1-terra`).

## Commands

All run from the repository root with `llm/.venv/Scripts/python`.

- `-m llm.catalog [out.json]`: the signed-in providers with their models and
  reasoning levels. It reads each CLI's sign-in and model list and starts no
  model turn. Account identity and keys are never included. The proxy reruns
  it when its saved catalog is more than six hours old, since free models come
  and go, and keeps the old one if that run fails.
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

  Two optional fields pick the engine. `engine` is `bridge` by default.
  `engine: "mcp"` is a dev-only experiment arm: the supervisor starts
  `node mcp/src/index.ts` over stdio, logs in and joins the table through the
  proxy at `proxy_url` (`ws://127.0.0.1:<port>`, which Mage.Proxy fills in), and
  the model plays with `mcp/`'s own tools. It runs ChatGPT seats at two-player,
  single-game tables only, and needs Node.js 24 or later on `PATH` with
  `mcp/`'s dependencies installed. For this arm the table password goes to
  `mcp/` over its stdin. When the seat ends, the supervisor calls
  `mage_disconnect`, which ends the seat's proxy session and frees its XMage
  login at once instead of after the proxy's grace period.
- `-m llm.sign_in openrouter|zai|opencode-go|opencode-zen`: runs OpenCode's own key prompt with its home
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
- `problem-reports.jsonl` (same suffix), `bridge` engine only: what the model
  sent with `report_problem`. Each line has its text (`problem`), whether it
  came after the game ended, `all_worked` for an end-of-game `none`, and the
  last five game calls with what the model was shown, so a report can be
  checked against the logs. The file only exists once a report was sent.
- `mcp-server.log`: the `mcp/` server's stderr, for the `mcp` engine only. That
  engine has no bridge files; its results come from `mcp/`'s `GAME_OVER`.

Each game in the manifest records its `winner` (the name in the server's game
over message, or null for a draw), and for the `mcp` engine its last `turns`.
With the `bridge` engine it also records `problem_reports` (the problems the
model reported that game) and `said_all_worked` (it answered `none` to the
end-of-game question). The manifest's own `problem_reports` adds up every
game's, including a game that failed before it got an entry.

## Problem reports

With the `bridge` engine the model has a ninth tool, `report_problem`, that the
gateway answers itself: nothing reaches the bridge or the game, and the report
is only saved in the run folder. The prompt asks the model to use it as soon as
the game tools keep it from doing something legal (a missing choice, a choice
made for it, a refused action). When the bridge's result says the game is over
or the player was eliminated, the gateway adds a `bridge_check` question to it,
once: did the tools let the model do everything it wanted? The model answers
with `report_problem`, one call per problem or `none`, in the two minutes the
seat gives it after the game. A game cut short by a crash or a limit gets no
end-of-game answer, but reports sent during it are kept. A game takes up to 20
reports. Reports are leads to check in the logs: a model can blame the tools
for its own rules mistake.

The bridge's lazily loaded card database is shared by all seats in
`.run/llm/bridge/db`.

## Boundaries

- The CLI gets only the eight game tools and `report_problem`, through a
  loopback gateway that counts calls, auto-passes empty priority windows and
  drops unchanged boards
  and repeated rules text.
- With the `mcp` engine the eight tools are `mcp/`'s play tools
  (`mage_wait_for_prompt`, `mage_game_state`, `mage_choose`, `mage_play_card`,
  `mage_pay_mana`, `mage_combat`, `mage_pass_priority`, `mage_concede`). The
  gateway gives them English descriptions and hides their `session` argument;
  the supervisor calls the setup tools itself, and the model never sees
  `mcp/`'s stack, test or lobby tools. `mcp/` keeps its own auto-pass and
  compact view, so the gateway's board and rules trimming is off.
- Each CLI runs from an empty folder (`.run/llm/player-sandbox` unless
  configured), with built-in tools, project instructions and user settings off.
  Credential overrides are removed from its environment.
- Codex pre-approves only `concede` (`mage_concede` for the `mcp` engine),
  which the gateway marks destructive, and
  only through that run's `-c` overrides; the user's Codex config is not read
  or changed.
- A seat stops after 6,000 tool calls, after two hours per game, or on any
  non-game tool use.
- The bridge accepts a rollback request and a spectator's request to see its
  hand without asking the model, as a computer opponent would.

## Tests

```bash
cd llm
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

The tests use fake processes and never start a model or a server.
