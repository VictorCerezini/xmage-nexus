package org.mage.test.cards.single.ltc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestMultiPlayerBaseWithRangeAll;

public class TheBalrogOfMoriaMultiplayerTest extends CardTestMultiPlayerBaseWithRangeAll {

    // When The Balrog of Moria dies, you may exile it. When you do, for each opponent,
    // exile up to one target creature that player controls.
    private static final String balrog = "The Balrog of Moria";
    private static final String murder = "Murder"; // {1}{B}{B} instant: destroy target creature

    private static final String bear = "Grizzly Bears";
    private static final String lion = "Silvercoat Lion";
    private static final String ogre = "Gray Ogre";

    // Player order: A -> D -> C -> B, so the targets are chosen for D, C and B in that order

    @Test
    public void testExilesTheCreatureChosenForEachOpponent() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, balrog);
        addCard(Zone.HAND, playerA, murder);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerB, ogre);
        addCard(Zone.BATTLEFIELD, playerC, lion);
        addCard(Zone.BATTLEFIELD, playerD, bear);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, murder, balrog);
        setChoice(playerA, true); // exile the Balrog
        addTarget(playerA, bear);
        addTarget(playerA, lion);
        addTarget(playerA, ogre);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertExileCount(playerA, balrog, 1);
        assertExileCount(playerD, bear, 1);
        assertExileCount(playerC, lion, 1);
        assertExileCount(playerB, ogre, 1);
    }

    @Test
    public void testAnOpponentWithoutATargetDoesNotStopTheOthers() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, balrog);
        addCard(Zone.HAND, playerA, murder);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerB, ogre);
        addCard(Zone.BATTLEFIELD, playerC, lion);
        addCard(Zone.BATTLEFIELD, playerD, bear);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, murder, balrog);
        setChoice(playerA, true); // exile the Balrog
        addTarget(playerA, TestPlayer.TARGET_SKIP); // nothing of D's
        addTarget(playerA, lion);
        addTarget(playerA, ogre);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertExileCount(playerA, balrog, 1);
        assertPermanentCount(playerD, bear, 1);
        assertExileCount(playerC, lion, 1);
        assertExileCount(playerB, ogre, 1);
    }
}
