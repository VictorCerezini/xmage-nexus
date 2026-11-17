package mage.client.bridge.processor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgePlayableActionTest {

    @Test
    void aLandWhoseOnlyPlayableAbilityIsCyclingIsActivatedNotPlayed() {
        // Xander's Lounge in hand while a trigger is on the stack: it can be cycled, not played
        assertThat(BridgePublishedQueryBuilder.playableAction(
            List.of("Cycling {3} ({3}, Discard this card: Draw a card.)"), false, true)).isEqualTo("activate");
    }

    @Test
    void aLandThatCanBePlayedIsALandPlayEvenWhenItCanAlsoBeCycled() {
        assertThat(BridgePublishedQueryBuilder.playableAction(
            List.of("Cycling {3} ({3}, Discard this card: Draw a card.)", "Play Xander's Lounge"), false, true))
            .isEqualTo("land");
    }

    @Test
    void theLandSideOfADoubleFacedCardIsALandPlay() {
        // Malakir Rebirth // Malakir Mire: the land side has an id of its own, off the hand
        assertThat(BridgePublishedQueryBuilder.playableAction(List.of("Play Malakir Mire"), true, false))
            .isEqualTo("land");
    }

    @Test
    void aSpellIsCastAndAPermanentsAbilityIsActivated() {
        assertThat(BridgePublishedQueryBuilder.playableAction(List.of("Cast Lightning Bolt"), false, false))
            .isEqualTo("cast");
        assertThat(BridgePublishedQueryBuilder.playableAction(
            List.of("{T}, Pay 1 life, Sacrifice {this}: Search your li..."), true, true)).isEqualTo("activate");
    }

    @Test
    void withoutAbilityNamesTheCardTypeDecides() {
        assertThat(BridgePublishedQueryBuilder.playableAction(List.of(), false, true)).isEqualTo("land");
        assertThat(BridgePublishedQueryBuilder.playableAction(List.of(), false, false)).isEqualTo("cast");
        assertThat(BridgePublishedQueryBuilder.playableAction(List.of(), true, false)).isEqualTo("activate");
    }
}
