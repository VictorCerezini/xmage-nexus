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
}
