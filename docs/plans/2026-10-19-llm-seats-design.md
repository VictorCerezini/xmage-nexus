# LLM seats: play against ChatGPT, Claude, OpenRouter and GLM from the Nexus web client

Date: 2026-10-19. Status: phases 0 to 3 done. With `--llm on` the proxy seats a
model itself from `createTable`. The ChatGPT concede refusal found in the phase 3
play proof is fixed (see "Risks and checks"). Next is phase 4, the web UI.

## Goal

Bring the model opponents built in `VictorCerezini/mage-bench-windows` (Swing
client) to Nexus: a table owner picks a model seat in Create Table, chooses
provider, model, reasoning level and deck, and plays it in the browser. Keep
everything that version has:

- **Providers**, each using the user's own sign-in, never an API key in the
  repo:
  - ChatGPT through Codex CLI.
  - Claude through Claude Code.
  - OpenRouter free models through OpenCode.
  - Z.ai GLM through OpenCode.
- **Formats:** 1v1, Commander and multiplayer with several model seats, and
  best-of-three and best-of-five, with one fresh agent per game and the deck
  unchanged between games.
- **Cost controls:**
  - Auto-pass empty priority windows.
  - Card rules sent once, with a refresh each turn.
  - Reuse of unchanged boards.
  - Direct game tools for Codex.
  - A trimmed prompt.
- **Safety boundaries:**
  - The agent gets only the eight game tools, through a loopback gateway.
  - Each CLI runs from an isolated home.
  - Credential overrides are removed from the child environment.
  - Tool and call limits, plus a two-hour cap.
  - The seat stops when its owner goes away.
- **Records:** a per-seat manifest with usage, per-game logs and the result.

## What Nexus is

- **`master`:**
  - The web client (`web/`, React 19).
  - The JSON/WebSocket proxy (`Mage.Proxy/`, Java 17, multi-tenant, binds
    127.0.0.1 by default).
  - A Node MCP server (`mcp/`) for dev agents, including a headless player.
  - The Tauri desktop launcher (`launcher/`).
  - The scripts in `scripts/`.
- **The engine:** XMage 1.4.62-V1 on branch `nexus`, plus three small
  patches. It is expected checked out at `../xmage-fork`.

Humans play in the browser. Native bots are the server's `COMPUTER_*` seats.
The proxy's own `SIM` bot logs in as its own client and fills a seat. It is
dev-only, off unless the proxy runs with `--sim on`. That is the same shape as
a model seat.

## Base: the latest release, not master

Work happens on a branch cut from release tag **`v0.4.7`** (`850411ec`, 2026-10-14):

