package org.mage.proxy;

import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;

import java.io.IOException;

/**
 * {@code listSavedDecks}, {@code saveDeck} and {@code deleteSavedDeck}: the logged-in player's decks
 * kept on the host. Only the friends proxy has them; anywhere else they stay unknown actions and the
 * web client keeps its decks in the browser.
 */
final class SavedDeckCommands {

    private SavedDeckCommands() {
    }

    static boolean handle(String action, WebSocket conn, String requestId, JsonObject args, CommandContext ctx) {
        if (!"listSavedDecks".equals(action) && !"saveDeck".equals(action) && !"deleteSavedDeck".equals(action)) {
            return false;
        }
        SavedDecks decks = ctx.gateway().savedDecks();
        if (decks == null) {
            return false;
        }
        // the name this session logged in with, never one the page sends
        String player = ctx.session() == null ? null : ctx.session().getUserName();
        try {
            Object data = null;
            if ("listSavedDecks".equals(action)) {
                data = decks.list(player);
            } else if ("saveDeck".equals(action)) {
                if (!args.has("deck") || !args.get("deck").isJsonObject()) {
                    throw new IllegalArgumentException("deck is required");
                }
                decks.save(player, args.getAsJsonObject("deck"));
            } else {
                decks.delete(player, JsonArgs.str(args, "id", ""));
            }
            ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, data));
        } catch (IOException ex) {
            ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_FAILED,
                    "the host could not read or write its saved decks: " + ex.getMessage()));
        }
        return true;
    }
}
