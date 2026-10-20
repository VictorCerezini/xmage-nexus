package org.mage.proxy;

import com.google.gson.JsonObject;
import mage.cards.decks.DeckCardLists;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;
import mage.view.TableView;
import org.java_websocket.WebSocket;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Table and match commands: create, join, leave, start, watch. */
final class TableCommands {

    private TableCommands() {
    }

    static boolean handle(String action, WebSocket conn, String requestId, JsonObject args, CommandContext ctx) throws Exception {
        switch (action) {
            case "createTable": {
                long start = System.currentTimeMillis();
                if (!ctx.simEnabled() && MatchOptionsParser.hasSimSeat(args)) {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_INVALID_ARGUMENT, "SIM seats are disabled on this proxy (dev only, start it with --sim on)"));
                    return true;
                }
                List<LlmSeats.Plan> llmSeats;
                try {
                    llmSeats = ctx.llmSeats().prepare(args);
                } catch (IllegalArgumentException ex) {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_INVALID_ARGUMENT, ex.getMessage()));
                    return true;
                }
                MatchOptions options = MatchOptionsParser.parseMatchOptions(args);
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                Set<String> seated = llmSeats.isEmpty() ? Collections.emptySet() : LlmSeats.seatedNames(ctx.session().getTables(roomId));
                Object result = ctx.session().createTable(roomId, options);
                boolean ok = result != null;
                if (!ok) {
                    ctx.sendFailure(conn, action, requestId, start);
                } else {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, result));
                    if (result instanceof TableView) {
                        ctx.startSims(args, roomId, ((TableView) result).getTableId());
                        ctx.llmSeats().start(llmSeats, args, ((TableView) result).getTableId(), seated);
                    }
                }
                return true;
            }
            case "joinTable": {
                long start = System.currentTimeMillis();
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                String playerName = JsonArgs.str(args, "playerName", ctx.session().getUserName());
                PlayerType playerType = PlayerType.valueOf(JsonArgs.str(args, "playerType", "HUMAN").toUpperCase(Locale.ROOT));
                int skill = JsonArgs.getInt(args, "skill", 0);
                DeckCardLists deck = DeckJson.parse(args.getAsJsonObject("deck"));
                deck = DeckValidation.normalizeForXMage(deck, JsonArgs.str(args, "deckType", null), JsonArgs.str(args, "gameType", null));
                String password = JsonArgs.str(args, "password", "");
                boolean ok = ctx.session().joinTable(roomId, tableId, playerName, playerType, skill, deck, password);
                if (!ok) {
                    ctx.sendFailure(conn, action, requestId, start);
                } else {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, null));
                }
                return true;
            }
            case "leaveTable": {
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                boolean hadModelSeats = tableId != null && ctx.llmSeats().stopTable(tableId);
                boolean left = ctx.session().leaveTable(roomId, tableId);
                if (hadModelSeats) {
                    LlmSeats.removeOwnedTables(ctx.session(), java.util.Collections.singleton(tableId));
                }
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, left, null, null));
                return true;
            }
            case "removeTable": {
                // always the roomId variant: removeTable(tableId) is the admin one
                // (adminTableRemove) and fails with "Wrong admin access" for normal
                // users, leaving orphaned tables
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                if (tableId == null) {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_INVALID_ARGUMENT, "tableId required"));
                    return true;
                }
                if (!ctx.session().getTable(roomId, tableId).isPresent()) {
                    ctx.llmSeats().stopTable(tableId);
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, null));
                    return true;
                }
                if (!ctx.session().isTableOwner(roomId, tableId)) {
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_NOT_AUTHORIZED, "Only the table owner can delete this table"));
                    return true;
                }
                ctx.llmSeats().stopTable(tableId);
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, ctx.session().removeTable(roomId, tableId), null, null));
                return true;
            }
            case "startMatch": {
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, ctx.session().startMatch(roomId, tableId), null, null));
                return true;
            }
            case "swapSeats": {
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                int seatNum1 = JsonArgs.getInt(args, "seatNum1", -1);
                int seatNum2 = JsonArgs.getInt(args, "seatNum2", -1);
                boolean ok = tableId != null && seatNum1 >= 0 && seatNum2 >= 0
                        && ctx.session().swapSeats(roomId, tableId, seatNum1, seatNum2);
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, ok, null, null));
                return true;
            }
            case "watchTable": {
                UUID roomId = JsonArgs.uuid(args, "roomId", ctx.session().getMainRoomId());
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, ctx.session().watchTable(roomId, tableId), null, null));
                return true;
            }
            case "watchGame": {
                UUID gameId = JsonArgs.uuid(args, "gameId", null);
                boolean ok = ctx.session().watchGame(gameId);
                if (ok) {
                    ctx.markGameActive(gameId);
                }
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, ok, null, null));
                return true;
            }
            default:
                return false;
        }
    }
}
