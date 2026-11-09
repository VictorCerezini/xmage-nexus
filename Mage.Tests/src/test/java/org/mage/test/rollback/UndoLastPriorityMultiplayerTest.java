package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestMultiPlayerBaseWithRangeAll;

/**
 * Going back to a priority before a player lost brings that player back, like a turn rollback.
 * Turn order: A, D, C, B.
 */
public class UndoLastPriorityMultiplayerTest extends CardTestMultiPlayerBaseWithRangeAll {

    @Test
    public void testPlayerWhoLostAfterThatPriorityIsBack() {
        setLife(playerD, 2);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);

        attack(1, playerA, "Silvercoat Lion", playerD);
        // the combat damage made D lose: back to the declare blockers step, before the damage
        undoLastPriority(1, PhaseStep.COMBAT_DAMAGE, playerA);
        rollbackAfterActionsStart();
        runCode("D is back", 1, PhaseStep.DECLARE_BLOCKERS, playerA, (info, player, game) -> {
            Assert.assertFalse("back in the game", playerD.hasLost());
            Assert.assertTrue("back in the game", playerD.isInGame());
            Assert.assertEquals(2, playerD.getLife());
        });
        rollbackAfterActionsEnd();

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        // nothing else changed, so the damage makes D lose again
        assertLostTheGame(playerD);
    }

    @Test
    public void testOtherPlayersOrderStaysTheSame() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        // passed the end of their turn, goes back from the next player's upkeep
        undoLastPriority(2, PhaseStep.UPKEEP, playerA);
        rollbackAfterActionsStart();
        castSpell(1, PhaseStep.END_TURN, playerA, "Lightning Bolt", playerC);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerC, 17);
        assertActivePlayer(playerC);
    }
}
