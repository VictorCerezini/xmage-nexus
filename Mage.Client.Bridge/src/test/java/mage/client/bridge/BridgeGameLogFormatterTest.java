package mage.client.bridge;

import mage.client.bridge.mcp.BridgeGameLogFormatter;
import mage.client.bridge.processor.BridgePublishedLogEntry;
import mage.client.bridge.tools.GetGameHistoryTool;
import mage.game.BridgeLogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeGameLogFormatterTest {

    @Test
    void renderGameLogFlatUsesPublishedEntryOrderAndSkipsSetupEvents() {
        List<BridgePublishedLogEntry> entries = List.of(
            new BridgePublishedLogEntry(0, bridgeLogEntry(0, "LAND_PLAYED", 0, "Alice", "Alice", "Pre-game Island", null), null),
            new BridgePublishedLogEntry(1, bridgeLogEntry(1, "BEGIN_TURN", 1, "Alice", "Alice", null, null), null),
            new BridgePublishedLogEntry(2, null, "[Chat] Zoe: alpha"),
            new BridgePublishedLogEntry(3, null, "[Chat] Bob: zeta"),
            new BridgePublishedLogEntry(4, bridgeLogEntry(2, "LAND_PLAYED", 1, "Alice", "Alice", "Island", null), null)
        );

        assertThat(BridgeGameLogFormatter.renderGameLogFlat(entries, false))
            .isEqualTo(String.join("\n",
                "Turn 1 (Alice):",
                "[Chat] Zoe: alpha",
                "[Chat] Bob: zeta",
                "Alice played Island"
            ));
    }

    @Test
    void renderGameLogFlatKeepsPreTurnChatAndSystemLines() {
        List<BridgePublishedLogEntry> entries = List.of(
            new BridgePublishedLogEntry(0, null, "[System] Table ready"),
            new BridgePublishedLogEntry(1, null, "[Chat] Bob: glhf"),
            new BridgePublishedLogEntry(2, bridgeLogEntry(2, "LAND_PLAYED", 0, "Alice", "Alice", "Pre-game Island", null), null),
            new BridgePublishedLogEntry(3, bridgeLogEntry(3, "BEGIN_TURN", 1, "Alice", "Alice", null, null), null),
            new BridgePublishedLogEntry(4, bridgeLogEntry(4, "LAND_PLAYED", 1, "Alice", "Alice", "Island", null), null)
        );

        assertThat(BridgeGameLogFormatter.renderGameLogFlat(entries, false))
            .isEqualTo(String.join("\n",
                "[System] Table ready",
                "[Chat] Bob: glhf",
                "Turn 1 (Alice):",
                "Alice played Island"
            ));
    }

    @Test
    void renderGameLogFlatNamesTheTurnAgainWhenATakeBackReturnsToIt() {
        // the game was on turn 17 (Bob's) and went back to turn 16 (Alice's), mid-turn: no new BEGIN_TURN
        List<BridgePublishedLogEntry> entries = List.of(
            new BridgePublishedLogEntry(0, bridgeLogEntry(0, "BEGIN_TURN", 16, "Alice", "Alice", null, null), null),
            new BridgePublishedLogEntry(1, bridgeLogEntry(1, "LAND_PLAYED", 16, "Alice", "Alice", "Island", null), null),
            new BridgePublishedLogEntry(2, bridgeLogEntry(2, "BEGIN_TURN", 17, "Bob", "Bob", null, null), null),
            new BridgePublishedLogEntry(3, bridgeLogEntry(3, "LAND_PLAYED", 17, "Bob", "Bob", "Swamp", null), null),
            new BridgePublishedLogEntry(4, null,
                "Player request: Alice goes back 3 priorities (turn 16, Precombat Main, Alice had priority)"),
            new BridgePublishedLogEntry(5, bridgeLogEntry(4, "SPELL_CAST", 16, "Alice", "Alice", "Opt", null), null)
        );

        assertThat(BridgeGameLogFormatter.renderGameLogFlat(entries, false))
            .isEqualTo(String.join("\n",
                "Turn 16 (Alice):",
                "Alice played Island",
                "Turn 17 (Bob):",
                "Bob played Swamp",
                "Player request: Alice goes back 3 priorities (turn 16, Precombat Main, Alice had priority)",
                "Turn 16 (Alice):",
                "Alice cast Opt"
            ));
    }

    @Test
    void renderGameLogFlatNamesTheTurnOfASliceThatStartsMidTurn() {
        List<BridgePublishedLogEntry> entries = List.of(
            new BridgePublishedLogEntry(7, bridgeLogEntry(7, "SPELL_CAST", 9, "Bob", "Bob", "Shock", "Alice"), null)
        );

        assertThat(BridgeGameLogFormatter.renderGameLogFlat(entries, true))
            .isEqualTo("Turn 9 (Bob):\nBob cast Shock targeting Alice");
    }

    @Test
    void buildGameHistoryResultFormatsTurnsAndPhaseHeaders() {
        List<BridgeLogEntry> events = List.of(
            new BridgeLogEntry(
                0,
                0,
                "LAND_PLAYED",
                3,
                "PRECOMBAT_MAIN",
                "PRECOMBAT_MAIN",
                "Alice",
                "Alice",
                "Island",
                null,
                0,
                true
            ),
            new BridgeLogEntry(
                1,
                1,
                "SPELL_CAST",
                3,
                "PRECOMBAT_MAIN",
                "PRECOMBAT_MAIN",
                "Alice",
                "Alice",
                "Lightning Bolt",
                "Bob",
                0,
                true
            )
        );

        GetGameHistoryTool.Result result = BridgeGameLogFormatter.buildGameHistoryResult(events, 2);

        assertThat(result.cursor).isEqualTo(2);
        assertThat(result.event_count).isEqualTo(2);
        assertThat(result.history).isEqualTo(String.join("\n",
            "Turn 3 (Alice):",
            "  Precombat Main:",
            "    - Alice played Island",
            "    - Alice cast Lightning Bolt targeting Bob",
            ""
        ));
    }

    private static BridgeLogEntry bridgeLogEntry(
            int index,
            String type,
            int turn,
            String activePlayer,
            String player,
            String cardName,
            String targetName
    ) {
        return new BridgeLogEntry(
            index,
            index,
            type,
            turn,
            "PRECOMBAT_MAIN",
            "PRECOMBAT_MAIN",
            activePlayer,
            player,
            cardName,
            targetName,
            0,
            true
        );
    }
}
