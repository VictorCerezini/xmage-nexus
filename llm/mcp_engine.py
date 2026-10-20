"""The mcp/ game server as a seat's engine: one node process per seat, spoken to over stdio.

An experiment arm next to the native bridge: the supervisor connects and joins the table itself, and the
model only ever sees the play tools listed here, through the same allow-listed gateway as the bridge.
"""

import json
import re
import shutil
import subprocess
import threading
from pathlib import Path

from llm import paths

ENTRY = paths.REPO / "mcp" / "src" / "index.ts"
PROVIDERS = {"chatgpt_subscription"}
TOOLS = [
    "mage_wait_for_prompt",
    "mage_game_state",
    "mage_choose",
    "mage_play_card",
    "mage_pay_mana",
    "mage_combat",
    "mage_pass_priority",
    "mage_concede",
]
READ_ONLY = {"mage_wait_for_prompt", "mage_game_state"}
PROTOCOL_VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
DESCRIPTIONS = {
    "mage_wait_for_prompt": (
        "Block until the game asks you for a decision or the game ends. Returns the pending prompt "
        "(mode, message and options), a compact game state and promptSeq, or gameOver. afterSeq waits only "
        "for a prompt newer than that sequence number: pass the promptSeq returned by your last action. "
        "On timeout it returns timeout=true with the current state; call it again."
    ),
    "mage_game_state": (
        "The current game state: turn, phase, step and priority, each player's life and counts, your hand, "
        "both battlefields (tapped, power/toughness, damage, counters), the stack, playable ids, combat, and "
        "the pending prompt. level=full adds the raw game view (large)."
    ),
    "mage_choose": (
        "Answer the pending prompt: optionId (an option's id or value), value (a number, text or "
        "true/false), or values (for order prompts, option indexes in the order you want; for multi-amount "
        "prompts, the amounts). With no arguments it passes a select or combat prompt. "
        'optionId="special" presses the special button of the prompt (automatic mana payment, all attack).'
    ),
    "mage_play_card": (
        "Play a playable card, or activate a playable ability, by its id (from your hand, the battlefield "
        "or an ability)."
    ),
    "mage_pay_mana": (
        "Pay mana: sourceId taps that mana source, manaType (W, U, B, R, G or C) spends that mana from "
        "your pool, and special=true pays automatically. To cancel a payment, call mage_choose with no "
        "arguments."
    ),
    "mage_combat": (
        "Declare combat. attackers are attacking creature ids. blockers are your blocking creature ids, "
        "declared one at a time; blockTargets gives the attacker each blocker blocks (same index), optional "
        "when only one creature attacks. confirm=true (default) confirms the declaration at the end."
    ),
    "mage_pass_priority": "Pass priority, confirm the current attackers or blockers, or answer no.",
    "mage_concede": "Concede the game.",
}
ARGUMENTS = {
    "timeoutMs": "How long to wait, in milliseconds (1000 to 120000).",
    "afterSeq": "Only return a prompt with a higher promptSeq than this.",
    "level": "compact (default) or full.",
    "optionId": "The id or value of one of the prompt's options, or special.",
    "value": "A number, text or boolean answer.",
    "values": "Indexes or amounts, in order.",
    "cardId": "The id of a playable card, source or ability.",
    "sourceId": "The id of a mana source to tap.",
    "manaType": "W, U, B, R, G or C from your mana pool.",
    "special": "true pays the cost automatically.",
    "attackers": "Ids of your creatures that attack.",
    "blockers": "Ids of your creatures that block.",
    "blockTargets": "The attacker each blocker blocks, at the same index as blockers.",
    "confirm": "Confirm the declaration after the ids are sent (default true).",
    "targetTimeoutMs": "How long to wait for the attacker choice after each blocker, in milliseconds.",
}
PROMPT = (
    "You are {username}, playing a live Magic: The Gathering game with a human and possibly other players in XMage. "
    "Play the entire game to win, using only the mage MCP tools. This is a game, not a coding task. "
    "Game mode: {game_type}. Deck format: {deck_type}. Number of players: {player_count}. "
    "Your deck was selected in the match configuration. No assumptions about the opponent's deck, "
    "hand, library order, or which player goes first are justified. Use your own player's observations.\n\n"
    "Start with mage_wait_for_prompt. It blocks until the game asks you for a decision and returns the "
    "prompt with its options, a compact game state and promptSeq. After each action, call "
    "mage_wait_for_prompt again with afterSeq set to the promptSeq that action returned and timeoutMs 120000, "
    "so you wait only for your next decision. Use mage_game_state when you need the whole board. Answer "
    "prompts with mage_choose, play cards and abilities with mage_play_card, pay mana with mage_pay_mana "
    "(special=true pays automatically), declare attackers and blockers with mage_combat, and pass priority "
    "or confirm with mage_pass_priority. Read tool descriptions for full details. Priority windows where "
    "you have nothing to do are passed for you. If an action returns an error, read the current prompt and "
    "correct it. Never invent card IDs.\n\n"
    "The human may take time to think; wait patiently, and call mage_wait_for_prompt again when it times "
    "out. Continue until mage_wait_for_prompt reports gameOver. Do not stop after a few moves or concede just "
    "to finish. Avoid commentary overhead. Do not supply the human's moves or look for outside information. "
    "Once your game ends, report your own factual result briefly and stop."
)
CARD_LINE = re.compile(r"(SB: )?(\d+) \[([^:\]]+):([^\]]+)\] (.+)")
WINNER = re.compile(r"(?:Player )?(.+?) is the winner")


