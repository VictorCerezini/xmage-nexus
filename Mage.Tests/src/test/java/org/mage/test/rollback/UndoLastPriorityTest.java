package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Going back to the player's last priority (Game.undoLastPriority): the game is put back as it was
 * then and goes on from there, without playing again what happened before that priority.
 */
public class UndoLastPriorityTest extends CardTestPlayerBase {

    @Test
    public void testSpellCastGoesBackToHand() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        // next priority, with the bolt on the stack: back to before casting it
        undoLastPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 20);
        assertHandCount(playerA, "Lightning Bolt", 1);
        assertGraveyardCount(playerA, "Lightning Bolt", 0);
        assertTapped("Mountain", false);
    }

    @Test
    public void testGoBackToAnEarlierStep() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        // passed the main phase without casting, goes back from the beginning of combat
        undoLastPriority(1, PhaseStep.BEGIN_COMBAT, playerA);
        rollbackAfterActionsStart();
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testFirstPriorityHasNothingToGoBackTo() {
        playerA.setKeepUndoPoints(true);
        runCode("first priority", 1, PhaseStep.UPKEEP, playerA, (info, player, game) ->
                Assert.assertFalse("nothing before the first priority", game.canUndoLastPriority(playerA.getId())));
        runCode("second priority", 1, PhaseStep.UPKEEP, playerA, (info, player, game) ->
                Assert.assertTrue("the first priority is there now", game.canUndoLastPriority(playerA.getId())));

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testStepTriggersDontTriggerAgain() {
        // At the beginning of your upkeep, you draw a card and you lose 1 life.
        addCard(Zone.BATTLEFIELD, playerA, "Phyrexian Arena", 1);

        // back from the draw step to the upkeep, after the trigger resolved (turns 1 and 3)
        undoLastPriority(3, PhaseStep.DRAW, playerA);

        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerA, 20 - 2);
    }

    @Test
    public void testGoBackToThePreviousTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        // passed the end of their turn, goes back from the opponent's upkeep
        undoLastPriority(2, PhaseStep.UPKEEP, playerA);
        rollbackAfterActionsStart();
        runCode("back in turn 1", 1, PhaseStep.END_TURN, playerA, (info, player, game) -> {
            Assert.assertEquals(1, game.getTurnNum());
            Assert.assertEquals(playerA.getId(), game.getActivePlayerId());
        });
        castSpell(1, PhaseStep.END_TURN, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertActivePlayer(playerA);
        Assert.assertEquals(3, currentGame.getTurnNum());
    }

    @Test
    public void testGoBackIntoAnExtraMainPhase() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        // Untap all creatures that attacked this turn. After this main phase, there is an additional
        // combat phase followed by an additional main phase.
        addCard(Zone.HAND, playerA, "Relentless Assault", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        attack(3, playerA, "Silvercoat Lion");
        castSpell(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "Relentless Assault");
        // no attack in the extra combat: the test player would try both attacks in the first combat

        // from the end step, back to the extra main phase
        undoLastPriority(3, PhaseStep.END_TURN, playerA);
        rollbackAfterActionsStart();
        runCode("in the extra main phase", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) ->
                Assert.assertNotSame("must be the extra main phase, not the turn's own",
                        game.getTurn().getPhase(TurnPhase.POSTCOMBAT_MAIN), game.getTurn().getPhase()));
        castSpell(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(4, PhaseStep.UPKEEP);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 20 - 2 - 3);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        assertActivePlayer(playerB);
    }

    @Test
    public void testGoBackIntoAnExtraCombatWithTheExtraMainPhaseStillToCome() {
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        addCard(Zone.HAND, playerA, "Relentless Assault", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        attack(3, playerA, "Silvercoat Lion");
        castSpell(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "Relentless Assault");
        // no attack in the extra combat: the test player would try both attacks in the first combat

        // from the end step back to the extra main phase, then back to the end of the extra combat
        undoLastPriority(3, PhaseStep.END_TURN, playerA);
        rollbackAfterActionsStart();
        undoLastPriority(3, PhaseStep.POSTCOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        runCode("in the extra combat", 3, PhaseStep.END_COMBAT, playerA, (info, player, game) ->
                Assert.assertNotSame("must be the extra combat, not the turn's own",
                        game.getTurn().getPhase(TurnPhase.COMBAT), game.getTurn().getPhase()));
        castSpell(3, PhaseStep.END_COMBAT, playerA, "Lightning Bolt", playerB);
        runCode("the extra main phase follows", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) ->
                Assert.assertNotSame("must be the extra main phase, not the turn's own",
                        game.getTurn().getPhase(TurnPhase.POSTCOMBAT_MAIN), game.getTurn().getPhase()));
        rollbackAfterActionsEnd();

        setStopAt(4, PhaseStep.UPKEEP);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 20 - 2 - 3);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        assertActivePlayer(playerB);
    }

    @Test
    public void testGoBackFromAnExtraTurnToTheTurnBefore() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        // Take an extra turn after this one.
        addCard(Zone.HAND, playerA, "Time Walk", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Time Walk");
        // the extra turn is turn 2: back to the end of turn 1, the extra turn is still to come
        undoLastPriority(2, PhaseStep.UPKEEP, playerA);
        rollbackAfterActionsStart();
        runCode("back in turn 1", 1, PhaseStep.END_TURN, playerA, (info, player, game) -> {
            Assert.assertEquals(1, game.getTurnNum());
            Assert.assertFalse(game.getState().isExtraTurn());
        });
        runCode("the extra turn", 2, PhaseStep.DRAW, playerA, (info, player, game) -> {
            Assert.assertEquals(playerA.getId(), game.getActivePlayerId());
            Assert.assertTrue(game.getState().isExtraTurn());
        });
        runCode("then the opponent's turn", 3, PhaseStep.UPKEEP, playerA, (info, player, game) -> {
            Assert.assertEquals(playerB.getId(), game.getActivePlayerId());
            Assert.assertFalse(game.getState().isExtraTurn());
        });
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertActivePlayer(playerB);
        assertGraveyardCount(playerA, "Time Walk", 1);
    }

    @Test
    public void testTurnRollbackForgetsThePointsToGoBackTo() {
        playerA.setKeepUndoPoints(true);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);

        rollbackTurns(1, PhaseStep.POSTCOMBAT_MAIN, playerA, 0);
        rollbackAfterActionsStart();
        runCode("after the rollback", 1, PhaseStep.UPKEEP, playerA, (info, player, game) ->
                Assert.assertFalse("the points were after the turn's start", game.canUndoLastPriority(playerA.getId())));
        rollbackAfterActionsEnd();

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testAutoPassedPriorityIsSkipped() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        // the client passed the draw step by itself: going back from the main phase skips it
        autoPass(3, PhaseStep.DRAW, playerA);
        undoLastPriority(3, PhaseStep.PRECOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        castSpell(3, PhaseStep.UPKEEP, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testAutoPassBeforeThePointIsKept() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);

        // the client's pass can come before the point is kept (the question goes out first)
        runCode("auto pass first", 3, PhaseStep.DRAW, playerA, (info, player, game) -> {
            game.forgetUndoPoint(playerA.getId());
            game.saveUndoPoint(playerA.getId());
        });
        // next priority in the draw step: back to the upkeep, not to the auto-passed priority
        undoLastPriority(3, PhaseStep.DRAW, playerA);
        rollbackAfterActionsStart();
        castSpell(3, PhaseStep.UPKEEP, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
    }

    @Test
    public void testGoBackTwiceOverAutoPassedPriorities() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        // stops: upkeep, main phase and second main phase; the client passed the rest by itself
        autoPass(3, PhaseStep.DRAW, playerA);
        autoPass(3, PhaseStep.BEGIN_COMBAT, playerA);
        autoPass(3, PhaseStep.DECLARE_ATTACKERS, playerA);
        autoPass(3, PhaseStep.END_COMBAT, playerA);
        // from the second main phase, back to the main phase
        undoLastPriority(3, PhaseStep.POSTCOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        // and right away, back to the upkeep
        undoLastPriority(3, PhaseStep.PRECOMBAT_MAIN, playerA);
        rollbackAfterActionsStart();
        castSpell(3, PhaseStep.UPKEEP, playerA, "Lightning Bolt", playerB);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertLife(playerB, 17);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }
}
