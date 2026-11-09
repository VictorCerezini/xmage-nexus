package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.GameOptions;
import mage.game.GameState;
import mage.game.match.MatchOptions;
import mage.players.Player;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Going back several of the player's priorities at once (Game.undoPriorities), seeing the game as it
 * was there without going back (Game.getUndoPriorityState), and how many priorities a table keeps
 * (GameOptions.undoPrioritySteps).
 */
public class UndoSeveralPrioritiesTest extends CardTestPlayerBase {

    /**
     * Stops on the upkeep, the main phase and the second main phase: the client passes the rest by itself.
     */
    private void stopOnlyInUpkeepAndMainPhases(int turn) {
        autoPass(turn, PhaseStep.DRAW, playerA);
        autoPass(turn, PhaseStep.BEGIN_COMBAT, playerA);
        autoPass(turn, PhaseStep.DECLARE_ATTACKERS, playerA);
        autoPass(turn, PhaseStep.END_COMBAT, playerA);
    }

    @Test
    public void testGoBackTwoPrioritiesAtOnce() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        stopOnlyInUpkeepAndMainPhases(3);
        // from the second main phase, past the main phase, back to the upkeep
        undoPriorities(3, PhaseStep.POSTCOMBAT_MAIN, playerA, 2);
        rollbackAfterActionsStart();
        runCode("back in the upkeep", 3, PhaseStep.UPKEEP, playerA, (info, player, game) -> {
            Assert.assertEquals(3, game.getTurnNum());
            Assert.assertEquals(PhaseStep.UPKEEP, game.getTurnStepType());
        });
        castSpell(3, PhaseStep.UPKEEP, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testSeeTheGameBackThereWithoutGoingBack() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);

        stopOnlyInUpkeepAndMainPhases(3);
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        runCode("look back", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) -> {
            int count = game.countUndoPriorities(playerA.getId());
            Assert.assertTrue("priorities to go back to: " + count, count >= 3);
            Assert.assertNull("not that many", game.getUndoPriorityState(playerA.getId(), count + 1));

            GameState last = game.getUndoPriorityState(playerA.getId(), 1);
            Assert.assertNotNull(last);
            Assert.assertNotSame("a kept copy, not the game", game.getState(), last);
            Assert.assertEquals(3, last.getTurnNum());
            Assert.assertEquals(PhaseStep.PRECOMBAT_MAIN, last.getTurnStepType());

            // further back: the upkeep, before the bolt
            GameState upkeep = null;
            for (int steps = 1; steps <= count && upkeep == null; steps++) {
                GameState state = game.getUndoPriorityState(playerA.getId(), steps);
                if (state.getTurnNum() == 3 && state.getTurnStepType() == PhaseStep.UPKEEP) {
                    upkeep = state;
                }
            }
            Assert.assertNotNull("the upkeep is there", upkeep);
            Player opponentThen = upkeep.getPlayer(playerB.getId());
            Assert.assertEquals("before the bolt", 20, opponentThen.getLife());

            // looking changed nothing
            Assert.assertEquals(PhaseStep.POSTCOMBAT_MAIN, game.getTurnStepType());
            Assert.assertEquals(17, game.getPlayer(playerB.getId()).getLife());
            Assert.assertEquals(count, game.countUndoPriorities(playerA.getId()));
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testTableKeepsOnlyTheChosenNumberOfPriorities() {
        gameOptions.undoPrioritySteps = 2;
        playerA.setKeepUndoPoints(true);

        runCode("only two", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals(2, game.countUndoPriorities(playerA.getId()));
            Assert.assertNotNull(game.getUndoPriorityState(playerA.getId(), 2));
            Assert.assertNull(game.getUndoPriorityState(playerA.getId(), 3));
            Assert.assertFalse("can't go back further than kept", game.undoPriorities(playerA.getId(), 3));
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    /**
     * While the game isn't at the player's own question (another player's priority, or a question
     * going out just before its point is kept, when the game view is made), every kept point is a
     * past one: still no more than the table's number.
     */
    @Test
    public void testNeverMoreThanTheTableKeepsOnAnotherPlayersPriority() {
        gameOptions.undoPrioritySteps = 2;
        playerA.setKeepUndoPoints(true);

        runCode("on B's priority", 3, PhaseStep.POSTCOMBAT_MAIN, playerB, (info, player, game) -> {
            Assert.assertEquals(2, game.countUndoPriorities(playerA.getId()));
            Assert.assertNotNull(game.getUndoPriorityState(playerA.getId(), 2));
            Assert.assertNull(game.getUndoPriorityState(playerA.getId(), 3));
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testNothingToSeeWithoutRollbacks() {
        gameOptions.rollbackTurnsAllowed = false;
        playerA.setKeepUndoPoints(true);

        runCode("no going back", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals(0, game.countUndoPriorities(playerA.getId()));
            Assert.assertNull(game.getUndoPriorityState(playerA.getId(), 1));
            Assert.assertFalse(game.undoPriorities(playerA.getId(), 1));
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testTableOptionLimits() {
        Assert.assertEquals(1, GameOptions.clampUndoPrioritySteps(0));
        Assert.assertEquals(1, GameOptions.clampUndoPrioritySteps(-5));
        Assert.assertEquals(7, GameOptions.clampUndoPrioritySteps(7));
        Assert.assertEquals(GameOptions.UNDO_PRIORITY_STEPS_MAX, GameOptions.clampUndoPrioritySteps(500));

        MatchOptions options = new MatchOptions("test", "Two Player Duel", false);
        Assert.assertEquals(GameOptions.UNDO_PRIORITY_STEPS_DEFAULT, options.getUndoPrioritySteps());
        options.setUndoPrioritySteps(99);
        Assert.assertEquals(GameOptions.UNDO_PRIORITY_STEPS_MAX, options.getUndoPrioritySteps());
        GameOptions tableOptions = new GameOptions();
        tableOptions.undoPrioritySteps = 3;
        Assert.assertEquals("a copy keeps it", 3, tableOptions.copy().undoPrioritySteps);
    }
}