def describe_tool(tool):
    """English text for a play tool, without the multi-session pin the seat never uses."""
    tool["description"] = DESCRIPTIONS[tool["name"]]
    schema = tool.get("inputSchema", {})
    properties = schema.get("properties", {})
    properties.pop("session", None)
    for name, argument in properties.items():
        argument["description"] = ARGUMENTS.get(name, name)
    if "required" in schema:
        schema["required"] = [name for name in schema["required"] if name != "session"]
    read_only = tool["name"] in READ_ONLY
    tool["annotations"] = {
        "readOnlyHint": read_only,
        "destructiveHint": tool["name"] == "mage_concede",
        "openWorldHint": False,
        "idempotentHint": read_only,
    }


def read_deck(path):
    """The proxy's .dck file as the DeckJson that mcp/ joins a table with."""
    deck = {"name": Path(path).stem, "cards": [], "sideboard": []}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("NAME:"):
            deck["name"] = line[len("NAME:") :]
            continue
        match = CARD_LINE.fullmatch(line)
        if match:
            sideboard, amount, set_code, number, name = match.groups()
            card = {"cardName": name, "setCode": set_code, "cardNumber": number, "amount": int(amount)}
            deck["sideboard" if sideboard else "cards"].append(card)
    if not deck["cards"]:
        raise ValueError("The seat's deck file has no cards")
    return deck


def winner(game_over):
    """The winner's name from a GAME_OVER message, or None for a draw or an unknown message."""
    if isinstance(game_over, dict):
        if game_over.get("winnerName"):
            return game_over["winnerName"]
        game_over = game_over.get("message")
    match = WINNER.search(game_over) if isinstance(game_over, str) else None
    return match.group(1) if match else None


def command():
    node = shutil.which("node")
    if not node:
        raise RuntimeError("Node.js 24 or later is required for the mcp engine")
    return [str(node), str(ENTRY)]


class StdioServer:
    """Newline-delimited JSON-RPC to one MCP server process; requests may overlap."""

    def __init__(self, proc):
        self.proc = proc
        self.pending = {}
        self.lock = threading.Lock()
        self.write_lock = threading.Lock()
        self.next_id = 0
        self.stopped = False
        self.initialize_result = None
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for line in self.proc.stdout:
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if not isinstance(message, dict) or "method" in message:
                continue
            with self.lock:
                waiter = self.pending.pop(message.get("id"), None)
            if waiter:
                waiter[1].append(message)
                waiter[0].set()
        with self.lock:
            self.stopped = True
            waiters = list(self.pending.values())
            self.pending.clear()
        for done, _slot in waiters:
            done.set()

    def _send(self, message):
        with self.write_lock:
            self.proc.stdin.write((json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8"))
            self.proc.stdin.flush()

    def request(self, method, params=None, timeout=60):
        done = threading.Event()
        slot = []
        with self.lock:
            if self.stopped:
                raise ConnectionError("The mcp/ game server stopped")
            self.next_id += 1
            ident = self.next_id
            self.pending[ident] = (done, slot)
        message = {"jsonrpc": "2.0", "id": ident, "method": method}
        if params is not None:
            message["params"] = params
        try:
            self._send(message)
        except OSError as error:
            with self.lock:
                self.pending.pop(ident, None)
            raise ConnectionError("The mcp/ game server stopped") from error
        if not done.wait(timeout):
            with self.lock:
                self.pending.pop(ident, None)
            raise TimeoutError(f"The mcp/ game server did not answer {method} within {timeout} seconds")
        if not slot:
            raise ConnectionError("The mcp/ game server stopped")
        return slot[0]

    def initialize(self):
        response = self.request(
            "initialize",
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "seat-supervisor", "version": "1"},
            },
        )
        if "error" in response:
            raise RuntimeError("The mcp/ game server refused to initialize")
        self.initialize_result = response["result"]
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, name, arguments=None, timeout=60):
        """A supervisor tool call; the JSON result, or {"text": ...} for a plain-text one."""
        response = self.request("tools/call", {"name": name, "arguments": arguments or {}}, timeout)
        result = response.get("result") or {}
        text = "\n".join(item.get("text", "") for item in result.get("content", []) if item.get("type") == "text")
        if "error" in response or result.get("isError"):
            message = text or response.get("error", {}).get("message", "unknown error")
            raise RuntimeError(f"mcp/ {name} failed: {message}")
        try:
            return json.loads(text)
        except ValueError:
            return {"text": text}

    def forward(self, payload, timeout):
        """The gateway's upstream: the model's MCP session rides on the supervisor's initialized one."""
        method = payload.get("method")
        if "id" not in payload:
            return 202, b""
        if method == "initialize":
            result = dict(self.initialize_result)
            requested = (payload.get("params") or {}).get("protocolVersion")
            if requested in PROTOCOL_VERSIONS:
                result["protocolVersion"] = requested
            response = {"jsonrpc": "2.0", "id": payload["id"], "result": result}
        else:
            response = dict(self.request(method, payload.get("params"), timeout))
            response["id"] = payload["id"]
        return 200, json.dumps(response, separators=(",", ":"), ensure_ascii=False).encode("utf-8")

    def end_session(self, timeout=10):
        """Frees the proxy session and its XMage login now instead of after the proxy's grace period."""
        if self.proc.poll() is not None:
            return
        try:
            self.call("mage_disconnect", timeout=timeout)
        except (ConnectionError, TimeoutError, RuntimeError):
            pass

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass


def launch(env, stderr):
    proc = subprocess.Popen(
        command(),
        cwd=ENTRY.parent.parent,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=stderr,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    return StdioServer(proc)
