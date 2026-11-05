package mage.client.bridge.processor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeManaSourcesTest {

    @Test
    void readsTheCostOfAPayPrompt() {
        var cost = BridgeManaSources.parseCost("Pay {1}{B}{B}<div><font object_id='x'>Qarsi Revenant</font></div>");
        assertThat(cost.colored()).containsExactly("B", "B");
        assertThat(cost.generic()).isEqualTo(1);

        var hybrid = BridgeManaSources.parseCost("Pay {W/B}{2}");
        assertThat(hybrid.colored()).containsExactly("WB");
        assertThat(hybrid.generic()).isEqualTo(2);

        assertThat(BridgeManaSources.parseCost("Pay {S}")).as("snow needs a snow source").isNull();
        assertThat(BridgeManaSources.parseCost("Pay life")).isNull();
    }

    @Test
    void onlySourcesThatJustTapArePlain() {
        assertThat(BridgeManaSources.plainColors("{T}: Add {G}.")).isEqualTo("G");
        assertThat(BridgeManaSources.plainColors("{T}: Add {W} or {B}. Caves of Koilos deals 1 damage to you."))
            .isEqualTo("WB");
        assertThat(BridgeManaSources.plainColors("{T}: Add one mana of any color.")).isEqualTo("WUBRG");
        assertThat(BridgeManaSources.plainColors("{1}, {T}: Add {W}{B}.")).as("Signet or filter land").isNull();
        assertThat(BridgeManaSources.plainColors("{W/B}, {T}: Add {B}{B}.")).isNull();
        assertThat(BridgeManaSources.plainColors("Sacrifice this artifact: Add one mana of any color.")).isNull();
        assertThat(BridgeManaSources.plainColors("{T}, Pay 1 life: Add {B}.")).isNull();
        assertThat(BridgeManaSources.plainColors("{T}: Add one mana of any color. Spend this mana o..."))
            .as("restricted mana, text cut short").isNull();

        assertThat(BridgeManaSources.plainUnits("{T}: Add {C}{C}.")).isEqualTo(2);
        assertThat(BridgeManaSources.plainUnits("{T}: Add {W} or {B}.")).isEqualTo(1);
        assertThat(BridgeManaSources.plainUnits("{1}, {T}: Add {W}{B}.")).isZero();
    }

    @Test
    void tapsTheLandThatMakesTheColorStillNeeded() {
        var mountain = source("R", 1);
        var forest = source("G", 1);

        var pick = BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {G}"), List.of(mountain, forest));

        assertThat(pick).isEqualTo(forest.id());
    }

    @Test
    void keepsTheOnlySourceOfAColorForThatColor() {
        // {1}{B} with a Swamp and a Plains: the Swamp must pay {B}, the Plains the {1}
        var plains = source("W", 1);
        var swamp = source("B", 1);

        var pick = BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {1}{B}"), List.of(plains, swamp));

        assertThat(pick).isEqualTo(swamp.id());
    }

    @Test
    void spendsTheLeastFlexibleSourceOnGenericMana() {
        var dual = source("WB", 1);
        var plains = source("W", 1);

        var pick = BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {1}"), List.of(dual, plains));

        assertThat(pick).isEqualTo(plains.id());
    }

    @Test
    void saysSoWhenThePlainSourcesCannotPay() {
        var swamp = source("B", 1);
        var plains = source("W", 1);

        assertThat(BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {1}{B}{B}"), List.of(swamp, plains)))
            .isNull();
        assertThat(BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {G}"), List.of(swamp, plains)))
            .isNull();
    }

    @Test
    void countsSourcesThatMakeTwoMana() {
        var tomb = source("C", 2);

        assertThat(BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {2}"), List.of(tomb)))
            .isEqualTo(tomb.id());
        assertThat(BridgeManaSources.nextToTap(BridgeManaSources.parseCost("Pay {C}{C}"), List.of(tomb)))
            .isEqualTo(tomb.id());
    }

    @Test
    void readsTheManaOfASourceWithItsOwnCost() {
        assertThat(BridgeManaSources.manaOfSourceWithCost(List.of("{1}, {T}: Add {R}{W}."))).containsExactly("R", "W");
        assertThat(BridgeManaSources.manaOfSourceWithCost(List.of("{T}: Add {C}.", "{B/R}, {T}: Add {B}{B}, {B}{R}, or {R}{R}.")))
            .as("filter land: the mana is a choice").isNull();
        assertThat(BridgeManaSources.manaOfSourceWithCost(List.of("Sacrifice this artifact: Add one mana of any color.")))
            .isNull();
        assertThat(BridgeManaSources.manaOfSourceWithCost(List.of("{T}: Add {B}."))).as("a plain land").isNull();
        assertThat(BridgeManaSources.manaOfSourceWithCost(List.of("{1}, {T}: Add {R}{W}.", "{2}, {T}: Add {C}{C}{C}.")))
            .as("which one was used is not known").isNull();
    }

    @Test
    void spendsManaOnAMatchingColorBeforeGeneric() {
        var left = BridgeManaSources.after(BridgeManaSources.parseCost("Pay {1}{W}{B}"), List.of("R", "W"));
        assertThat(left.colored()).containsExactly("B");
        assertThat(left.generic()).isZero();

        var lost = BridgeManaSources.after(BridgeManaSources.parseCost("Pay {B}"), List.of("C"));
        assertThat(lost.colored()).as("{C} cannot pay {B}").containsExactly("B");
    }

    @Test
    void paysASignetsOwnCostWithALandTheSpellNoLongerNeeds() {
        // Codex game, v0.7.9: Queen Marchesa {1}{R}{W}{B}; the Mountain paid {R}, then Boros Signet was tapped.
        // Its {R}{W} pays {1}{W}, so the spell still needs {B}, which only the Pathway makes.
        var pathway = source("B", 1);
        var plains = source("W", 1);
        var signetMana = BridgeManaSources.manaOfSourceWithCost(List.of("{1}, {T}: Add {R}{W}."));
        var stillOwed = BridgeManaSources.after(BridgeManaSources.parseCost("Pay {1}{W}{B}"), signetMana);

        assertThat(BridgeManaSources.payerKeepingRestPayable(
            BridgeManaSources.parseCost("Pay {1}"), stillOwed, List.of(pathway, plains), List.of()))
            .isEqualTo(plains.id());
        assertThat(BridgeManaSources.payerKeepingRestPayable(
            BridgeManaSources.parseCost("Pay {1}"), stillOwed, List.of(pathway), List.of()))
            .as("with only the Pathway left nothing keeps the spell payable").isNull();
        assertThat(BridgeManaSources.payerKeepingRestPayable(
            BridgeManaSources.parseCost("Pay {1}"), stillOwed, List.of(plains), List.of(pathway)))
            .as("auto-tap after the plan can still pay {B}").isEqualTo(plains.id());
    }

    @Test
    void picksThePainlandColorTheRestOfThePlanCannotMake() {
        // Claude game, v0.7.9: Queen Marchesa {1}{R}{W}{B} with mana_plan Sulfurous Springs, Caves of Koilos,
        // Plains, Pillarverge Pathway and no ability numbers: {C} was taken twice and {R}{B} was left unpaid.
        var springs = List.of(
            "1. {T}: Add {C}.",
            "2. {T}: Add {B}. Sulfurous Springs deals 1 damage to you.",
            "3. {T}: Add {R}. Sulfurous Springs deals 1 damage to you.");
        var caves = List.of(
            "1. {T}: Add {C}.",
            "2. {T}: Add {W}. Caves of Koilos deals 1 damage to you.",
            "3. {T}: Add {B}. Caves of Koilos deals 1 damage to you.");
        var cavesSource = source("CWB", 1);
        var plains = source("W", 1);
        var pillarverge = source("W", 1);

        assertThat(BridgeManaSources.abilityKeepingRestPayable(BridgeManaSources.parseCost("Pay {1}{R}{W}{B}"),
            BridgeManaSources.NOTHING, springs, List.of(cavesSource, plains, pillarverge)))
            .as("only Sulfurous Springs makes {R}").isEqualTo(2);
        assertThat(BridgeManaSources.abilityKeepingRestPayable(BridgeManaSources.parseCost("Pay {1}{W}{B}"),
            BridgeManaSources.NOTHING, caves, List.of(plains, pillarverge)))
            .as("the Plains and the Pathway make no {B}").isEqualTo(2);
    }

    @Test
    void keepsColorlessWhenTheRestOfThePlanPaysTheColors() {
        var springs = List.of(
            "1. {T}: Add {C}.",
            "2. {T}: Add {B}. Sulfurous Springs deals 1 damage to you.",
            "3. {T}: Add {R}. Sulfurous Springs deals 1 damage to you.");

        assertThat(BridgeManaSources.abilityKeepingRestPayable(BridgeManaSources.parseCost("Pay {1}{B}"),
            BridgeManaSources.NOTHING, springs, List.of(source("B", 1))))
            .as("no damage when {C} will do").isZero();
        assertThat(BridgeManaSources.abilityKeepingRestPayable(BridgeManaSources.parseCost("Pay {R}{B}"),
            BridgeManaSources.NOTHING, springs, List.of()))
            .as("{C} cannot pay {R}{B}; nothing after it, so a needed color").isEqualTo(1);
        assertThat(BridgeManaSources.abilityKeepingRestPayable(BridgeManaSources.parseCost("Pay {G}"),
            BridgeManaSources.NOTHING, springs, List.of()))
            .as("nothing helps: the caller takes the first").isEqualTo(-1);
    }

    @Test
    void tellsManaAbilitiesFromModes() {
        assertThat(BridgeManaSources.onlyManaAbilities(List.of("1. {T}: Add {C}.", "2. {B/R}, {T}: Add {B}{B}.")))
            .isTrue();
        assertThat(BridgeManaSources.onlyManaAbilities(List.of(
            "1. Boros Charm deals 4 damage to target player or planeswalker.",
            "2. Permanents you control gain indestructible until end of turn.",
            "3. Target creature gains double strike until end of turn.")))
            .isFalse();
        assertThat(BridgeManaSources.onlyManaAbilities(List.of(
            "1. Target player creates a 2/1 white and black Inkling creature token with flying.",
            "2. Target player draws a card and loses 1 life.")))
            .isFalse();
        assertThat(BridgeManaSources.onlyManaAbilities(List.of("1. {T}: Add {G}.", "2. Cast Adventure: Stomp")))
            .as("a mix is not a mana choice").isFalse();
        assertThat(BridgeManaSources.onlyManaAbilities(List.of())).isFalse();
    }

    private static BridgeManaSources.Source source(String colors, int units) {
        return new BridgeManaSources.Source(UUID.randomUUID(), colors, units);
    }
}
