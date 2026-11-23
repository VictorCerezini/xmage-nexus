package mage.cards.p;

import mage.MageInt;
import mage.abilities.common.AttacksEachCombatStaticAbility;
import mage.abilities.common.DealsCombatDamageToAPlayerTriggeredAbility;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.dynamicvalue.common.PermanentsTargetOpponentControlsCount;
import mage.abilities.effects.common.counter.AddCountersSourceEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.counters.CounterType;
import mage.filter.StaticFilters;

import java.util.UUID;

/**
 * @author VictorCerezini
 */
public final class PlanetKiller extends CardImpl {

    // the trigger points its effect at the damaged player, so "target opponent" here is that player
    private static final DynamicValue xValue = new PermanentsTargetOpponentControlsCount(StaticFilters.FILTER_LAND);

    public PlanetKiller(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{8}");

        this.subtype.add(SubType.CONSTRUCT);
        this.power = new MageInt(9);
        this.toughness = new MageInt(9);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // Indestructible
        this.addAbility(IndestructibleAbility.getInstance());

        // This creature attacks each combat if able.
        this.addAbility(new AttacksEachCombatStaticAbility());

        // Whenever this creature deals combat damage to a player, put a +1/+1 counter on it for each land that player controls.
        this.addAbility(new DealsCombatDamageToAPlayerTriggeredAbility(
                new AddCountersSourceEffect(CounterType.P1P1.createInstance(), xValue)
                        .setText("put a +1/+1 counter on it for each land that player controls"),
                false, true
        ));
    }

    private PlanetKiller(final PlanetKiller card) {
        super(card);
    }

    @Override
    public PlanetKiller copy() {
        return new PlanetKiller(this);
    }
}
