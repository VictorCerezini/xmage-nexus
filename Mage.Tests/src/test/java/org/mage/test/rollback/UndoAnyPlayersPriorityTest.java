package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.GameState;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Going back steps through the priorities of all players, in the order they came, not only the
 * requester's (Game.undoPriorities): a player who had nothing to do for a while goes back to the
 * last thing that happened, not a whole round back.
 */
public class UndoAnyPlayersPriorityTest extends CardTestPlayerBase {

    /**
     * A casts a bolt at B and it resolves. Priorities since: A's before casting, A's with the bolt on
     * the stack, then B's with the bolt on the stack, then A's again with the bolt resolved.
     */
    private void boltResolvedWithBothKeepingPoints() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);
        playerB.setKeepUndoPoints(true);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
    }

    @Test
    public void testGoBackToTheOpponentsPriority() {
        boltResolvedWithBothKeepingPoints();
        // A goes back one: to B's priority, the bolt still on the stack
        undoLastPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        runCode("B has priority again", 1, PhaseStep.PRECOMBAT_MAIN, playerB, (info, player, game) -> {
            Assert.assertEquals(playerB.getId(), game.getPriorityPlayerId());
            Assert.assertEquals("the bolt is on the stack", 1, game.getStack().size());
            Assert.assertEquals(20, game.getPlayer(playerB.getId()).getLife());
        });
        rollbackAfterActionsEnd();

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        // B passed again: the bolt resolved once
        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        assertTapped("Mountain", true);
    }

    @Test
    public void testLookingBackShowsEveryonesPrioritiesInOrder() {
        boltResolvedWithBothKeepingPoints();
        runCode("look back", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals("the bolt resolved", 17, game.getPlayer(playerB.getId()).getLife());
            int count = game.countUndoPriorities(playerA.getId());
            Assert.assertEquals("the same for everyone", count, game.countUndoPriorities(playerB.getId()));

            GameState back1 = game.getUndoPriorityState(playerA.getId(), 1);
            Assert.assertEquals("B's priority", playerB.getId(), back1.getPriorityPlayerId());
            Assert.assertEquals(1, back1.getStack().size());

            GameState back2 = game.getUndoPriorityState(playerA.getId(), 2);
            Assert.assertEquals("A's priority with the bolt on the stack", playerA.getId(), back2.getPriorityPlayerId());
            Assert.assertEquals(1, back2.getStack().size());

            GameState back3 = game.getUndoPriorityState(playerA.getId(), 3);
            Assert.assertEquals("A's priority before casting it", playerA.getId(), back3.getPriorityPlayerId());
            Assert.assertEquals(0, back3.getStack().size());
            Assert.assertEquals(20, back3.getPlayer(playerB.getId()).getLife());
        });

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
    }

    @Test
    public void testAPriorityTheClientPassedUnseenIsSkipped() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        // B's client passes the bolt by itself, unseen (a model seat): not a point to go back to
        unseenPass(1, PhaseStep.PRECOMBAT_MAIN, playerB);
        // so A goes back to their own priority with the bolt on the stack
        undoLastPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        runCode("A has priority again", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals(playerA.getId(), game.getPriorityPlayerId());
            Assert.assertEquals("the bolt is on the stack", 1, game.getStack().size());
        });
        rollbackAfterActionsEnd();

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testTheTableKeepsTheChosenNumberForAllPlayersTogether() {
        gameOptions.undoPrioritySteps = 2;
        playerA.setKeepUndoPoints(true);
        playerB.setKeepUndoPoints(true);

        runCode("only two, whoever had them", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals(2, game.countUndoPriorities(playerA.getId()));
            Assert.assertEquals(2, game.countUndoPriorities(playerB.getId()));
            GameState back1 = game.getUndoPriorityState(playerA.getId(), 1);
            Assert.assertEquals("B passed last, before this main phase", playerB.getId(), back1.getPriorityPlayerId());
            Assert.assertNotNull(game.getUndoPriorityState(playerA.getId(), 2));
            Assert.assertNull(game.getUndoPriorityState(playerA.getId(), 3));
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }
}
