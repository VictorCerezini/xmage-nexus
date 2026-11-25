package mage.server.recovery;

import mage.cards.decks.Deck;
import mage.game.Game;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A table's match as it's saved at the start of each regular turn, to bring it back after the server
 * stopped (a power cut): the game itself, and what the table around it needs.
 *
 * @author VictorCerezini
 */
public class SavedTable implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String serverVersion;
    private final MatchOptions options;
    private final String ownerName; // the table's host: they decide whether to go on without the players not back yet
    private final List<SavedSeat> seats; // in the table's seat order
    private final int startedGames;
    private final int draws;
    private final Game game; // saved at the start of a regular turn, it goes on from there (Game.playFromTurnStart)

    public SavedTable(String serverVersion, MatchOptions options, String ownerName, List<SavedSeat> seats,
                      int startedGames, int draws, Game game) {
        this.serverVersion = serverVersion;
        this.options = options;
        this.ownerName = ownerName;
        this.seats = new ArrayList<>(seats);
        this.startedGames = startedGames;
        this.draws = draws;
        this.game = game;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public MatchOptions getOptions() {
        return options;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public List<SavedSeat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    public int getStartedGames() {
        return startedGames;
    }

    public int getDraws() {
        return draws;
    }

    public Game getGame() {
        return game;
    }

    /**
     * A seat and its match player: who sits there, their deck for the next games and the match score.
     */
    public static class SavedSeat implements Serializable {

        private static final long serialVersionUID = 1L;

        private final PlayerType playerType;
        private final UUID playerId;
        private final String name;
        private final Deck deck;
        private final int wins;
        private final boolean quit;
        private final int priorityTimeLeft;

        public SavedSeat(PlayerType playerType, UUID playerId, String name, Deck deck, int wins, boolean quit, int priorityTimeLeft) {
            this.playerType = playerType;
            this.playerId = playerId;
            this.name = name;
            this.deck = deck;
            this.wins = wins;
            this.quit = quit;
            this.priorityTimeLeft = priorityTimeLeft;
        }

        public PlayerType getPlayerType() {
            return playerType;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getName() {
            return name;
        }

        public Deck getDeck() {
            return deck;
        }

        public int getWins() {
            return wins;
        }

        public boolean hasQuit() {
            return quit;
        }

        public int getPriorityTimeLeft() {
            return priorityTimeLeft;
        }
    }
}
