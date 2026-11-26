package mage.server.game;

import mage.MageException;
import mage.game.Game;
import mage.util.ThreadUtils;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Game: main thread to process full game (one thread per game)
 *
 * @author BetaSteward_at_googlemail.com, JayDi85
 */
public class GameWorker implements Callable<Boolean> {

    private static final Logger LOGGER = Logger.getLogger(GameWorker.class);

    private final GameCallback gameController;
    private final Game game;
    private final UUID choosingPlayerId;
    private final List<UUID> concedeOnResume; // a game brought back: the players the host went on without; null for a new game

    public GameWorker(Game game, UUID choosingPlayerId, GameCallback gameController) {
        this.game = game;
        this.choosingPlayerId = choosingPlayerId;
        this.gameController = gameController;
        this.concedeOnResume = null;
    }

    /**
     * A game brought back after the server stopped: it goes on from the saved turn start
     * (Game.playFromTurnStart), after the players the host went on without concede.
     */
    public GameWorker(Game game, List<UUID> concedeOnResume, GameCallback gameController) {
        this.game = game;
        this.choosingPlayerId = null;
        this.gameController = gameController;
        this.concedeOnResume = new ArrayList<>(concedeOnResume);
    }

    @Override
    public Boolean call() {
        boolean endProcessed = false;
        try {
            // play game
            ThreadUtils.setGameThreadStatus(game.getId(), "");
            if (concedeOnResume == null) {
                game.start(choosingPlayerId);
            } else {
                for (UUID playerId : concedeOnResume) {
                    game.concede(playerId);
                }
                game.playFromTurnStart();
            }

            // save result and start next game or close finished table
            game.fireUpdatePlayersEvent(); // TODO: no needs in update event (gameController.endGameWithResult already send game end dialog)?
            endProcessed = true; // errors after that point must not close a game twice
            gameController.endGameWithResult(game.getWinner());

            // clear resources
            game.cleanUp();// TODO: no needs in cleanup code (cards list are useless for memory optimization, game states are more important)?
        } catch (MageException e) {
            LOGGER.fatal("GameWorker mage error [" + game.getId() + " - " + game + "]: " + e, e);
            if (!endProcessed) {
                gameController.endGameWithError(e);
            }
        } catch (Throwable e) {
            LOGGER.fatal("GameWorker system error [" + game.getId() + " - " + game + "]: " + e, e);
            if (!endProcessed) {
                gameController.endGameWithError(e);
            }
        } finally {
            // a pool keeps a finished thread alive for a while, so it must not look like a game thread
            // in dumps and stats, and must not pass game thread checks by name
            ThreadUtils.setGameThreadIdle(game.getId());
        }
        return null;
    }
}
