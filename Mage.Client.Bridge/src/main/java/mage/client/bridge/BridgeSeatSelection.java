package mage.client.bridge;

import mage.players.PlayerType;
import mage.view.SeatView;

import java.util.List;

/** The server assigns the first empty seat of a matching type; preserve GUI seat order. */
final class BridgeSeatSelection {
    private BridgeSeatSelection() {
    }

    static boolean canJoin(List<SeatView> seats, Integer requestedSeat) {
        if (requestedSeat != null && (requestedSeat < 1 || requestedSeat > seats.size())) {
            throw new IllegalArgumentException("Requested bridge seat is outside the table");
        }
        for (int index = 0; index < seats.size(); index++) {
            SeatView seat = seats.get(index);
            if (seat.getPlayerType() == PlayerType.HUMAN
                    && (seat.getPlayerId() == null || seat.getPlayerName().isEmpty())) {
                return requestedSeat == null || requestedSeat == index + 1;
            }
        }
        return false;
    }
}
