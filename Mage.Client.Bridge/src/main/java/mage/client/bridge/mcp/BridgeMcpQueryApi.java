package mage.client.bridge.mcp;

import mage.client.bridge.processor.BridgePublishedGameState;
import mage.client.bridge.processor.BridgePublishedLogEntry;
import mage.client.bridge.processor.BridgePublishedQuerySnapshot;
import mage.client.bridge.processor.BridgePublishedQueryState;
import mage.client.bridge.tools.ActionResult;
import mage.client.bridge.tools.GetGameHistoryTool;
import mage.client.bridge.tools.GetGameLogTool;
import mage.client.bridge.tools.GetGameStateTool;
import mage.client.bridge.tools.GetOracleTextTool;
import mage.game.BridgeLogEntry;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class BridgeMcpQueryApi {
    private final String username;
    private final Logger logger;
    private final BridgePublishedQueryState publishedQueryState;
    private final Supplier<Map<String, Object>> decklistSnapshotSupplier;
    private final Runnable awaitPublishedReadBarrier;

    public BridgeMcpQueryApi(
            String username,
            Logger logger,
            BridgePublishedQueryState publishedQueryState,
            Supplier<Map<String, Object>> decklistSnapshotSupplier,
            Runnable awaitPublishedReadBarrier) {
        this.username = username;
        this.logger = logger;
        this.publishedQueryState = publishedQueryState;
        this.decklistSnapshotSupplier = decklistSnapshotSupplier;
        this.awaitPublishedReadBarrier = awaitPublishedReadBarrier;
    }

    public boolean isActionPending() {
        return snapshotForRead().actionChoices().actionPending();
    }

    public ActionResult getActionChoices(Long boardCursorParam) {
        return snapshotForRead().actionChoices().copyForRead(boardCursorParam);
    }

    public ActionResult getActionChoicesSafe(Long boardCursorParam) {
        return getActionChoices(boardCursorParam);
    }

    public GetGameLogTool.Result getGameLogChunk(int maxChars, Integer cursor) {
        BridgeGameLogSnapshot snapshot = snapshotGameLog();
        List<BridgePublishedLogEntry> allEntries = snapshot.entries();

        if (cursor != null) {
            int effectiveCursor = normalizeSnapshotCursor(snapshot, cursor);
            List<BridgePublishedLogEntry> responseEntries = allEntries.stream()
                .filter(entry -> entry.seq() >= effectiveCursor)
                .toList();

            boolean afterFirstTurn = allEntries.stream()
                .anyMatch(entry -> entry.seq() < effectiveCursor && isBeginTurn(entry));

            String rendered = renderGameLogFlat(responseEntries, afterFirstTurn);
            GetGameLogTool.Result result = buildGameLogResult(snapshot, rendered, null, maxChars);
            if (effectiveCursor != cursor) {
                result.cursor_reset = true;
            }
            return result;
        }

        String rendered = renderGameLogFlat(allEntries, false);
        return buildGameLogResult(snapshot, rendered, rendered.length(), maxChars);
    }

    /** The log from the game's turn sinceTurn on (the game's own turn number, as the context's "T16"). */
    public GetGameLogTool.Result getGameLogSinceTurn(int sinceTurn) {
        BridgeGameLogSnapshot snapshot = snapshotGameLog();
        List<BridgePublishedLogEntry> allEntries = snapshot.entries();

        String allRendered = renderGameLogFlat(allEntries, false);

        // the first game event of that turn or a later one, after setup; with take-backs the log
        // keeps what was undone, so this is its first time at that turn
        int startCursor = -1;
        boolean seenFirstTurn = false;
        for (BridgePublishedLogEntry entry : allEntries) {
            if (!entry.isBridgeEvent()) {
                continue;
            }
            seenFirstTurn |= isBeginTurn(entry);
            if (seenFirstTurn && entry.bridgeEvent().turn() >= sinceTurn) {
                startCursor = entry.seq();
                break;
            }
        }

        if (startCursor < 0) {
            GetGameLogTool.Result result = buildGameLogResult(snapshot, "", allRendered.length(), null);
            result.truncated = false;
            return result;
        }
        int effectiveCursor = startCursor;
        List<BridgePublishedLogEntry> subset = allEntries.stream()
            .filter(entry -> entry.seq() >= effectiveCursor)
            .toList();
        GetGameLogTool.Result result = buildGameLogResult(
                snapshot,
                renderGameLogFlat(subset, true),
                allRendered.length(),
                null
        );
        result.truncated = false;
        result.since_turn = sinceTurn;
        return result;
    }

    private static boolean isBeginTurn(BridgePublishedLogEntry entry) {
        return entry.isBridgeEvent() && "BEGIN_TURN".equals(entry.bridgeEvent().type());
    }

    public GetGameHistoryTool.Result getGameHistory(Integer sinceTurn, Integer sinceCursor) {
        BridgeGameLogSnapshot snapshot = snapshotGameLog();
        int effectiveCursor = sinceCursor != null
            ? normalizeHistoryCursor(snapshot, sinceCursor)
            : snapshot.firstCursor();
        List<BridgeLogEntry> events = snapshot.entries().stream()
            .filter(BridgePublishedLogEntry::isBridgeEvent)
            .filter(entry -> entry.seq() >= effectiveCursor)
            .map(BridgePublishedLogEntry::bridgeEvent)
            .toList();

        if (sinceTurn != null) {
            events = events.stream()
                    .filter(e -> e.turn() >= sinceTurn)
                    .toList();
        }
        return BridgeGameLogFormatter.buildGameHistoryResult(events, snapshot.nextCursor());
    }

    public GetGameStateTool.Result getGameState(Long snapshotId) {
        return buildGameStateFromPublished(snapshotId, snapshotGameStateForRead());
    }

    public GetGameStateTool.Result getGameState() {
        return getGameState(null);
    }

    public Map<String, Object> getMyDecklist() {
        awaitPublishedReadBarrier.run();
        return decklistSnapshotSupplier.get();
    }

    public GetOracleTextTool.Result getOracleText(
            String cardName,
            String objectId,
            String[] cardNames,
            String[] objectIds) {
        awaitPublishedReadBarrier.run();
        return BridgePublishedOracleQuery.getOracleText(
            publishedQueryState.oracleIndex(),
            cardName,
            objectId,
            cardNames,
            objectIds
        );
    }

    private BridgeGameLogSnapshot snapshotGameLog() {
        awaitPublishedReadBarrier.run();
        var gameLog = publishedQueryState.snapshot().gameLog();
        return new BridgeGameLogSnapshot(gameLog.entries(), gameLog.firstCursor(), gameLog.nextCursor());
    }

    private BridgePublishedGameState snapshotGameStateForRead() {
        return snapshotForRead().gameState();
    }

    private String renderGameLogFlat(List<BridgePublishedLogEntry> entries, boolean afterFirstTurn) {
        return BridgeGameLogFormatter.renderGameLogFlat(entries, afterFirstTurn);
    }

    private GetGameStateTool.Result buildGameStateFromPublished(Long snapshotId, BridgePublishedGameState snapshot) {
        if (!snapshot.available()) {
            var unavailable = new GetGameStateTool.Result();
            unavailable.available = false;
            unavailable.error = snapshot.error();
            return unavailable;
        }
        if (snapshotId != null && snapshotId.longValue() == snapshot.snapshotId().longValue()) {
            var unchanged = new GetGameStateTool.Result();
            unchanged.available = true;
            unchanged.unchanged = true;
            unchanged.snapshot_id = snapshot.snapshotId();
            return unchanged;
        }
        var state = new GetGameStateTool.Result();
        state.available = true;
        state.snapshot_id = snapshot.snapshotId();
        state.game_seq = snapshot.gameSeq();
        String step = snapshot.step() != null ? snapshot.step() : "null";
        logger.debug("[" + username + "] getGameState returning game_seq="
                + snapshot.gameSeq() + " step=" + step
                + " thread=" + Thread.currentThread().getName());
        state.turn = snapshot.turn();
        state.phase = snapshot.phase();
        state.step = snapshot.step();
        state.active_player = snapshot.activePlayer();
        state.priority_player = snapshot.priorityPlayer();
        state.players = snapshot.players();
        state.stack = snapshot.stack();
        state.combat = snapshot.combat();
        return state;
    }

    private BridgePublishedQuerySnapshot snapshotForRead() {
        awaitPublishedReadBarrier.run();
        return publishedQueryState.snapshot();
    }

    private GetGameLogTool.Result buildGameLogResult(
            BridgeGameLogSnapshot snapshot,
            String rendered,
            Integer totalLength,
            Integer maxChars) {
        return BridgeGameLogFormatter.buildGameLogResult(snapshot.nextCursor(), rendered, totalLength, maxChars);
    }

    private int normalizeSnapshotCursor(BridgeGameLogSnapshot snapshot, int requestedCursor) {
        if (requestedCursor < snapshot.firstCursor() || requestedCursor > snapshot.nextCursor()) {
            return snapshot.firstCursor();
        }
        return requestedCursor;
    }

    private int normalizeHistoryCursor(BridgeGameLogSnapshot snapshot, int requestedCursor) {
        if (requestedCursor < snapshot.firstCursor()) {
            return snapshot.firstCursor();
        }
        if (requestedCursor > snapshot.nextCursor()) {
            return snapshot.nextCursor();
        }
        return requestedCursor;
    }
}
