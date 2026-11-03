package org.mage.test.cards.single.ltc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheBalrogOfMoriaTest extends CardTestPlayerBase {

    /**
     * Cycling {3}{R}. When you cycle The Balrog of Moria, create two Treasure tokens.
     */
    private static final String balrog = "The Balrog of Moria"; // {4}{B}{B}{R} 8/8
    private static final String reanimate = "Reanimate"; // {B} sorcery

    @Test
    public void testReanimateFromGraveyard() {
        addCard(Zone.GRAVEYARD, playerA, balrog);
        addCard(Zone.HAND, playerA, reanimate);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, reanimate, balrog);
        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, balrog, 1);
        assertLife(playerA, 20 - 7);
    }

    @Test
    public void testReanimateAfterCycling() {
        addCard(Zone.HAND, playerA, balrog);
        addCard(Zone.HAND, playerA, reanimate);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 5);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, reanimate, balrog);
        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertGraveyardCount(playerA, reanimate, 1);
        assertPermanentCount(playerA, balrog, 1);
        assertLife(playerA, 20 - 7);
    }

    private static final String animateDead = "Animate Dead"; // {1}{B} Aura, enchant creature card in a graveyard

    @Test
    public void testAnimateDeadAfterCycling() {
        addCard(Zone.HAND, playerA, balrog);
        addCard(Zone.HAND, playerA, animateDead);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 6);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateManaAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "{T}: Add {B}", 2); // not a Treasure: it would ask for a color
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, animateDead, balrog);
        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, balrog, 1);
        assertPermanentCount(playerA, animateDead, 1);
    }

    /**
     * Reported: after a rollback, Animate Dead on the cycled Balrog went to the graveyard at once
     * ("SBA: aura can be attached to creature only") while it worked on other creature cards.
     */
    @Test
    public void testAnimateDeadAfterCyclingAndRollback() {
        addCard(Zone.HAND, playerA, balrog);
        addCard(Zone.HAND, playerA, animateDead);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 6);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateManaAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "{T}: Add {B}", 2); // not a Treasure: it would ask for a color
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, animateDead, balrog);
        checkPermanentCount("first time", 1, PhaseStep.END_TURN, playerA, balrog, 1);

        rollbackTurns(3, PhaseStep.PRECOMBAT_MAIN, playerA, 2);
        rollbackAfterActionsStart();
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateManaAbility(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "{T}: Add {B}", 2); // not a Treasure: it would ask for a color
        castSpell(3, PhaseStep.POSTCOMBAT_MAIN, playerA, animateDead, balrog);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, balrog, 1);
        assertPermanentCount(playerA, animateDead, 1);
    }

    @Test
    public void testAnimateDeadAfterRollbackOfTheCycling() {
        addCard(Zone.HAND, playerA, balrog);
        addCard(Zone.HAND, playerA, animateDead);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 6);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);

        rollbackTurns(3, PhaseStep.PRECOMBAT_MAIN, playerA, 2);
        rollbackAfterActionsStart();
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateManaAbility(3, PhaseStep.POSTCOMBAT_MAIN, playerA, "{T}: Add {B}", 2); // not a Treasure: it would ask for a color
        castSpell(3, PhaseStep.POSTCOMBAT_MAIN, playerA, animateDead, balrog);
        rollbackAfterActionsEnd();

        setStopAt(3, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, balrog, 1);
        assertPermanentCount(playerA, animateDead, 1);
    }
}
