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

    private static BridgeManaSources.Source source(String colors, int units) {
        return new BridgeManaSources.Source(UUID.randomUUID(), colors, units);
    }
}
