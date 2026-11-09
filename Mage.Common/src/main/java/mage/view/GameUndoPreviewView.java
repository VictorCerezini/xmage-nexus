package mage.view;

import java.io.Serializable;

/**
 * The game as it was some of a player's priorities back, shown without going back (Game.undoPriorities):
 * to the player while they choose how far to go back, and to everyone while the others are asked
 * to agree. Each player gets it as they saw it (their own hand, not the others').
 */
public class GameUndoPreviewView implements Serializable {

    private static final long serialVersionUID = 1L;

    private final GameView board; // not "gameView": clients take a "gameView" for the game itself
    private final int steps; // how many priorities back
    private final int available; // how many the player who goes back can go back at most
    private final boolean vote; // shown while the others are asked to agree
    private final String requesterName;

    public GameUndoPreviewView(GameView board, int steps, int available, boolean vote, String requesterName) {
        this.board = board;
        this.steps = steps;
        this.available = available;
        this.vote = vote;
        this.requesterName = requesterName;
    }

    public GameView getBoard() {
        return board;
    }

    public int getSteps() {
        return steps;
    }

    public int getAvailable() {
        return available;
    }

    public boolean isVote() {
        return vote;
    }

    public String getRequesterName() {
        return requesterName;
    }
}
