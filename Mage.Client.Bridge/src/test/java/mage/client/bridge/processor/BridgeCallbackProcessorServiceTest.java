package mage.client.bridge.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeCallbackProcessorServiceTest {

    @Test
    void logLinesAreMatchedThroughTheNameColour() {
        String line = "<font color='#20B2AA'>Claude 10</font> returns to the game";

        assertThat(BridgeCallbackProcessorService.logSays(line, "Claude 10", "returns to the game")).isTrue();
        assertThat(BridgeCallbackProcessorService.logSays(line, "Claude 10", "has lost the game")).isFalse();
    }

    @Test
    void aSimilarNameIsNotThisPlayer() {
        String line = "<font color='#20B2AA'>Claude 10</font> has lost the game";

        assertThat(BridgeCallbackProcessorService.logSays(line, "Claude 1", "has lost the game")).isFalse();
    }

    @Test
    void aMissingMessageSaysNothing() {
        assertThat(BridgeCallbackProcessorService.logSays(null, "Claude 1", "has lost the game")).isFalse();
    }

    @Test
    void onlyAGameThatWentBackMakesATakeBackNote() {
        assertThat(BridgeCallbackProcessorService.takeBackNote(
            "<font color='#FFFF00'>Player request: Rolling back to start of turn 4</font>"))
            .startsWith("[System] Moves were taken back: Rolling back to start of turn 4. ");
        // a request can still be denied, and other lines are not about going back
        assertThat(BridgeCallbackProcessorService.takeBackNote("Undo request denied by Claude 1")).isNull();
        assertThat(BridgeCallbackProcessorService.takeBackNote(
            "Player request: It's not possible to rollback 2 turn(s)")).isNull();
        assertThat(BridgeCallbackProcessorService.takeBackNote("Albin casts Lightning Bolt")).isNull();
        assertThat(BridgeCallbackProcessorService.takeBackNote(null)).isNull();
    }
}
