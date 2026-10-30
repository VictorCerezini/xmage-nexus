"""Seat one model at an XMage table: run its bridge, start its CLI per game and record the result.

Run as python -m llm.seat with the seat request JSON on stdin; the owner_pid process owns the seat.
"""

import importlib
import json
import re
import socket
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import psutil

from llm import mcp_engine, paths, providers, subscription_client
from llm.game_tool_gateway import GameToolGateway
from llm.jsonl import read_complete_events
from llm.mcp_client import rpc

ENGINES = ("bridge", "mcp")
MAX_CALLS = 2000
MAX_SECONDS = 7200
MAX_WINS = 10
NEXT_GAME_SECONDS = 600
REPORT_GRACE_SECONDS = 120
# Waits before another player process takes over a game whose player stopped mid-game (a busy model, a
# dropped connection); a reached plan limit is not retried. A model still at capacity after the last one
# hands the seat to its backup model, when it has one, which gets the same retries.
RETRY_DELAYS = (15, 30, 60, 120, 120)
TABLE_PASSWORD_ENV = "XMAGE_BRIDGE_TABLE_PASSWORD"  # noqa: S105 - an environment variable name, not a password
PROMPT = (
    "You are {username}, playing a live Magic: The Gathering game with a human and possibly other players in XMage. "
    "Play the entire game to win, using only the mage MCP tools. This is a game, not a coding task. "
    "Game mode: {game_type}. Deck format: {deck_type}. Number of players: {player_count}. "
    "Your deck was selected in the match configuration. No assumptions about the opponent's deck, "
    "hand, library order, or which player goes first are justified. Use your own player's observations.\n\n"
    "Start with get_action_choices. Inspect get_game_state and oracle text when useful. Choose legal "
    "actions using the exact card IDs or indexes returned by the bridge. choose_action responds to the "
    "current pending choice. yes/no mean confirm or decline as described by that choice. Auto-tap can "
    "pay mana costs. Batch attackers can be 'all' or comma-separated IDs; blockers can be comma-separated "
    "blocker:attacker pairs. Read tool descriptions for full details. If a choice is stale or an action "
    "returns an error, refresh choices and correct it. Never invent card IDs.\n\n"
    "Use pass_priority to wait for your next decision rather than repeatedly polling. The human may "
    "take time to think; wait patiently. Omit until so it stops at playable actions, combat or other choices. "
    "At an empty ordinary priority prompt, choice=no alone also uses that blocking pass. "
    "A board_unchanged result references the previous full board by board_reference; all other fields are current. "
    "If that full board is missing from your context, call get_action_choices to recover it before deciding. "
    "Card rules text already shown this turn is left out (rules_omitted); each new turn shows it all again. "
    "An empty priority prompt after your action is passed for you (auto_passed), and the reply is your next decision. "
    "Continue until the bridge reports game_over or player_dead. Do not stop after "
    "a few moves or concede just to finish. Avoid commentary overhead. Do not supply the human's moves "
    "or look for outside information. In multiplayer, inspect all players and legal targets rather than "
    "assuming there is only one opponent. For Commander, respond to commander selection and command-zone "
    "choices using the legal actions provided. Once your game ends or you are eliminated, report your own "
    "factual result briefly and stop; other multiplayer participants may still be playing."
)
MATCH_PROMPT = (
    "\n\nThis game is part of a match won by the first player to win {wins_needed} games. This is game {game}. "
    "{status}A separate session plays each game of the match with the same, unchanged deck."
)


RESUME_PROMPT = (
    "\n\nThe previous session playing this game for you stopped before the game ended. The game is still "
    "running and you are still in it. Start by looking at the current game state and your pending decision, "
    "then keep playing until the game ends."
)


class SessionCancelledError(Exception):
    """The seat's owner stopped or cancelled it; this is not a model loss or harness failure."""


def request_provider(request):
    # Requests from clients built before Claude support carry no provider and mean ChatGPT.
    return providers.get(request.get("provider", "chatgpt_subscription"))


