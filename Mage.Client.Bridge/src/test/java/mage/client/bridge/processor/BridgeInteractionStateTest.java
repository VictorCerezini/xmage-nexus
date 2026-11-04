package mage.client.bridge.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeInteractionStateTest {

    @Test
    void callsThatMoveTheGameOnAreNeverStuck() {
        var state = new BridgeInteractionState();
        for (int seq = 1; seq <= 500; seq++) {
            state.recordCall(seq);
            assertThat(state.stuck()).as("call on sequence " + seq).isFalse();
        }
    }

    @Test
    void tenCallsInARowThatChangeNothingAreStuck() {
        var state = new BridgeInteractionState();
        state.recordCall(7);
        for (int call = 1; call <= 9; call++) {
            state.recordCall(7);
            assertThat(state.stuck()).as("after " + call + " calls without progress").isFalse();
        }
        state.recordCall(7);
        assertThat(state.callsWithoutProgress()).isEqualTo(10);
        assertThat(state.stuck()).isTrue();

        state.clearStuck();
        assertThat(state.stuck()).as("the bridge answered the prompt").isFalse();
    }

    @Test
    void progressStartsTheCountOver() {
        var state = new BridgeInteractionState();
        for (int call = 0; call < 9; call++) {
            state.recordCall(3);
        }
        state.recordCall(4);
        assertThat(state.callsWithoutProgress()).isZero();
        for (int call = 0; call < 9; call++) {
            state.recordCall(4);
        }
        assertThat(state.stuck()).isFalse();
    }

    @Test
    void aNewGameStartsTheCountOver() {
        var state = new BridgeInteractionState();
        for (int call = 0; call < 11; call++) {
            state.recordCall(5);
        }
        assertThat(state.stuck()).isTrue();

        state.resetRuntimeState();
        state.recordCall(5);
        assertThat(state.stuck()).isFalse();
        assertThat(state.callsWithoutProgress()).isZero();
    }

    @Test
    void theLimitCanBeChanged() {
        var state = new BridgeInteractionState();
        state.setMaxCallsWithoutProgress(3);
        for (int call = 0; call < 4; call++) {
            state.recordCall(9);
        }
        assertThat(state.stuck()).isTrue();
    }
}
