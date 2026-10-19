package mage.game;

import java.io.Serializable;
import java.util.Objects;

/**
 * A structured event entry for the bridge event log (LLM seats).
 * <p>
 * Captured server-side from GameEvent firings in {@link GameImpl#fireEvent},
 * buffered in-memory, and delivered to the player's client attached to game callbacks.
 * <p>
 * Human-readable descriptions are generated client-side from these
 * structured fields — the server stores raw data only.
 * <p>
 * Ported from mage-bench, where it is a record; written as a class here because the
 * engine compiles at Java 8. The accessors keep the record names.
 */
public final class BridgeLogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int index;            // buffer position, used as pull cursor
    private final int gameSeq;          // current gameSeq at time of recording, for correlation
    private final String type;          // GameEvent.EventType name (SPELL_CAST, LAND_PLAYED, etc.)
    private final int turn;             // turn number
    private final String phase;         // TurnPhase name (nullable)
    private final String step;          // PhaseStep name (nullable)
    private final String activePlayer;  // whose turn it is
    private final String player;        // who performed the action
    private final String cardName;      // card/permanent involved (nullable, redacted for hidden info)
    private final String targetName;    // target player/permanent (nullable)
    private final int amount;           // life/damage amount (0 if N/A)
    private final boolean visibleToAll; // false if this event contains player-private info

    public BridgeLogEntry(int index, int gameSeq, String type, int turn, String phase, String step,
                          String activePlayer, String player, String cardName, String targetName,
                          int amount, boolean visibleToAll) {
        this.index = index;
        this.gameSeq = gameSeq;
        this.type = type;
        this.turn = turn;
        this.phase = phase;
        this.step = step;
        this.activePlayer = activePlayer;
        this.player = player;
        this.cardName = cardName;
        this.targetName = targetName;
        this.amount = amount;
        this.visibleToAll = visibleToAll;
    }

    /**
     * Return a copy with cardName redacted (set to null).
     * Used for visibility filtering when delivering to non-owning players.
     */
    public BridgeLogEntry redacted() {
        return new BridgeLogEntry(
                index, gameSeq, type, turn, phase, step,
                activePlayer, player, null, targetName, amount, visibleToAll
        );
    }

    public int index() {
        return index;
    }

    public int gameSeq() {
        return gameSeq;
    }

    public String type() {
        return type;
    }

    public int turn() {
        return turn;
    }

    public String phase() {
        return phase;
    }

    public String step() {
        return step;
    }

    public String activePlayer() {
        return activePlayer;
    }

    public String player() {
        return player;
    }

    public String cardName() {
        return cardName;
    }

    public String targetName() {
        return targetName;
    }

    public int amount() {
        return amount;
    }

    public boolean visibleToAll() {
        return visibleToAll;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BridgeLogEntry)) {
            return false;
        }
        BridgeLogEntry other = (BridgeLogEntry) o;
        return index == other.index
                && gameSeq == other.gameSeq
                && turn == other.turn
                && amount == other.amount
                && visibleToAll == other.visibleToAll
                && Objects.equals(type, other.type)
                && Objects.equals(phase, other.phase)
                && Objects.equals(step, other.step)
                && Objects.equals(activePlayer, other.activePlayer)
                && Objects.equals(player, other.player)
                && Objects.equals(cardName, other.cardName)
                && Objects.equals(targetName, other.targetName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, gameSeq, type, turn, phase, step, activePlayer, player,
                cardName, targetName, amount, visibleToAll);
    }

    @Override
    public String toString() {
        return "BridgeLogEntry[index=" + index + ", gameSeq=" + gameSeq + ", type=" + type
                + ", turn=" + turn + ", phase=" + phase + ", step=" + step
                + ", activePlayer=" + activePlayer + ", player=" + player
                + ", cardName=" + cardName + ", targetName=" + targetName
                + ", amount=" + amount + ", visibleToAll=" + visibleToAll + "]";
    }
}