def request_engine(request):
    return request.get("engine", "bridge")


def validate_request(request):
    provider = request_provider(request)
    engine = request_engine(request)
    if engine not in ENGINES:
        raise ValueError(f"Unknown game engine: {engine}")
    if engine == "mcp":
        if provider.id not in mcp_engine.PROVIDERS:
            raise ValueError(f"The mcp engine does not support {provider.label} seats")
        if wins_needed(request) != 1 or int(request["player_count"]) != 2:
            raise ValueError("The mcp engine plays two-player single games only")
        if not str(request.get("proxy_url", "")).startswith("ws://127.0.0.1:"):
            raise ValueError("The mcp engine needs the local proxy's WebSocket URL")
    UUID(request["table_id"])
    if not 1 <= int(request["seat"]) <= int(request["player_count"]):
        raise ValueError(f"The {provider.label} seat is outside the configured player count")
    if not 1 <= wins_needed(request) <= MAX_WINS:
        raise ValueError(f"Wins needed must be between 1 and {MAX_WINS}")
    if not 1 <= int(request["server_port"]) <= 65535:
        raise ValueError("Invalid local XMage server port")
    if not Path(request["deck"]).is_file():
        raise ValueError(f"The selected {provider.label} deck file does not exist")
    catalog = json.loads(Path(request["catalog"]).read_text(encoding="utf-8"))
    entry = providers.catalog_entry(catalog, provider.id)
    provider.validate_selection(entry, request["model"], request["reasoning"])
    if request.get("backup_model"):
        if request["backup_model"] == request["model"]:
            raise ValueError(f"The {provider.label} backup model must differ from the chosen model")
        provider.validate_selection(entry, request["backup_model"], request["backup_reasoning"])


def wins_needed(request):
    # Requests from clients built before match support carry no wins and mean a single game.
    return int(request.get("wins_needed", 1))


def game_file(name, game):
    """Game 1 keeps the original file names; later games of a match get their own."""
    if game == 1:
        return name
    path = Path(name)
    return f"{path.stem}-game{game}{path.suffix}"


def retry_file(name, attempt):
    """A player that takes over a stopped game writes its own files next to the first one's."""
    if attempt == 0:
        return name
    path = Path(name)
    return f"{path.stem}-retry{attempt}{path.suffix}"


def tool_payload(response):
    if "error" in response or response["result"].get("isError"):
        raise RuntimeError("The local game bridge rejected a request")
    result = response["result"]
    if "structuredContent" in result:
        return result["structuredContent"]
    return json.loads(next(item["text"] for item in result["content"] if item["type"] == "text"))


def owner_alive(owner, created):
    try:
        return owner.is_running() and owner.create_time() == created
    except psutil.NoSuchProcess:
        return False


def check_owner(owner, created):
    if not owner_alive(owner, created):
        raise SessionCancelledError("The process that owns this seat stopped")


def check_session(owner, created, run):
    check_owner(owner, created)
    if (run / "cancel.requested").exists():
        raise SessionCancelledError("XMage table was cancelled or left")


def games_started(log_text, username):
    return log_text.count(f"[{username}] Game started: ")


def game_log(log_text, username, game):
    """The part of the bridge log that belongs to one game of the match."""
    starts = [match.start() for match in re.finditer(re.escape(f"[{username}] Game started: "), log_text)]
    begin = starts[game - 1] if 1 < game <= len(starts) else 0
    end = starts[game] if game < len(starts) else len(log_text)
    return log_text[begin:end]


def match_line(log_text, username, event):
    """The text after the last '[user] <event>: ' bridge log line, or None."""
    marker = f"[{username}] {event}: "
    index = log_text.rfind(marker)
    if index < 0:
        return None
    return log_text[index + len(marker) :].splitlines()[0].strip()


def native_terminal_reason(log_text, username):
    if f"[{username}] Game over: " in log_text:
        return "game_over"
    if f"[{username}] Player death detected from game log" in log_text:
        return "player_eliminated"
    return None


