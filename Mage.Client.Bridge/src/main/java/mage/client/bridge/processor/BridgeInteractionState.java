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
    private int interactionsThisTurn = 0;
    private int maxInteractionsPerTurn = 25;
    private final List<String> cancelledSpells = new ArrayList<>();
    private int manaAnswersNotCounted = 0;

    /** Mana payment answers per turn that do not count toward the loop limit, so paying by hand is not cut off. */
    static final int MANA_ANSWERS_NOT_COUNTED_PER_TURN = 30;

    public void setMaxInteractionsPerTurn(int maxInteractionsPerTurn) {
        this.maxInteractionsPerTurn = maxInteractionsPerTurn;
    }

    public int maxInteractionsPerTurn() {
        return maxInteractionsPerTurn;
    }

    public int interactionsThisTurn() {
        return interactionsThisTurn;
    }

    public int incrementInteractionsThisTurn() {
        interactionsThisTurn++;
        return interactionsThisTurn;
    }

    /**
     * Takes back the interaction counted for a choose_action that paid mana from a source or the pool. Paying by
     * hand needs one answer per source, which should not use up the loop limit; the allowance stays bounded so a
     * payment that never progresses is still stopped.
     */
    public void uncountManaAnswer() {
        if (interactionsThisTurn > 0 && manaAnswersNotCounted < MANA_ANSWERS_NOT_COUNTED_PER_TURN) {
            interactionsThisTurn--;
            manaAnswersNotCounted++;
        }
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
        interactionsThisTurn = 0;
        manaAnswersNotCounted = 0;
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
        interactionsThisTurn = 0;
        manaAnswersNotCounted = 0;
        cancelledSpells.clear();
    }
}
