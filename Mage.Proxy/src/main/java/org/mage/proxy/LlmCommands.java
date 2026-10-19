package org.mage.proxy;

import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;

import java.util.UUID;
import java.util.concurrent.CompletionException;

final class LlmCommands {

    private LlmCommands() {
    }

    static boolean handle(String action, WebSocket conn, String requestId, JsonObject args, CommandContext ctx) {
        switch (action) {
            case "getLlmProviders": {
                LlmSeats seats = ctx.llmSeats();
                if (!seats.enabled()) {
                    JsonObject data = new JsonObject();
                    data.addProperty("enabled", false);
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, data));
                    return true;
                }
                seats.providers(JsonArgs.getBool(args, "refresh", false)).whenComplete((catalog, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, false, ProxyProtocol.ERR_FAILED,
                                cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                        return;
                    }
                    JsonObject data = catalog.deepCopy();
                    data.addProperty("enabled", true);
                    ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, data));
                });
                return true;
            }
            case "getLlmSeats": {
                UUID tableId = JsonArgs.uuid(args, "tableId", null);
                ctx.gateway().send(conn, ProxyProtocol.resultJson(action, requestId, true, null, ctx.llmSeats().describe(tableId)));
                return true;
            }
            default:
                return false;
        }
    }
}