def run_player(request, *, start_player=None, runtime=None):
    if runtime is None:
        runtime = importlib.import_module("llm.runtime")

    local = runtime
    validate_request(request)
    provider = request_provider(request)
    start_player = start_player or provider.start
    engine = request_engine(request)
    engine_label = "bridge" if engine == "bridge" else "mcp/ game server"
    run = Path(request["run"])
    run.mkdir(parents=True, exist_ok=True)
    owner = psutil.Process(request["owner_pid"])
    created = owner.create_time()
    wins = wins_needed(request)
    manifest = {
        "status": "joining",
        "provider": provider.id,
        "table_id": request["table_id"],
        "model": request["model"],
        "reasoning": request["reasoning"],
        "seat": request["seat"],
        "game_type": request["game_type"],
        "deck_type": request["deck_type"],
        "player_count": request["player_count"],
        "auth": provider.auth,
        "api_mode": False,
        "engine": engine,
        "tool_policy": "gui-efficient-v2" if engine == "bridge" else "mcp",
        "wins_needed": wins,
        "started_at_utc": datetime.now(UTC).isoformat(),
        "usage": [],
        "games": [],
    }
    processes = []
    logs = []
    gateways = []
    event_paths = []
    url = None
    bridge = None
    server = None
    mcp_game = {}
    # The model this seat plays with now: the chosen one, or its backup once that took over.
    playing = {"model": request["model"], "reasoning": request["reasoning"]}
    started = time.monotonic()

    def log(name):
        stream = (run / name).open("w", encoding="utf-8")
        logs.append(stream)
        return stream

    def save_status(status):
        manifest["status"] = status
        local.save_json(run / "manifest.json", manifest)
        try:
            print(status, flush=True)
        except OSError:
            # The app that reads it already stopped (it cancels the seat); the manifest has the status.
            pass

    def bridge_text():
        bridge_log = run / "bridge.log"
        return bridge_log.read_text(encoding="utf-8") if bridge_log.is_file() else ""

    def engine_process():
        return server.proc if server else bridge

    def poll_mcp_game():
        if "game_over" in mcp_game or not server.call("mage_session").get("gameOver"):
            return
        view = server.call("mage_game_state")
        mcp_game["game_over"] = (view.get("gameOver") or {}).get("data")
        mcp_game["turn"] = (view.get("state") or {}).get("turn")

    def decided(number):
        """How this player's game ended, or None while it goes on."""
        if server:
            return "game_over" if "game_over" in mcp_game else None
        return native_terminal_reason(game_log(bridge_text(), request["username"], number), request["username"])

    def winner(number):
        if server:
            return mcp_engine.winner(mcp_game.get("game_over"))
        game_text = game_log(bridge_text(), request["username"], number)
        return mcp_engine.winner(match_line(game_text, request["username"], "Game over"))

    def play_attempt(number, attempt, prompt, gateway, game_started, usage):
        """Run one player process on this game: (terminal_reason, None) once the game is decided for this
        player, or (None, why it stopped) when it stopped first."""

        def name(base):
            return retry_file(game_file(base, number), attempt)

        events = run / name(provider.events_file)
        if attempt:
            prompt += RESUME_PROMPT
        (run / name("player-prompt.txt")).write_text(prompt, encoding="utf-8")
        agent = start_player(
            prompt,
            log(name(provider.events_file)),
            log(name(provider.stderr_file)),
            gateway.url,
            model=playing["model"],
            reasoning=playing["reasoning"],
            tool_timeout_seconds=MAX_SECONDS + 60,
            **({"tools": mcp_engine.TOOLS} if server else {}),
        )
        processes.append(agent)
        event_paths.append(events)
        save_status("playing")
        ended_at = None
        stopped_after_report = False
        while agent.poll() is None:
            check_session(owner, created, run)
            # Read after polling: a bridge that logged the result and then left at match end is not a failure.
            engine_stopped = engine_process().poll() is not None
            if server and ended_at is None and not engine_stopped:
                poll_mcp_game()
            if ended_at is None and decided(number):
                # The game is decided for this player; nothing it calls from now on reaches the next game.
                gateway.finish_game()
                ended_at = time.monotonic()
            if engine_stopped and ended_at is None:
                raise RuntimeError(f"The {provider.label} {engine_label} stopped during play")
            if gateway.violations or gateway.call_count > MAX_CALLS:
                raise RuntimeError("The game tool boundary or call limit was exceeded")
            if gateway.fatal_error is not None:
                raise RuntimeError(gateway.fatal_error)
            if time.monotonic() - game_started > MAX_SECONDS:
                raise TimeoutError("The interactive game exceeded its two-hour limit")
            provider.require_game_only_events(read_complete_events(events))
            if ended_at is not None and time.monotonic() - ended_at > REPORT_GRACE_SECONDS:
                local.stop_tree(agent)
                stopped_after_report = True
                break
            time.sleep(0.5)
        trace = read_complete_events(events)
        provider.require_game_only_events(trace)
        usage.extend(provider.usage(trace))
        terminal_reason = decided(number)
        if terminal_reason is not None and (stopped_after_report or agent.returncode == 0):
            return terminal_reason, None
        reason = provider.failure_reason(trace) or f"see {events.name}"
        if terminal_reason is not None:
            # The game is over, so another player would have nothing left to play.
            raise RuntimeError(f"{provider.label} stopped before a confirmed game result: {reason}")
        return None, reason

    def wait_to_retry(number, seconds):
        """Wait before the next player takes over; the game's result if it ended meanwhile."""
        deadline = time.monotonic() + seconds
        while True:
            check_session(owner, created, run)
            if engine_process().poll() is not None:
                raise RuntimeError(f"The {provider.label} {engine_label} stopped during play")
            if server:
                poll_mcp_game()
            terminal_reason = decided(number)
            if terminal_reason is not None:
                return terminal_reason
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.5)

    def can_switch(reason):
        backup = request.get("backup_model")
        return bool(backup) and playing["model"] != backup and providers.at_capacity(reason)

    def play_game(number):
        gateway = GameToolGateway(
            server or url,
            mcp_engine.TOOLS if server else subscription_client.TOOLS,
            run / game_file("player-tools.jsonl", number),
            max_calls=MAX_CALLS,
            optimize_play=server is None,
            upstream_timeout=MAX_SECONDS + 30,
            describe=mcp_engine.describe_tool if server else None,
        )
        gateways.append(gateway)
        prompt = (mcp_engine.PROMPT if server else PROMPT).format(
            username=request["username"],
            game_type=request["game_type"],
            deck_type=request["deck_type"],
            player_count=request["player_count"],
        )
        if wins > 1:
            status = match_line(bridge_text(), request["username"], "Match continues")
            prompt += MATCH_PROMPT.format(
                wins_needed=wins, game=number, status=f"Match status: {status} " if status else ""
            )
        game_started = time.monotonic()
        usage = []
        attempt = 0
        tries = 0
        while True:
            terminal_reason, reason = play_attempt(number, attempt, prompt, gateway, game_started, usage)
            if reason is None:
                break
            switching = tries == len(RETRY_DELAYS)
            if providers.lasting_limit(reason) or (switching and not can_switch(reason)):
                raise RuntimeError(f"{provider.label} stopped before a confirmed game result: {reason}")
            attempt += 1
            manifest.setdefault("recoveries", []).append(
                {"game": number, "attempt": attempt, "reason": reason, "model": playing["model"]}
            )
            if switching:
                # The chosen model stayed at capacity through every retry: the backup plays the rest of the match.
                manifest["model_switch"] = {
                    "game": number,
                    "from": playing["model"],
                    "to": request["backup_model"],
                    "reason": reason,
                }
                playing.update(model=request["backup_model"], reasoning=request["backup_reasoning"])
                tries, delay = 0, 0
            else:
                delay = RETRY_DELAYS[tries]
                tries += 1
            terminal_reason = wait_to_retry(number, delay)
            if terminal_reason is not None:
                # The game ended while no player was connected (the others won, or the table ended it).
                gateway.finish_game()
                break
            gateway.new_player()
        manifest["games"].append(
            {
                "game": number,
                "terminal_reason": terminal_reason,
                "winner": winner(number),
                **({"turns": mcp_game.get("turn")} if server else {}),
                "game_wall_seconds": round(time.monotonic() - game_started, 3),
                "tool_calls": gateway.call_count,
                **({"player_restarts": attempt} if attempt else {}),
                **({"model": playing["model"]} if playing["model"] != request["model"] else {}),
                "usage": usage,
            }
        )
        manifest["game_wall_seconds"] = round(sum(entry["game_wall_seconds"] for entry in manifest["games"]), 3)
        return terminal_reason

    def wait_for_next_game(number):
        """True once the match's next game is ready for a decision; False when the match is over."""
        deadline = time.monotonic() + NEXT_GAME_SECONDS
        while True:
            check_session(owner, created, run)
            bridge_stopped = bridge.poll() is not None
            text = bridge_text()
            result = match_line(text, request["username"], "Match over")
            if bridge_stopped and result is None:
                raise RuntimeError(f"The {provider.label} bridge stopped between games of the match")
            if result is not None:
                manifest["match_result"] = result
                return False
            if games_started(text, request["username"]) >= number:
                state = tool_payload(rpc(url, "tools/call", {"name": "get_game_state", "arguments": {}}))
                choices = tool_payload(rpc(url, "tools/call", {"name": "get_action_choices", "arguments": {}}))
                if state.get("available") or choices.get("action_pending"):
                    return True
            if time.monotonic() > deadline:
                raise TimeoutError("The next game of the match did not start within ten minutes")
            time.sleep(1)

    def join_with_bridge(env):
        nonlocal bridge, url
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            mcp_port = reservation.getsockname()[1]
        arguments = [
            *paths.bridge_java_arguments(),
            "-Xmx512m",
            "-Dxmage.bridge.server=127.0.0.1",
            f"-Dxmage.bridge.port={request['server_port']}",
            f"-Dxmage.bridge.username={request['username']}",
            f"-Dxmage.bridge.seat={request['seat']}",
            f"-Dxmage.bridge.deck={Path(request['deck']).as_posix()}",
            f"-Dxmage.bridge.tableId={request['table_id']}",
            f"-Dxmage.bridge.mcpPort={mcp_port}",
            f"-Dxmage.bridge.errorlog={(run / 'bridge-errors.log').as_posix()}",
            f"-Dxmage.bridge.bridgelog={(run / 'bridge.jsonl').as_posix()}",
        ]
        if wins > 1:
            # The bridge stays for the match's later games and keeps the deck unchanged between them.
            arguments.append("-Dxmage.bridge.continueMatch=true")
        bridge_env = dict(env)
        if request.get("table_password"):
            bridge_env[TABLE_PASSWORD_ENV] = request["table_password"]
        bridge = local.launch_bridge(arguments, bridge_env, log("bridge.log"), run / "bridge")
        processes.append(bridge)
        save_status("joining")
        url = f"http://127.0.0.1:{mcp_port}/mcp"
        deadline = time.monotonic() + 180
        while True:
            check_session(owner, created, run)
            if bridge.poll() is not None:
                raise RuntimeError(f"The {provider.label} game bridge stopped while joining; see bridge.log")
            with socket.socket() as probe:
                probe.settimeout(0.5)
                if probe.connect_ex(("127.0.0.1", mcp_port)) == 0:
                    break
            if time.monotonic() > deadline:
                raise TimeoutError(f"{provider.label} could not join the selected table within three minutes")
            time.sleep(0.5)
        rpc(
            url,
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "gui-controller", "version": "1"},
            },
        )
        save_status("waiting_for_start")
        deadline = time.monotonic() + 600
        while True:
            check_session(owner, created, run)
            if bridge.poll() is not None:
                raise RuntimeError(f"The {provider.label} bridge stopped before the match started")
            state = tool_payload(rpc(url, "tools/call", {"name": "get_game_state", "arguments": {}}))
            choices = tool_payload(rpc(url, "tools/call", {"name": "get_action_choices", "arguments": {}}))
            if state.get("available") or choices.get("action_pending"):
                break
            if time.monotonic() > deadline:
                raise TimeoutError("The table was not started within ten minutes; create a new match")
            time.sleep(1)

    def join_with_mcp(env):
        nonlocal server
        server = local.launch_mcp(env, log("mcp-server.log"))
        processes.append(server.proc)
        save_status("joining")
        server.initialize()
        server.call(
            "mage_connect",
            {
                "proxyUrl": request["proxy_url"],
                "host": "127.0.0.1",
                "port": int(request["server_port"]),
                "username": request["username"],
                "timeoutSec": 170,
            },
            timeout=180,
        )
        join = {
            "tableId": request["table_id"],
            "deck": mcp_engine.read_deck(request["deck"]),
            "playerName": request["username"],
        }
        if request.get("table_password"):
            join["password"] = request["table_password"]
        server.call("mage_join_table", join)
        save_status("waiting_for_start")
        deadline = time.monotonic() + 600
        game_seen = None
        while True:
            check_session(owner, created, run)
            if server.proc.poll() is not None:
                raise RuntimeError("The mcp/ game server stopped before the match started; see mcp-server.log")
            snapshot = server.call("mage_session")
            if snapshot.get("game") or snapshot.get("pendingPrompt"):
                return
            if snapshot.get("gameId"):
                game_seen = game_seen or time.monotonic()
                if time.monotonic() - game_seen > 20:
                    server.call("mage_join_game", {"waitMs": 15000}, timeout=60)
                    game_seen = time.monotonic()
            if time.monotonic() > deadline:
                raise TimeoutError("The table was not started within ten minutes; create a new match")
            time.sleep(1)

    try:
        env = subscription_client.clean_env()
        paths.PLAYER_CWD.mkdir(parents=True, exist_ok=True)
        if engine == "mcp":
            join_with_mcp(env)
        else:
            join_with_bridge(env)
        game = 1
        while True:
            manifest["terminal_reason"] = play_game(game)
            if wins == 1:
                break
            save_status("waiting_for_next_game")
            if not wait_for_next_game(game + 1):
                break
            game += 1
        save_status("completed")
        return 0
    except SessionCancelledError as error:
        manifest["reason"] = str(error)
        # Stopping the seat right after the game ends, before the model's closing report, is still a result.
        terminal_reason = decided(len(gateways)) if manifest["status"] == "playing" else None
        if terminal_reason is not None:
            manifest["terminal_reason"] = terminal_reason
            save_status("completed")
        else:
            save_status("cancelled")
        return 0
    except Exception as error:  # noqa: BLE001 - supervisor records failures and always cleans owned processes
        manifest["error"] = str(error)
        save_status("failed")
        print(f"{type(error).__name__}: {error}", flush=True)
        return 1
    finally:
        for proc in reversed(processes):
            if server and proc is server.proc:
                server.end_session()
            local.stop_tree(proc)
        for stream in logs:
            stream.close()
        for gateway in gateways:
            gateway.close()
        if gateways:
            manifest["tool_calls"] = sum(gateway.call_count for gateway in gateways)
            manifest["optimization"] = merged_stats([gateway.optimization_stats() for gateway in gateways])
        manifest["usage"] = [usage for path in event_paths for usage in provider.usage(read_complete_events(path))]
        manifest["total_wall_seconds"] = round(time.monotonic() - started, 3)
        manifest["cleanup_complete"] = all(proc.poll() is not None for proc in processes)
        local.save_json(run / "manifest.json", manifest)


def merged_stats(stats):
    """Per-game gateway statistics added up for the whole match."""
    merged = dict(stats[0])
    for entry in stats[1:]:
        for key, value in entry.items():
            if isinstance(value, int) and not isinstance(value, bool):
                merged[key] = merged.get(key, 0) + value
    return merged


if __name__ == "__main__":
    raise SystemExit(run_player(json.load(sys.stdin)))
