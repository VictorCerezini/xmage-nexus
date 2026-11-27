package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;

import mage.client.bridge.BridgeCallbackHandler;

import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class GetGameLogTool {

    public static class Result {
        @ResultField(description = "Game log text")
        public String log;

        @ResultField(description = "Full log length in chars")
        public Integer total_length;

        @ResultField(description = "Older content was omitted")
        public Boolean truncated;

        @ResultField(description = "Game log cursor for next call")
        public Integer cursor;

        @ResultField(description = "Cursor was too old and was reset",
            conditional = "when cursor parameter was used")
        public Boolean cursor_reset;

        @ResultField(description = "Turn number the log starts from",
            conditional = "when since_turn parameter was used")
        public Integer since_turn;
    }

    @Tool(
        name = "get_game_log",
        description = "Get game log text, under a \"Turn N (player):\" line per turn. "
            + "Use since_turn for turn-based recap, "
            + "cursor for incremental updates, or max_chars for recent text."
    )
    public static Result execute(
            BridgeCallbackHandler handler,
            @Param(description = "Max chars to return (0 or omit for all)") Integer max_chars,
            @Param(description = "Cursor from previous call for incremental updates. Mutually exclusive with since_turn.") Integer cursor,
            @Param(description = "Get log from this game turn onward: the number after T in context "
                + "(every player's turn counts). Mutually exclusive with cursor.") Integer since_turn) {

        if (since_turn != null && cursor != null) {
            throw new RuntimeException("since_turn and cursor are mutually exclusive — provide one or neither");
        }

        if (since_turn != null) {
            return handler.getGameLogSinceTurn(since_turn);
        }

        int mc = max_chars != null ? max_chars : 0;
        return handler.getGameLogChunk(mc, cursor);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Truncated log", json(
                "log", "Turn 5 (Alice):\nAlice cast Lightning Bolt targeting Bob",
                "total_length", 523,
                "truncated", true,
                "cursor", 42)),
            example("Cursor delta", json(
                "log", "Bob cast Swords to Plowshares targeting Goblin Guide",
                "total_length", 530,
                "truncated", false,
                "cursor", 45)),
            example("Since turn recap", json(
                "log", "Turn 4 (Bob):\nBob cast Sol Ring\nTurn 5 (Alice):\nAlice played Forest",
                "total_length", 540,
                "truncated", false,
                "cursor", 50,
                "since_turn", 4)));
    }
}
