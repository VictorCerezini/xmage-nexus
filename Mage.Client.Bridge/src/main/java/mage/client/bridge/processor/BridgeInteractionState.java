package mage.client.bridge.processor;

import mage.view.GameView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BridgeInteractionState {
    private final Set<UUID> failedManaCasts = new HashSet<>();
    private UUID poolManaPayingForId = null;
    private int poolManaAttempts = 0;
    private List<BridgeManaPlanEntry> manaPlan = null;
    private Integer manaPlanAbilityIndex = null;
    private boolean manaPlanAutoTapFallback = true;
    private int lastTurnNumber = -1;
    private int maxCallsWithoutProgress = DEFAULT_MAX_CALLS_WITHOUT_PROGRESS;
    private int callsWithoutProgress = 0;
    private Integer lastCallGameSeq = null;
    private final List<String> cancelledSpells = new ArrayList<>();

    /**
     * How many choose_action / pass_priority calls in a row may leave the game where it was before the bridge
     * answers the prompt itself. The server moves the game sequence on at every new prompt, so a call that
     * changed nothing is one that started on the same sequence as the call before it. Real games never had more
     * than 3 in a row; busy turns with many calls that do move the game on are never cut off.
     */
    public static final int DEFAULT_MAX_CALLS_WITHOUT_PROGRESS = 10;

    public void setMaxCallsWithoutProgress(int maxCallsWithoutProgress) {
        this.maxCallsWithoutProgress = maxCallsWithoutProgress;
    }

    public int maxCallsWithoutProgress() {
        return maxCallsWithoutProgress;
    }

    public int callsWithoutProgress() {
        return callsWithoutProgress;
    }

    /**
     * Records the start of a choose_action or pass_priority call on the given game sequence. When the sequence
     * is the same as at the previous call's start, the previous call did not move the game on.
     */
    public int recordCall(int gameSeq) {
        if (lastCallGameSeq != null && lastCallGameSeq == gameSeq) {
            callsWithoutProgress++;
        } else {
            callsWithoutProgress = 0;
        }
        lastCallGameSeq = gameSeq;
        return callsWithoutProgress;
    }

    /** True once the model has made the allowed number of calls in a row that did not change the game. */
    public boolean stuck() {
        return callsWithoutProgress >= maxCallsWithoutProgress;
    }

    /** Called after the bridge has answered the stuck prompt itself, so the model gets the next one. */
    public void clearStuck() {
        callsWithoutProgress = 0;
    }

    public void recordCancelledSpell(String note) {
        if (note != null && !note.isBlank()) {
            cancelledSpells.add(note);
        }
    }

    /** The notes about spells cancelled since the last call, oldest first. */
    public List<String> drainCancelledSpells() {
        List<String> notes = List.copyOf(cancelledSpells);
        cancelledSpells.clear();
        return notes;
    }

    public void clearCancelledSpells() {
        cancelledSpells.clear();
    }

    public int lastTurnNumber() {
        return lastTurnNumber;
    }

    public void advanceTurn(GameView gameView) {
        if (gameView == null) {
            return;
        }
        int turn = gameView.getTurn();
        if (turn == lastTurnNumber) {
            return;
        }
        lastTurnNumber = turn;
        failedManaCasts.clear();
        resetPoolManaTracking();
        clearManaPlan();
    }

    public boolean failedManaCast(UUID objectId) {
        return failedManaCasts.contains(objectId);
    }

    public Set<UUID> failedManaCastsSnapshot() {
        return Set.copyOf(failedManaCasts);
    }

    public void markFailedManaCast(UUID objectId) {
        if (objectId != null) {
            failedManaCasts.add(objectId);
        }
    }

    public List<BridgeManaPlanEntry> manaPlan() {
        return manaPlan;
    }

    public void setManaPlan(List<BridgeManaPlanEntry> manaPlan, boolean autoTapFallback) {
        this.manaPlan = manaPlan == null ? null : new ArrayList<>(manaPlan);
        this.manaPlanAutoTapFallback = autoTapFallback;
        this.manaPlanAbilityIndex = null;
    }

    public void clearManaPlan() {
        manaPlan = null;
        manaPlanAbilityIndex = null;
        manaPlanAutoTapFallback = true;
    }

    public Integer manaPlanAbilityIndex() {
        return manaPlanAbilityIndex;
    }

    public void setManaPlanAbilityIndex(Integer manaPlanAbilityIndex) {
        this.manaPlanAbilityIndex = manaPlanAbilityIndex;
    }

    public Integer consumeManaPlanAbilityIndex() {
        Integer abilityIndex = manaPlanAbilityIndex;
        manaPlanAbilityIndex = null;
        return abilityIndex;
    }

    public boolean manaPlanAutoTapFallback() {
        return manaPlanAutoTapFallback;
    }

    public void resetPoolManaTracking() {
        poolManaPayingForId = null;
        poolManaAttempts = 0;
    }

    public int recordPoolManaAttempt(UUID payingForId) {
        if (payingForId != null && payingForId.equals(poolManaPayingForId)) {
            poolManaAttempts++;
        } else {
            poolManaPayingForId = payingForId;
            poolManaAttempts = 1;
        }
        return poolManaAttempts;
    }

    public void resetRuntimeState() {
        failedManaCasts.clear();
        resetPoolManaTracking();
        clearManaPlan();
        lastTurnNumber = -1;
        callsWithoutProgress = 0;
        lastCallGameSeq = null;
        cancelledSpells.clear();
    }
}