- All of its CI passed: web, mcp, proxy and the Tauri release build.
- Master is one commit ahead (`9a2c35bd`, public ranking, #16), and that commit
  fails Web client CI (`src/demo/data.test.ts`, demo roster tiers).

The engine is the `nexus` branch at `825d1205`, which is XMage's stable
1.4.62-V1 plus Nexus's patches. That branch has not moved since 2026-10-04, so
it is also what v0.4.7 ships. Its worktree is pinned to that commit.

Upgrades go release to release: merge the next `v0.x.y` tag into our branch
once its CI is green. We don't track master day to day. Our fork's `master`
stays an untouched mirror of upstream.

## Which engine drives the model

**Recommended: keep the mage-bench Java bridge.** It is
`Mage.Client.Bridge`, about 12.4k lines plus our match support.

- The models play well with its tools today.
- All of our cost work (`game_tool_gateway.py`, `player_view.py`) is written
  against its tool contract.
- Its Maven dependencies are only `mage`, `mage-common` and `mage-sets`.
  However, it needs mage-bench's own engine changes, which stock XMage does
  not have (see the next section).

**Alternative: Nexus's `mcp/` headless player.**

- It is about 2.4k lines of TypeScript.
- It has its own compact view, auto-pass and tournament tools.
- It speaks the proxy protocol, so it needs no second Java client.
- But it was built and verified as a dev-agent harness against the SIM bot.
  Moving to it means rewriting our gateway policies and prompts against
  different tools, with a real risk of weaker play.

Possible later experiment: an A/B test that drives `mcp/` from the same
supervisor.

## Phase 1 finding: the bridge needs engine patches

mage-bench's README says its XMage server is unmodified. That is not true.
Its engine is XMage 1.4.61V1 plus 64 changed or added files in `Mage`,
`Mage.Common`, `Mage.Server` and the human player plugin.

- **Compile result.** Built against Nexus's stock 1.4.62 artifacts, the bridge
  fails to compile. The Java 17 backport of its two Java 21 spots (virtual
  threads and `getFirst()`) is done and is not the problem.
- **What the bridge uses from those patches:**
  - Server-side short IDs (`ShortIdRegistry`).
  - A per-game sequence counter.
  - Structured game events (`BridgeLogEntry`), attached to callbacks.
  - View fields: lands played and lands per turn on `PlayerView`, the
    controller on `CardView` and `SimpleCardView`, and the extra `GameView`
    data.
  - Mana ability names in `PlayableObjectStats`.
  - Connection retry and timeout settings.
  - Lazy card loading in `CardRepository`, so the bridge starts without a full
    card scan.
- **What it does not need:** the rest of the patch set, such as data
  collectors, watch results, server-side session changes for the Swing GUI,
  the jumpstart list and log settings.

Two ways forward:

- **A. Patch Nexus's engine (recommended for the proof).**
  - Port only the bridge-support subset, about 1,000 lines, onto `nexus` as
    our own branch or patch set.
  - Write it as Java 8, because the engine compiles at release 8.
  - Pros: the bridge stays identical, so model play quality stays as it is.
  - Cons:
    - We carry an engine patch for every XMage release Nexus adopts.
    - The desktop launcher's bundled server would need the patched engine.
    - Nexus's web contract guards must model the new view fields, or ignore
      them.
- **B. Adapt the bridge to a stock server.**
  - Assign short IDs on the client.
  - Take events from game-log text.
  - Do a full card scan on first run.
  - Infer lands played, mana ability names and stack controllers, or drop
    them.
  - Pros: no engine patch, so any stock Nexus server works.
  - Cons: more bridge work, and the risk of weaker play where the inferred
    data is worse.

A can come first and B later, once the feature works end to end.

**Decision: A.** The engine patch is commit `5a8f9f71` on branch
`llm-seats-engine` in `../xmage-fork`, cut from `825d1205`. It changes 15
files and adds 2, about 1,070 lines.

- **Ported:**
  - `BridgeLogEntry` and `ShortIdRegistry`. The record became a Java 8 class
    with the same accessor names.
  - `Game`/`GameImpl`: the sequence counter, the short ID registry, and the
    event buffer fed from `fireEvent`.
    - The sequence advances on every game log line and every decision point.
    - The counter and registry are created lazily too, so deserialized games
      such as replays still work.
  - `GameView`: short ID assignment and `gameSeq`. Its trace log is now at
    debug level.
  - `PlayerView`: lands played and lands per turn, plus `shortId`.
  - `SimpleCardView`: `shortId`.
  - `PlayableObjectStats`: mana ability names.
  - `ClientCallback`: `bridgeEvents`.
  - `Connection`: retry and timeout setters.
  - Lazy card loading in `CardRepository`/`CardScanner`.
  - Server side:
    - `GameSessionWatcher`/`GameSessionPlayer` attach events to game
      callbacks.
    - `GameController` advances the sequence per decision and gives target
      and pile lists short IDs.
- **Already in Nexus, so not ported:**
  - Nexus's engine already sends `controllerId`/`controllerName` on stack
    and battlefield views. The bridge reads `controllerName` instead (a
    one-line bridge change).
  - Upstream 1.4.62 already fixed the client-side callback race in
    `SessionImpl` and caches views for calls off the game thread, so
    mage-bench's game snapshot machinery is not needed.
- **Left out:**
  - The server-side game log collector and the data collector API changes.
  - Watch results and observer support.
  - The H2 open retry.
  - Deterministic map ordering.
  - Golden-test hooks: set restriction, fixed shuffle seed, choosing player.
  - mage-bench's extra server fixes: the callback executor in `Session`,
    the response-window race in `HumanPlayer`, and `informOthers` ordering.
    Add these only if model seats show stalls.
- **Wire compatibility:**
  - `ClientCallback`, `SimpleCardView` and `PlayableObjectStats` had no
    `serialVersionUID`. They are pinned to the values computed for stock
    1.4.62, so stock clients and the stock Nexus proxy still deserialize the
    patched server's objects.
  - `node scripts/test.mjs human-test` passes with the stock-built proxy
    against the patched server.
  - A proxy rebuilt from the patched artifacts would serialize the new view
    fields to the web client. Either keep its JSON output unchanged or model
    the fields in the web schemas.
- **Bridge changes for Nexus:**
  - The Java 17 backport.
  - The `controllerName` read.
  - Its log4j console layout is now log4j's stock `PatternLayout`.
    mage-bench's `MicrosecondPatternLayout` is engine-only, and without it the
    console log was empty. The supervisor parses that log for game results.
  - A `Build-Time` manifest entry.
  - The test deck fixture moved into the module's test resources.
  - A cold-start wait in one test was widened.
  - All 113 bridge tests pass.

## Where each piece goes

| mage-bench-windows piece | Nexus home | Work |
|---|---|---|
| `Mage.Client.Bridge` (and its 113 tests) | New top-level `Mage.Client.Bridge/`. It builds standalone like `Mage.Proxy`, against the `org.mage:1.4.62` artifacts in `~/.m2`, as a shaded jar run with `java -jar` instead of `mvn exec:java`. `node scripts/build.mjs bridge` packages it. The full build leaves it out, because it compiles only against the patched engine. It needs the engine patch from "Decision: A". | Medium |
| `windows/` supervisor:<br>• `gui_player`, `game_tool_gateway`, `player_view`<br>• the provider clients<br>• `model_catalog`, `providers`, `mcp_client`, `jsonl`, `paths` | New `llm/` Python package. It needs Python 3.11+ and `psutil`, managed with uv. The request JSON is unchanged; only the bridge launch and paths change. The 153 tests move with it. | Medium |
| Setup and sign-in scripts | `llm/` modules (`llm.configure`, `llm.sign_in`). A gitignored local config can point at the existing isolated CLI homes in mage-bench-windows, so nothing has to be signed in again and no credential is copied. | Low |
| Swing UI:<br>• `NewTableDialog` and `TablePlayerPanel` (seat type, model, effort, deck)<br>• `SubscriptionSupport` (validate, start, watch, error dialog, stop)<br>• `SubscriptionPlayerRegistry` | **Proxy**, new `LlmSeats` modelled on `SimManager`, opt-in with `--llm <catalog>`. **Web**, a model seat type in `SeatsTab`. Details below. | Medium to high (the main new work) |
| Swing launch pieces:<br>• `HumanGui`, `MatchGui`, `IsolatedPreferencesFactory`<br>• `gui_session`<br>• the Swing half of `subscription_runner` | Not needed: the human plays in the browser. | None |
| `Play-vs-LLM.cmd` | A wrapper that starts server, proxy (with `--llm`) and web, then opens the browser. | Low |

### Proxy: `LlmSeats`

- **Off by default.** It refuses to start unless the proxy binds loopback and
  the target XMage server is loopback. A hosted multi-tenant proxy must never be
  able to spend the host's subscriptions.
- **`getLlmProviders`** returns the catalog: providers, models and reasoning
  levels. It never returns credentials.
- **`createTable`** accepts `llmSeats: [{seat, provider, model, effort, deck}]`.
  - Each seat is a `HUMAN` seat on the server.
  - The proxy writes the seat's deck as a `.dck` file in the run folder.
  - It spawns `python -m llm.seat` with the same request JSON the Swing client
    sends, plus the table password, which lifts the empty-password rule.
- **Seat status** (starting, joined, playing, waiting for next game, finished,
  stopped, failed with reason) goes out as events. Failures reuse the proxy's
  existing non-fatal `error` broadcast.
- **Stopping:** seats stop on `removeTable` or `leaveTable` by the owner, when
  the owner's session expires, and when the proxy exits. The supervisor already
  stops when its owner PID dies, and the owner becomes the proxy.
- **Only the account that created the table** can start or stop its model
  seats.

### Web

- **Seat picker:** a model seat type in `SeatsTab` (ChatGPT, Claude,
  OpenRouter, GLM), shown only when the proxy reports LLM seats enabled. Each
  seat gets model, reasoning and deck pickers.
- **Staging screen:** shows the seat's status, for example "ChatGPT 2 is
  joining…" or a failure reason.
- **Translations:** strings in all 9 locales, as the i18n coverage test
  requires.
- **Protocol:** document the new action and event in `Mage.Proxy/README.md`,
  regenerate the types and zod schemas, and add a FakeServer scenario with
  vitest and fake Playwright coverage.
- **Merge conflicts:** keep the changes small. Upstream has a 452-file
  create-table wizard redesign on `feat/create-table-wizard-redesign`.

## Phases

0. **Toolchain (no code). Done.**
   - Work branch `llm-seats` created from `v0.4.7`.
   - `nexus` at `825d1205` checked out as `../xmage-fork`, built and installed
     to the local Maven repository (`~/.m2`).
   - Plugins, proxy jar and web dependencies built.
   - `node scripts/test.mjs human-test` passes against the live stack. It
     logs in, mulligans, plays a land, casts spells with targets and sees
     them resolve against a server AI.
1. **Bridge on 1.4.62. Done.**
   - Decision A, done as described above. The module builds and all 113
     bridge tests pass.
   - **Proof game:**
     - A script logged in through the Nexus proxy as the table owner and
       created a Two Player Duel. Seat 1 was Mad; seat 2 was an open `HUMAN`
       seat. Both players used Mirror Bears.
     - The unchanged mage-bench-windows supervisor (`gui_player.run_player`)
       ran with only the bridge launch swapped for `java -jar` on the new jar.
       It seated ChatGPT `gpt-6-luna` at low reasoning.
     - The model joined, kept its hand and won in 99 s. That took 41 tool
       calls, no failed calls and 35 auto-passes. It used about 1.05M input
       tokens (0.92M cached) and 880 output tokens.
     - The manifest says `completed` and `game_over`, and cleanup finished.
     - The bridge's game ID matches the server's `GAME started` line for the
       table. The bridge logged no errors and no stale-game warnings.
     - The sequence counter reached 883. Cards carry server-assigned `p` IDs.
   - **Event check without a model:**
     - The model never called `get_game_history`, so a scripted seat that
       declines everything played a second table against Mad.
     - `get_game_history` returned 45 events: draws, land plays, casts and
       life loss, grouped by turn and step.
     - The seat's own draws name the card. Mad's draws only say "drew a
       card", so private events are redacted.
   - The web UI wasn't used. Phases 3 and 4 add model seats to it.
2. **Supervisor in `llm/`. Done.**
   - `llm/` is a uv project (Python 3.11+, `psutil`) run from the repository
     root as `python -m llm.<module>`. `llm/README.md` covers setup, the seat
     request and the run folder.
   - `gui_player` became `llm.seat`, which reads the seat request from stdin.
     The gateway, player view and provider clients moved over with only import,
     path and setup-message changes. The model-facing prompt and tool texts are
     unchanged.
   - `llm.runtime` launches the bridge as `java -jar` on the shaded jar. Java
     comes from `java_home`, then `JAVA_HOME`, then `PATH`. The isolated Java
     preferences jar is gone: nothing on the bridge's classpath uses
     `java.util.prefs`.
   - New modules: `llm.catalog` (providers and models, no model turn),
     `llm.configure` (writes the ignored `llm/settings.local.json`; with
     `--from-mage-bench` it reuses mage-bench's executables and isolated
     OpenCode home) and `llm.sign_in` (OpenRouter and Z.ai keys into that
     home).
   - `node scripts/build.mjs bridge` packages the bridge with `clean package`.
     Without the clean, shade wrapped the previous shaded jar again.
   - Tests: the 153 ported tests and 15 new ones pass (168), and ruff is clean.
   - **Proof game through `llm.seat`:**
     - Request JSON on stdin, Nexus proxy table, ChatGPT `gpt-6-luna` at low
       reasoning against Mad, Mirror Bears.
     - Mad won on turn 24. The model played lands, cast creatures and
       attacked.
     - It took 90 s, 72 tool calls with none failed, and 34 auto-passes. It
       used about 2.6M input tokens (2.46M cached) and 1,519 output tokens.
     - The manifest says `completed` and `game_over`, and cleanup finished.
       The bridge's game ID matches the server's `GAME started` line.
     - `llm.catalog` listed all four providers from the existing sign-ins.
3. **Proxy `LlmSeats`. Done.** `Mage.Proxy/README.md` ("Model seats") covers
   the protocol.
   - `--llm off|on|<catalog>`, plus `--llmRoot`, `--llmPython` and
     `--llmMaxSeats` (default 4). With `--llm`, a non-loopback `--bind` or a
     non-local `--allowedOrigins` stops the proxy at startup with exit code 2,
     and a session whose XMage server is not loopback cannot seat models.
   - `getLlmProviders {refresh?}` runs `python -m llm.catalog` and returns only
     whitelisted fields. `getLlmSeats {tableId?}` lists the session's seats.
   - `createTable` validates `llmSeats` before the table exists: HUMAN seat,
     one model per seat, provider, model and effort from the catalog, a deck
     the server knows, 1 to 10 wins, and the seat cap. Then each seat gets a
     run folder with its `.dck` and one `python -m llm.seat` process, with the
     proxy's PID as `owner_pid`.
   - Seats are per `ProxyClient`, so only the session that created the table
     can stop them. They stop on `removeTable`, `leaveTable` (which also
     removes the owned table), `disconnect`, a user switch, grace expiry and
     shutdown. A stop writes `cancel.requested` and kills the process tree
     after 20 s.
   - Names are `<playerName> <n>`, unique across the proxy. A stopped or failed
     seat's name waits 10 minutes before reuse, for the reattach risk below.
   - The table password goes to `llm.seat` on stdin and to the bridge in
     `XMAGE_BRIDGE_TABLE_PASSWORD`, never argv, the manifest or a log.
   - Tests: 26 new Java tests, run against a fake seat process (265 in the
     proxy), plus new `llm` tests for the password and the concede approval
     (170). The bridge's 113
     tests still pass.
   - **Stop proof:** `removeTable` on a password table with a ChatGPT seat.
     The seat joined 12 s after `createTable`, and the `stopped` event came
     1 s after `removeTable`. The manifest says `cancelled` with cleanup
     finished, and no process was left. The password is in none of the run
     files or proxy logs.
   - **Play proof:** ChatGPT `gpt-6-luna` at low reasoning against Mad, Mirror
     Bears. The seat joined in 11 s and logged in as `ChatGPT 1`, because the
     stop proof's `ChatGPT 2` was still cooling down. The events went
     `starting`, `joined`, `playing`. On turn 10, after 28 tool calls (none
     failed), the losing model tried to concede. Codex refused the call, the
     model ended its turn, and the seat reported `failed` with the non-fatal
     `error`. No game result was confirmed through the proxy. The per-run
     overrides now pre-approve `concede` and nothing else; a direct Codex run
     against a stub gateway then called `concede` and the call completed.
   - Not done here: the web types and zod schemas (phase 4) and a `--llm`
     switch in `dev.mjs` and the launcher (phase 5).
4. **Web UI.** Seat type, pickers, staging status, i18n, fixtures and tests.
5. **Docs and live matrix.**
   - Add the launcher wrapper and update the README, `ROADMAP.md` §4 and
     `docs/lessons.md`.
   - Live checks:
     - A 1v1 game with each provider.
     - A best-of-three.
     - Commander with two model seats.
     - Removing a table stops its seats.
     - A proxy restart mid-game.
6. **Later, optional.**
   - Bundling in the desktop launcher. It would need Python and the CLIs on the
     user's machine, or a TypeScript rewrite of the supervisor.
   - Tournaments and draft. `mcp/` already speaks that protocol; the bridge
     does not.
   - The A/B test against `mcp/`.

## Risks and checks

- **Upstream moves daily.** Keep new code in new folders (`Mage.Client.Bridge/`,
  `llm/`, a separate `LlmSeats` class), keep edits to existing web and proxy
  files thin, and merge each new release tag.
- **Version coupling.** Every XMage release Nexus adopts needs the bridge
  rebuilt, the same as the proxy. Connecting with a mismatched version is
  refused. With option A, the engine patch must also be carried onto each
  release.
- **Stopping a seat must leave its table.** In an early phase 1 attempt, a cancelled
  seat's game kept running on the server. The next bridge that logged in as
  the same user was reattached to that old game, played it, and ignored the
  new table's game. `LlmSeats` must concede or leave and remove the table when
  it stops a seat. The bridge should also prefer the game of the table it was
  asked to join. Phase 3 removes the owner's table and keeps a stopped seat's
  name out of use for 10 minutes; the bridge preference is still open.
- **ChatGPT could not concede (fixed).** The gateway marks `concede` as
  destructive. Codex's `default_tools_approval_mode = "auto"` still asks
  approval for destructive tools, and with `-a never` the call failed, so the
  model ended its turn and the seat failed. The per-run `-c` overrides now set
  `mcp_servers.mage.tools.concede.approval_mode = "approve"`. No other tool is
  pre-approved, and the user's own Codex config is untouched because the
  player runs with `--ignore-user-config --ephemeral`. A supervisor that
  resumes a model which stops mid-game would still help for other early stops.
- **Server username limit is 14.** `ChatGPT 2`, `OpenRouter 10` and similar
  fit. Names with a space are fine: the server checks its invalid-name pattern
  only when registering an account, not on login.
- **Card database.** The bridge loads cards lazily into its own H2 database.
  Give it a folder under `.run/llm/` so it never shares a file with the server
  or the proxy.
- **Windows-specific code.** The supervisor uses `msvcrt` and
  `CREATE_NO_WINDOW`. That is fine here, but other platforms need small shims.
- **Secrets.**
  - Catalogs, run folders, CLI homes and local config stay gitignored.
  - The pre-commit secret scan from mage-bench-windows carries over.
  - Nothing reads or copies the CLIs' sign-in caches.
