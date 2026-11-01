package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestMultiPlayerBaseWithRangeAll;

/**
 * A rollback to before a player lost brings that player back, unless they gave up.
 * Turn order: A, D, C, B.
 */
public class PlayerLostRollbackTest extends CardTestMultiPlayerBaseWithRangeAll {

    private void killPlayerDThenRollback() {
        setLife(playerD, 3);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerD);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        runCode("lost before the rollback", 1, PhaseStep.PRECOMBAT_MAIN, playerA,
                (info, player, game) -> Assert.assertTrue(playerD.hasLost() && playerD.hasLeft()));

        rollbackTurns(1, PhaseStep.POSTCOMBAT_MAIN, playerA, 0);
    }

    @Test
    public void testLostPlayerIsBackAndTakesTheirTurn() {
        killPlayerDThenRollback();

        setStopAt(2, PhaseStep.UPKEEP);
        setStrictChooseMode(false);
        execute();

        assertHasNotLostTheGame(playerD);
        assertLife(playerD, 3);
        assertActivePlayer(playerD);
    }

    @Test
    public void testLostPlayerCanBeAttackedAgain() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion");
        killPlayerDThenRollback();

        attack(5, playerA, "Silvercoat Lion", playerD);

        setStopAt(5, PhaseStep.END_TURN);
        setStrictChooseMode(false);
        execute();

        assertHasNotLostTheGame(playerD);
        assertLife(playerD, 1);
    }

    @Test
    public void testLostPlayerGetsTheirCardsBack() {
        addCard(Zone.HAND, playerD, "Mountain");
        addCard(Zone.BATTLEFIELD, playerD, "Silvercoat Lion");
        killPlayerDThenRollback();

        playLand(2, PhaseStep.PRECOMBAT_MAIN, playerD, "Mountain");
        attack(2, playerD, "Silvercoat Lion", playerC);

        setStopAt(2, PhaseStep.END_TURN);
        setStrictChooseMode(false);
        execute();

        assertHasNotLostTheGame(playerD);
        assertPermanentCount(playerD, "Mountain", 1);
        assertLife(playerC, 18);
    }

    @Test
    public void testPlayerWhoGaveUpAfterLosingStaysOut() {
        runCode("gave up", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> playerD.markGaveUp());
        killPlayerDThenRollback();

        setStopAt(2, PhaseStep.UPKEEP);
        setStrictChooseMode(false);
        execute();

        assertLostTheGame(playerD);
        assertActivePlayer(playerC);
    }

    @Test
    public void testPlayerWhoConcededStaysOut() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");

        concede(1, PhaseStep.PRECOMBAT_MAIN, playerD);
        rollbackTurns(1, PhaseStep.POSTCOMBAT_MAIN, playerA, 0);

        setStopAt(2, PhaseStep.UPKEEP);
        setStrictChooseMode(false);
        execute();

        assertLostTheGame(playerD);
        assertActivePlayer(playerC);
    }
}
