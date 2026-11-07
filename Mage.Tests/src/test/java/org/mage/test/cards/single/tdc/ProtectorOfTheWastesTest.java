package org.mage.test.cards.single.tdc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ProtectorOfTheWastesTest extends CardTestPlayerBase {

    /*
    Protector of the Wastes
    {4}{W}{W}
    Creature - Dragon
    Flying
    When this creature enters or becomes monstrous, exile up to two target artifacts and/or enchantments controlled by
    different players.
    {4}{W}: Monstrosity 3.
    5/5
    */
    private static final String protector = "Protector of the Wastes";

    /*
    Replenish
    {3}{W}
    Sorcery
    Return all enchantment cards from your graveyard to the battlefield. (Auras with nothing to enchant remain in your
    graveyard.)
    */
    private static final String replenish = "Replenish";
    private static final String bears = "Grizzly Bears"; // 2/2
    private static final String holyStrength = "Holy Strength"; // {W} Aura: enchanted creature gets +1/+2
    private static final String fountain = "Fountain of Youth"; // {0} artifact
    private static final String zombify = "Zombify"; // {3}{B} sorcery: return target creature card from your graveyard to the battlefield

    /**
     * An Aura put onto the battlefield by an effect, attached to what its controller picks as it enters, enters with
     * no source. Protector of the Wastes checks every
     * creature entering, from any zone, and used to read that missing source, so the game crashed even with
     * Protector only in its owner's hand.
     */
    @Test
    public void test_AuraPutOntoBattlefield_WhileProtectorInHand_DoesNotCrash() {
        setStrictChooseMode(true);

        addCard(Zone.HAND, playerB, protector);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 4);
        addCard(Zone.BATTLEFIELD, playerA, bears);
        addCard(Zone.GRAVEYARD, playerA, holyStrength);
        addCard(Zone.HAND, playerA, replenish);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, replenish);
        setChoice(playerA, bears); // what Holy Strength enchants as it enters

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, holyStrength, 1);
        assertPowerToughness(playerA, bears, 3, 4);
        assertHandCount(playerB, protector, 1);
    }

    @Test
    public void test_Cast_ExilesTarget() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Plains", 6);
        addCard(Zone.HAND, playerA, protector);
        addCard(Zone.BATTLEFIELD, playerB, fountain);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, protector);
        addTarget(playerA, fountain);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, protector, 1);
        assertExileCount(playerB, fountain, 1);
    }

    /**
     * Entering without being cast still counts as entering. The old check compared the event's source (here
     * Zombify) with Protector, so it never triggered this way.
     */
    @Test
    public void test_PutOntoBattlefieldByOtherEffect_ExilesTarget() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.GRAVEYARD, playerA, protector);
        addCard(Zone.HAND, playerA, zombify);
        addCard(Zone.BATTLEFIELD, playerB, fountain);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, zombify, protector);
        addTarget(playerA, fountain);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, protector, 1);
        assertExileCount(playerB, fountain, 1);
    }

    @Test
    public void test_BecomesMonstrous_ExilesTarget() {
        setStrictChooseMode(true);

        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        addCard(Zone.BATTLEFIELD, playerA, protector);
        addCard(Zone.BATTLEFIELD, playerB, fountain);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{4}{W}: Monstrosity");
        addTarget(playerA, fountain);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPowerToughness(playerA, protector, 8, 8);
        assertExileCount(playerB, fountain, 1);
    }
}
