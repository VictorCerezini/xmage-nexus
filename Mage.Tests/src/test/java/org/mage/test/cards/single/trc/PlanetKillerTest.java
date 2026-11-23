package org.mage.test.cards.single.trc;

import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * @author VictorCerezini
 */
public class PlanetKillerTest extends CardTestPlayerBase {

    /**
     * Planet Killer {8}
     * Artifact Creature - Construct 9/9
     * Flying, indestructible
     * This creature attacks each combat if able.
     * Whenever this creature deals combat damage to a player, put a +1/+1 counter on it for each land that player controls.
     */
    private static final String killer = "Planet Killer";

    @Test
    public void test_AttacksOnItsOwnAndGrowsByTheDamagedPlayersLands() {
        addCard(Zone.BATTLEFIELD, playerA, killer);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2); // the controller's lands don't count
        addCard(Zone.BATTLEFIELD, playerB, "Island", 3);

        // no attack declared: it has to attack anyway
        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertLife(playerB, 20 - 9);
        assertCounterCount(playerA, killer, CounterType.P1P1, 3);
        assertPowerToughness(playerA, killer, 9 + 3, 9 + 3);
    }

    @Test
    public void test_NoCountersWhenThatPlayerHasNoLands() {
        addCard(Zone.BATTLEFIELD, playerA, killer);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertLife(playerB, 20 - 9);
        assertCounterCount(playerA, killer, CounterType.P1P1, 0);
        assertPowerToughness(playerA, killer, 9, 9);
    }

    @Test
    public void test_FlyingAndIndestructible() {
        addCard(Zone.BATTLEFIELD, playerA, killer);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.HAND, playerA, "Murder"); // {1}{B}{B} destroy target creature

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Murder", killer);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertGraveyardCount(playerA, "Murder", 1);
        assertPermanentCount(playerA, killer, 1);
        assertAbility(playerA, killer, FlyingAbility.getInstance(), true);
        assertAbility(playerA, killer, IndestructibleAbility.getInstance(), true);
        assertLife(playerB, 20 - 9);
    }
}
