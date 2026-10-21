package mage.client.bridge;

import mage.players.PlayerType;
import mage.view.SeatView;

import java.util.List;

/**
 * The server assigns the first empty seat of a matching type. A bridge waits a little for the seats
 * before its own, so the host and the models keep the GUI seat order, then sits in any open seat:
 * a friend who joined early, even into this bridge's seat, must not leave the model standing.
 */
final class BridgeSeatSelection {
    static final long KEEP_ORDER_MS = 15000;

    private BridgeSeatSelection() {
    }

    static boolean canJoin(List<SeatView> seats, Integer requestedSeat, long waitedMs) {
        if (requestedSeat != null && (requestedSeat < 1 || requestedSeat > seats.size())) {
            throw new IllegalArgumentException("Requested bridge seat is outside the table");
        }
        for (int index = 0; index < seats.size(); index++) {
            if (isOpen(seats.get(index))) {
                return requestedSeat == null
                        || requestedSeat == index + 1
                        // someone else has this bridge's seat: waiting for it can only end in no seat
                        || !isOpen(seats.get(requestedSeat - 1))
                        || waitedMs >= KEEP_ORDER_MS;
            }
        }
        return false;
    }

    private static boolean isOpen(SeatView seat) {
        return seat.getPlayerType() == PlayerType.HUMAN
                && (seat.getPlayerId() == null || seat.getPlayerName().isEmpty());
    }
}
