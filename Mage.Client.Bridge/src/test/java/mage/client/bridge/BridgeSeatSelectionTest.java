package mage.client.bridge;

import mage.game.Seat;
import mage.players.PlayerType;
import mage.view.SeatView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSeatSelectionTest {
    private SeatView empty(PlayerType type) {
        return new SeatView(new Seat(type));
    }

    private SeatView occupied() throws Exception {
        SeatView seat = empty(PlayerType.HUMAN);
        for (String name : new String[]{"playerId", "playerName"}) {
            Field field = SeatView.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(seat, name.equals("playerId") ? UUID.randomUUID() : "Already joined");
        }
        return seat;
    }

    @Test
    void waitsForTheHostInsteadOfTakingItsSeat() {
        List<SeatView> seats = List.of(empty(PlayerType.HUMAN), empty(PlayerType.HUMAN));
        assertFalse(BridgeSeatSelection.canJoin(seats, 2));
        assertTrue(BridgeSeatSelection.canJoin(seats, null));
    }

    @Test
    void multipleBridgesJoinInConfiguredOrder() throws Exception {
        List<SeatView> before = List.of(occupied(), empty(PlayerType.HUMAN), empty(PlayerType.HUMAN));
        assertTrue(BridgeSeatSelection.canJoin(before, 2));
        assertFalse(BridgeSeatSelection.canJoin(before, 3));
        List<SeatView> after = List.of(occupied(), occupied(), empty(PlayerType.HUMAN));
        assertTrue(BridgeSeatSelection.canJoin(after, 3));
    }

    @Test
    void nativeComputerSeatsDoNotBlockAnAvailableHumanSeat() throws Exception {
        List<SeatView> seats = List.of(occupied(), empty(PlayerType.COMPUTER_MAD), empty(PlayerType.HUMAN));
        assertTrue(BridgeSeatSelection.canJoin(seats, 3));
    }

    @Test
    void rejectsASeatOutsideTheTable() {
        assertThrows(IllegalArgumentException.class,
                () -> BridgeSeatSelection.canJoin(List.of(empty(PlayerType.HUMAN)), 2));
    }
}
