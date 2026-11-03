package org.mage.test.cards.single.ltc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestCommander4Players;

public class TheBalrogOfMoriaCommanderTest extends CardTestCommander4Players {

    private static final String balrog = "The Balrog of Moria"; // {4}{B}{B}{R} 8/8, cycling {3}{R}
    private static final String reanimate = "Reanimate"; // {B} sorcery

    @Test
    public void testReanimateAfterCyclingInTheLibrary() {
        addCard(Zone.HAND, playerA, balrog);
        addCard(Zone.HAND, playerA, reanimate);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 5);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(5, PhaseStep.PRECOMBAT_MAIN, playerA, reanimate, balrog);
        setStopAt(5, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertGraveyardCount(playerA, reanimate, 1);
        assertPermanentCount(playerA, balrog, 1);
    }

    @Test
    public void testReanimateTheCommanderAfterCycling() {
        addCard(Zone.COMMAND, playerA, balrog);
        addCard(Zone.HAND, playerA, reanimate);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 7);
        addCard(Zone.HAND, playerB, "Remand"); // counter target spell, it goes to its owner's hand
        addCard(Zone.BATTLEFIELD, playerB, "Island", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, balrog);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerB, "Remand", balrog, balrog);
        setChoice(playerA, false); // to the hand, not the command zone
        checkHandCardCount("in hand", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, balrog, 1);

        activateAbility(5, PhaseStep.PRECOMBAT_MAIN, playerA, "Cycling");
        setChoice(playerA, false); // stays in the graveyard
        waitStackResolved(5, PhaseStep.PRECOMBAT_MAIN);
        checkGraveyardCount("cycled", 5, PhaseStep.PRECOMBAT_MAIN, playerA, balrog, 1);
        castSpell(5, PhaseStep.POSTCOMBAT_MAIN, playerA, reanimate, balrog);
        setStopAt(5, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        assertGraveyardCount(playerA, reanimate, 1);
        assertPermanentCount(playerA, balrog, 1);
    }
}
