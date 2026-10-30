"""GUI-only passing policy and lossless references to unchanged player boards."""

import copy
import json
import re

ACTION_TOOLS = {"choose_action", "get_action_choices", "pass_priority"}
TURN = re.compile(r"T(\d+)\b")
# What the merged reply keeps from the action that led into an auto-passed window.
PROMPT_FIELDS = {
    "action_pending",
    "action_type",
    "board",
    "board_cursor",
    "choices",
    "combat",
    "context",
    "game_seq",
    "land_drops_used",
    "message",
    "respond_with",
    "response_type",
    "stack",
    "untapped_lands",
    "your_hand",
}


def is_empty_priority(action):
    """An ordinary priority window with nothing playable: passing is the only meaningful answer."""
    return (
        isinstance(action, dict)
        and action.get("action_pending") is True
        and action.get("action_type") == "GAME_SELECT"
        and action.get("response_type") == "boolean"
        and not action.get("choices")
        and not action.get("combat_phase")
        and not any(action.get(key) for key in ("error", "error_code", "game_over", "player_dead"))
    )


def action_payload(response):
    if not isinstance(response, dict) or "error" in response or response.get("result", {}).get("isError"):
        return None
    texts = [part for part in response["result"].get("content", []) if part.get("type") == "text"]
    if len(texts) != 1:
        return None
    try:
        payload = json.loads(texts[0]["text"])
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None


def replace_payload(response, payload):
    outgoing = copy.deepcopy(response)
    for part in outgoing["result"]["content"]:
        if part["type"] == "text":
            part["text"] = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    if "structuredContent" in outgoing["result"]:
        outgoing["result"]["structuredContent"] = copy.deepcopy(payload)
    return outgoing


