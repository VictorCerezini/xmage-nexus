package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.GameState;
import mage.view.GameView;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A player looks back at their priorities while the game isn't waiting on them (Game.holdForUndo):
 * the game stops before the next priority of anyone, until it's released or goes back. The server
 * holds and releases it from another thread, as these tests do.
 */
public class UndoHoldTest extends CardTestPlayerBase {

    private static final long WAIT_MS = 10_000;

    /**
     * Stops on the upkeep, the main phase and the second main phase: the client passes the rest by itself.
     */
    private void stopOnlyInUpkeepAndMainPhases(int turn) {
        autoPass(turn, PhaseStep.DRAW, playerA);
        autoPass(turn, PhaseStep.BEGIN_COMBAT, playerA);
        autoPass(turn, PhaseStep.DECLARE_ATTACKERS, playerA);
        autoPass(turn, PhaseStep.END_COMBAT, playerA);
    }

    private static boolean waitUntilReady(Game game) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (!game.isUndoHoldReady() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        return game.isUndoHoldReady();
    }

    private static void startHelper(Runnable code) {
        Thread helper = new Thread(code, "undo hold test helper");
        helper.setDaemon(true);
        helper.start();
    }

    @Test
    public void testGameWaitsBeforeTheNextPriorityUntilReleased() {
        playerA.setKeepUndoPoints(true);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean stayed = new AtomicBoolean();
        AtomicBoolean releasedWhileWaiting = new AtomicBoolean();
        AtomicReference<UUID> priorityWhileHeld = new AtomicReference<>();
        AtomicReference<PhaseStep> stepWhileHeld = new AtomicReference<>();

        runCode("A looks back", 3, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertTrue(game.holdForUndo(playerA.getId()));
            Assert.assertEquals(playerA.getId(), game.getUndoHolder());
            Assert.assertFalse("still in A's priority", game.isUndoHoldReady());
            Assert.assertNull("copies (AI simulations) don't hold", game.copy().getUndoHolder());
            startHelper(() -> {
                try {
                    ready.set(waitUntilReady(game));
                    priorityWhileHeld.set(game.getPriorityPlayerId());
                    stepWhileHeld.set(game.getTurnStepType());
                    int turn = game.getTurnNum();
                    Thread.sleep(300);
                    stayed.set(game.isUndoHoldReady() && turn == game.getTurnNum()
                            && stepWhileHeld.get() == game.getTurnStepType()
                            && priorityWhileHeld.get().equals(game.getPriorityPlayerId()));
                } catch (InterruptedException ignore) {
                } finally {
                    releasedWhileWaiting.set(game.releaseUndoHold(playerA.getId()));
                }
            });
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        Assert.assertTrue("the game stopped", ready.get());
        // running the code is an action, so A gets priority again: the holder waits too
        Assert.assertEquals("before A's next priority", playerA.getId(), priorityWhileHeld.get());
        Assert.assertEquals(PhaseStep.PRECOMBAT_MAIN, stepWhileHeld.get());
        Assert.assertTrue("and stayed there", stayed.get());
        Assert.assertTrue("released while it waited", releasedWhileWaiting.get());
        Assert.assertNull(currentGame.getUndoHolder());
        Assert.assertEquals("then went on", PhaseStep.END_TURN, currentGame.getTurnStepType());
    }

    @Test
    public void testGoBackWhileTheGameIsHeld() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);
        stopOnlyInUpkeepAndMainPhases(3);
        AtomicBoolean wentBack = new AtomicBoolean();

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        runCode("A looks back", 3, PhaseStep.POSTCOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertEquals("the bolt hit", 17, game.getPlayer(playerB.getId()).getLife());
            Assert.assertTrue(game.holdForUndo(playerA.getId()));
            startHelper(() -> {
                try {
                    if (!waitUntilReady(game)) {
                        return;
                    }
                    // back to the upkeep, before the bolt
                    int count = game.countUndoPriorities(playerA.getId());
                    for (int steps = 1; steps <= count; steps++) {
                        GameState state = game.getUndoPriorityState(playerA.getId(), steps);
                        if (state.getTurnNum() == 3 && state.getTurnStepType() == PhaseStep.UPKEEP) {
                            wentBack.set(game.undoPriorities(playerA.getId(), steps));
                            break;
                        }
                    }
                } catch (InterruptedException ignore) {
                } finally {
                    if (!wentBack.get()) {
                        game.releaseUndoHold(playerA.getId());
                    }
                }
            });
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        Assert.assertTrue("went back", wentBack.get());
        Assert.assertNull("nobody holds it after going back", currentGame.getUndoHolder());
        // the bolt was cast in the main phase it went back past, and isn't cast again
        assertLife(playerB, 20);
        assertHandCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void testOnlyOnePlayerHoldsIt() {
        runCode("A and B", 3, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertTrue(game.holdForUndo(playerA.getId()));
            Assert.assertFalse("B can't while A holds it", game.holdForUndo(playerB.getId()));
            Assert.assertTrue("A again is fine", game.holdForUndo(playerA.getId()));
            Assert.assertFalse("B can't release A's", game.releaseUndoHold(playerB.getId()));
            Assert.assertEquals(playerA.getId(), game.getUndoHolder());
            game.releaseUndoHold(playerA.getId());
            Assert.assertNull(game.getUndoHolder());
            Assert.assertFalse("released, nothing to wait for", game.isUndoHoldReady());
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testTheGameViewSaysWhoHoldsIt() {
        runCode("A looks back", 3, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            GameView before = new GameView(game.getState(), game, playerB.getId(), null);
            Assert.assertNull(before.getUndoHeldBy());
            Assert.assertTrue(game.holdForUndo(playerA.getId()));
            Assert.assertEquals("B is told who stopped the game", playerA.getName(),
                    new GameView(game.getState(), game, playerB.getId(), null).getUndoHeldBy());
            Assert.assertNull("A isn't", new GameView(game.getState(), game, playerA.getId(), null).getUndoHeldBy());
            Assert.assertEquals("spectators are", playerA.getName(),
                    new GameView(game.getState(), game, null, UUID.randomUUID()).getUndoHeldBy());
            // the server sends a view built earlier again when asked from another thread
            before.refreshUndoHeldBy(game, playerB.getId());
            Assert.assertEquals("a view built before says it once refreshed", playerA.getName(), before.getUndoHeldBy());
            game.releaseUndoHold(playerA.getId());
            before.refreshUndoHeldBy(game, playerB.getId());
            Assert.assertNull("and stops saying it once the game goes on", before.getUndoHeldBy());
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }

    @Test
    public void testNoHoldWithoutRollbacks() {
        gameOptions.rollbackTurnsAllowed = false;

        runCode("no going back", 3, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            Assert.assertFalse(game.holdForUndo(playerA.getId()));
            Assert.assertNull(game.getUndoHolder());
        });

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();
    }
}
