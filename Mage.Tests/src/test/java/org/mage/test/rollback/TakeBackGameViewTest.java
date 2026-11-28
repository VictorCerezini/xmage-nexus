package org.mage.test.rollback;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.GameState;
import mage.game.events.TableEvent;
import mage.view.GameView;
import mage.view.PlayerView;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the players are shown around a take-back: the game view sent after going back is the game as
 * it was there, and a look back at an earlier priority shows that point, not the game as it is now.
 */
public class TakeBackGameViewTest extends CardTestPlayerBase {

    /**
     * Records the game views the players are sent (as B's life at that moment) and the line saying the
     * game went back, in the order they go out.
     */
    private List<String> watchWhatThePlayersAreSent(int turnNum, PhaseStep step) {
        List<String> sent = new CopyOnWriteArrayList<>();
        runCode("watch what the players are sent", turnNum, step, playerA, (info, player, game) ->
                game.addTableEventListener(event -> {
                    if (event.getEventType() == TableEvent.EventType.UPDATE) {
                        sent.add((game.executingRollback() ? "game being left: " : "game: ")
                                + game.getPlayer(playerB.getId()).getLife());
                    } else if (event.getEventType() == TableEvent.EventType.INFO && event.getMessage() != null
                            && (event.getMessage().contains("goes back") || event.getMessage().contains("Rolling back"))) {
                        sent.add("went back");
                    }
                }));
        return sent;
    }

    private static void assertSentRightAfterGoingBack(List<String> sent, int lifeThere) {
        Assert.assertFalse("no game view of the game being left: " + sent,
                sent.stream().anyMatch(s -> s.startsWith("game being left")));
        int at = sent.indexOf("went back");
        Assert.assertTrue("it went back: " + sent, at >= 0);
        Assert.assertTrue("a game view follows: " + sent, at + 1 < sent.size());
        Assert.assertEquals("right after, the game as it was there: " + sent, "game: " + lifeThere, sent.get(at + 1));
    }

    @Test
    public void testGoingBackSendsTheGameAsItWasThere() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        playerA.setKeepUndoPoints(true);
        playerB.setKeepUndoPoints(true);
        List<String> sent = watchWhatThePlayersAreSent(1, PhaseStep.PRECOMBAT_MAIN);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        // back to B's priority, the bolt still on the stack
        undoLastPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        // B passed again: the bolt resolved once
        assertLife(playerB, 17);
        assertSentRightAfterGoingBack(sent, 20);
    }

    @Test
    public void testRollingBackATurnSendsTheGameAsItWasAtItsStart() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        List<String> sent = watchWhatThePlayersAreSent(3, PhaseStep.PRECOMBAT_MAIN);

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN);
        rollbackTurns(3, PhaseStep.POSTCOMBAT_MAIN, playerA, 0);

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        // the bolt isn't cast again after rolling back
        assertLife(playerB, 20);
        assertHandCount(playerA, "Lightning Bolt", 1);
        assertSentRightAfterGoingBack(sent, 20);
    }

    private static PlayerView viewOf(GameView view, TestPlayer player) {
        return view.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(player.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no view of " + player.getName()));
    }

    @Test
    public void testLookingBackShowsTheExileAsItWasThere() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1);
        addCard(Zone.HAND, playerA, "Swords to Plowshares", 1);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1);
        playerA.setKeepUndoPoints(true);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Swords to Plowshares", "Grizzly Bears");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        runCode("look back", 1, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            PlayerView now = viewOf(new GameView(game.getState(), game, playerA.getId(), null), playerB);
            Assert.assertEquals("the bears are in exile now", 1, now.getExile().size());
            Assert.assertTrue("and not on the battlefield", now.getBattlefield().isEmpty());

            GameState beforeCasting = null;
            int count = game.countUndoPriorities(playerA.getId());
            for (int steps = 1; steps <= count && beforeCasting == null; steps++) {
                GameState state = game.getUndoPriorityState(playerA.getId(), steps);
                if (state.getStack().isEmpty() && state.getExile().getAllCards(game).isEmpty()) {
                    beforeCasting = state;
                }
            }
            Assert.assertNotNull("a priority before casting it", beforeCasting);
            PlayerView back = viewOf(new GameView(beforeCasting, game, playerA.getId(), null), playerB);
            Assert.assertTrue("back there, the bears aren't in exile yet: " + back.getExile().keySet(), back.getExile().isEmpty());
            Assert.assertEquals("but on the battlefield", 1, back.getBattlefield().size());
        });

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertExileCount(playerB, "Grizzly Bears", 1);
    }
}