class PlayerView:
    def __init__(self):
        self.last_action = None
        self.board = None
        self.board_reference = 0
        self.full_board_required = True
        self.batched_pass_attempts = 0
        self.omitted_boards = 0
        self.auto_passes = 0
        self.omitted_rules = 0
        self.turn = None
        self.shown_rules = set()

    def invalidate_action(self):
        self.last_action = None

    def new_player(self):
        """A replacement model saw none of this game: its next board is full, with every card's rules."""
        self.last_action = None
        self.full_board_required = True

    def prepare_request(self, request):
        effective = copy.deepcopy(request)
        params = effective["params"]
        tool = params["name"]
        arguments = params.setdefault("arguments", {})
        if tool in {"get_action_choices", "get_game_state"}:
            self.full_board_required = True
        if tool in ACTION_TOOLS:
            # Own this cache completely: native cursors also track action snapshots.
            arguments.pop("board_cursor", None)
        if tool == "choose_action" and self.is_empty_priority_pass(arguments):
            params["name"] = "pass_priority"
            params["arguments"] = {}
            self.batched_pass_attempts += 1
        return effective

    def is_empty_priority_pass(self, arguments):
        if set(arguments) != {"choice"} or not isinstance(arguments["choice"], str):
            return False
        if arguments["choice"].strip().lower() not in {"no", "false"}:
            return False
        return is_empty_priority(self.last_action)

    @staticmethod
    def needs_auto_pass(effective_tool, response):
        # Only after an action: a model that asked for this window with pass_priority(until=...) keeps it.
        return effective_tool == "choose_action" and is_empty_priority(action_payload(response))

    def merge_auto_pass(self, first, second):
        """The reply to the model's action is the next real decision, with the action's own outcome kept."""
        decision = action_payload(second)
        if decision is None:
            # Nothing was passed if the bridge refused; the model answers the empty window itself.
            return first
        self.auto_passes += 1
        merged = dict(decision)
        merged["auto_passed"] = True
        outcome = {key: value for key, value in action_payload(first).items() if key not in PROMPT_FIELDS}
        if outcome:
            merged["previous_action"] = outcome
        return replace_payload(second, merged)

    def model_response(self, tool, response):
        if "error" in response or response["result"].get("isError"):
            self.invalidate_action()
            return response
        if tool == "concede":
            self.invalidate_action()
        if tool not in ACTION_TOOLS:
            return response
        result = response["result"]
        texts = [part for part in result["content"] if part["type"] == "text"]
        if len(texts) != 1:
            raise ValueError("Expected one JSON player-view text result")
        payload = json.loads(texts[0]["text"])
        if not isinstance(payload, dict):
            raise ValueError("Expected a JSON player-view object")
        if "structuredContent" in result and result["structuredContent"] != payload:
            raise ValueError("Player-view text and structured content disagree")
        self.last_action = copy.deepcopy(payload)
        if "board" not in payload:
            return response
        turn = TURN.match(payload.get("context") or "")
        new_turn = turn is not None and turn.group(1) != self.turn
        if new_turn:
            self.turn = turn.group(1)
        if new_turn or self.full_board_required:
            # Every turn, and every explicit recovery, shows all card rules again.
            self.shown_rules = set()
        reduced = copy.deepcopy(payload)
        if payload["board"] == self.board:
            if not self.full_board_required and not new_turn and "board_unchanged" not in payload:
                reduced.pop("board")
                reduced["board_unchanged"] = True
                self.omitted_boards += 1
        else:
            self.board = copy.deepcopy(payload["board"])
            self.board_reference += 1
        if "board" in reduced and self.omit_shown_rules(reduced["board"]):
            reduced["rules_omitted"] = True
        reduced["board_reference"] = self.board_reference
        self.full_board_required = False
        return replace_payload(response, reduced)

    def omit_shown_rules(self, board):
        omitted = 0
        for player in board:
            for zone in player.values() if isinstance(player, dict) else ():
                for card in zone if isinstance(zone, list) else ():
                    if not isinstance(card, dict) or not isinstance(card.get("rules"), list):
                        continue
                    # Text that changes (a met condition, a gained ability) is new and shown again.
                    key = (card.get("name"), json.dumps(card["rules"], ensure_ascii=False))
                    if key in self.shown_rules:
                        card.pop("rules")
                        omitted += 1
                    else:
                        self.shown_rules.add(key)
        self.omitted_rules += omitted
        return omitted

    @staticmethod
    def describe_tool(tool):
        name = tool["name"]
        if name not in ACTION_TOOLS:
            return
        if name == "choose_action":
            extra = (
                " In this GUI, choice=no alone at an observed empty ordinary priority prompt uses pass_priority "
                "to wait for the next decision. It does not answer combat, mulligan or targeting choices. "
                "When an action leaves you at an ordinary priority prompt where nothing is playable, that prompt "
                "is passed for you: the result is then your next decision, with auto_passed=true and the "
                "action's own outcome in previous_action."
            )
        elif name == "get_action_choices":
            extra = (
                " Always returns the full board, with each card's rules text at least once, even with board_cursor; "
                "use to recover context."
            )
        else:
            extra = " Omit until to stop at the next playable action, combat or other decision."
        tool["description"] = (
            tool["description"]
            + extra
            + (
                " Identical boards may be replaced by board_unchanged=true and board_reference pointing to the "
                "previous full board. All other fields are current. If that board is no longer in your context, "
                "call get_action_choices for a full board. Native board_cursor is ignored by this GUI adapter. "
                "A card's rules text already shown earlier in the same turn is left out (rules_omitted=true); "
                "each new turn shows it all again, and get_oracle_text looks up any card."
            )
        )
        if "outputSchema" in tool:
            properties = tool["outputSchema"]["properties"]
            properties["board_reference"] = {
                "type": "integer",
                "description": "GUI reference to the last full identical board",
            }
            properties["rules_omitted"] = {
                "type": "boolean",
                "description": "Rules text shown earlier this turn was left out of some cards",
            }
            properties["auto_passed"] = {
                "type": "boolean",
                "description": "An empty priority prompt after your action was passed for you",
            }
            properties["previous_action"] = {
                "type": "object",
                "description": "Outcome of your action before the empty prompt was passed",
            }
