package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.exporter.XmageDeckExporter;
import mage.constants.TableState;
import mage.remote.SessionImpl;
import mage.view.SeatView;
import mage.view.TableView;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class LlmSeats {

    private static final Logger logger = Logger.getLogger(LlmSeats.class.getName());

    static final int MAX_USERNAME = 14;
    static final int MAX_WINS = 10;
    static final long NAME_COOLDOWN_MS = 10 * 60_000;
    static final long STOP_GRACE_MS = 20_000;
    static final String BRIDGE = "bridge";
    static final String MCP = "mcp";

    static final Names NAMES = new Names();

    static final class Plan {
        final int seat;
        final LlmCatalog.Selection selection;
        final DeckCardLists deck;
        final String engine;

        Plan(int seat, LlmCatalog.Selection selection, DeckCardLists deck, String engine) {
            this.seat = seat;
            this.selection = selection;
            this.deck = deck;
            this.engine = engine;
        }
    }

    static final class Names {
        private final Map<String, Long> held = new HashMap<>();

        synchronized String allocate(String base, int seat, String owner, long now) {
            return allocate(base, seat, owner, now, Collections.emptySet());
        }

        synchronized String allocate(String base, int seat, String owner, long now, Set<String> seated) {
            List<Integer> numbers = new ArrayList<>();
            numbers.add(seat);
            for (int n = 1; n < 100; n++) {
                if (n != seat) {
                    numbers.add(n);
                }
            }
            for (int n : numbers) {
                String name = base + " " + n;
                String key = name.toLowerCase(Locale.ROOT);
                if (name.length() > MAX_USERNAME || name.equalsIgnoreCase(owner) || seated.contains(key)) {
                    continue;
                }
                Long until = held.get(key);
                if (until == null || until <= now) {
                    held.put(key, Long.MAX_VALUE);
                    return name;
                }
            }
            return null;
        }

        synchronized void release(String name, long now, boolean cooldown) {
            held.put(name.toLowerCase(Locale.ROOT), cooldown ? now + NAME_COOLDOWN_MS : now);
        }

        synchronized int live() {
            int count = 0;
            for (long until : held.values()) {
                if (until == Long.MAX_VALUE) {
                    count++;
                }
            }
            return count;
        }

        synchronized void clear() {
            held.clear();
        }
    }

    static Set<String> seatedNames(Collection<TableView> tables) {
        Set<String> names = new HashSet<>();
        for (TableView table : tables) {
            if (table.getTableState() == TableState.FINISHED) {
                continue;
            }
            for (SeatView seat : table.getSeats()) {
                String name = seat.getPlayerName();
                if (name != null && !name.isEmpty()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private final LlmRuntime runtime;
    private final LlmCatalog catalog;
    private final Consumer<String> events;
    private final Consumer<String> errors;
    private final long stopGraceMs;
    private final Map<String, LlmSeat> seats = new LinkedHashMap<>();
    private volatile String serverHost = "";
    private volatile int serverPort = 0;
    private volatile String ownerName = "";
    private volatile int proxyPort = 0;

    LlmSeats(LlmRuntime runtime, LlmCatalog catalog, Consumer<String> events, Consumer<String> errors) {
        this(runtime, catalog, events, errors, STOP_GRACE_MS);
    }

    LlmSeats(LlmRuntime runtime, LlmCatalog catalog, Consumer<String> events, Consumer<String> errors, long stopGraceMs) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.events = events;
        this.errors = errors;
        this.stopGraceMs = stopGraceMs;
    }

    static LlmSeats disabled() {
        return new LlmSeats(null, null, json -> {
        }, message -> {
        });
    }

    boolean enabled() {
        return runtime != null;
    }

    void setServer(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
    }

    void setProxyPort(int port) {
        this.proxyPort = port;
    }

    void setOwnerName(String username) {
        this.ownerName = username == null ? "" : username;
    }

    CompletableFuture<JsonObject> providers(boolean refresh) {
        return catalog.providers(refresh);
    }

    List<Plan> prepare(JsonObject args) throws IOException {
        if (!args.has("llmSeats") || args.get("llmSeats").isJsonNull()) {
            return new ArrayList<>();
        }
        if (!enabled()) {
            throw new IllegalArgumentException("Model seats are disabled on this proxy (start it with --llm)");
        }
        if (!LlmRuntime.isLoopbackHost(serverHost)) {
            throw new IllegalArgumentException("Model seats need the XMage server on this machine, not " + serverHost);
        }
        JsonElement raw = args.get("llmSeats");
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() == 0) {
            throw new IllegalArgumentException("llmSeats must be a non-empty list");
        }
        if (!args.has("playerTypes") || !args.get("playerTypes").isJsonArray()) {
            throw new IllegalArgumentException("llmSeats needs playerTypes");
        }
        JsonArray types = args.getAsJsonArray("playerTypes");
        int wins = JsonArgs.getInt(args, "winsNeeded", 1);
        if (wins < 1 || wins > MAX_WINS) {
            throw new IllegalArgumentException("Model seats play matches of 1 to " + MAX_WINS + " wins");
        }
        JsonObject current = catalog.read();
        String deckType = JsonArgs.str(args, "deckType", null);
        String gameType = JsonArgs.str(args, "gameType", null);
        List<Plan> plans = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each model seat must be an object");
            }
            JsonObject entry = element.getAsJsonObject();
            int seat = JsonArgs.getInt(entry, "seat", 0);
            if (seat < 1 || seat > types.size()) {
                throw new IllegalArgumentException("Model seat " + seat + " is outside the table's " + types.size() + " seats");
            }
            if (!"HUMAN".equalsIgnoreCase(types.get(seat - 1).getAsString())) {
                throw new IllegalArgumentException("Model seat " + seat + " must be a HUMAN seat in playerTypes");
            }
            if (!taken.add(seat)) {
                throw new IllegalArgumentException("Seat " + seat + " has two models");
            }
            LlmCatalog.Selection selection = LlmCatalog.select(current, JsonArgs.str(entry, "provider", ""),
                    JsonArgs.str(entry, "model", ""), JsonArgs.str(entry, "effort", ""));
            if (!entry.has("deck") || !entry.get("deck").isJsonObject()) {
                throw new IllegalArgumentException("Model seat " + seat + " needs a deck");
            }
            DeckCardLists deck = DeckJson.parse(entry.getAsJsonObject("deck"));
            deck = DeckValidation.stripMissing(DeckValidation.normalizeForXMage(deck, deckType, gameType));
            if (deck.getCards().isEmpty()) {
                throw new IllegalArgumentException("The deck of model seat " + seat + " has no playable cards");
            }
            String engine = JsonArgs.str(entry, "engine", BRIDGE);
            if (!BRIDGE.equals(engine) && !MCP.equals(engine)) {
                throw new IllegalArgumentException("Model seat " + seat + " has an unknown engine: " + engine);
            }
            plans.add(new Plan(seat, selection, deck, engine));
        }
        int live = NAMES.live();
        if (live + plans.size() > runtime.maxSeats()) {
            throw new IllegalArgumentException("At most " + runtime.maxSeats() + " model seats can play at once on this proxy ("
                    + live + " playing)");
        }
        return plans;
    }

    void start(List<Plan> plans, JsonObject args, UUID tableId) {
        start(plans, args, tableId, Collections.emptySet());
    }

    void start(List<Plan> plans, JsonObject args, UUID tableId, Set<String> seated) {
        if (plans.isEmpty()) {
            return;
        }
        pruneFinished();
        int playerCount = args.getAsJsonArray("playerTypes").size();
        for (Plan plan : plans) {
            String username = NAMES.allocate(plan.selection.playerName, plan.seat, ownerName, System.currentTimeMillis(), seated);
            if (username == null) {
                errors.accept("No free player name for the " + plan.selection.playerName + " seat " + plan.seat);
                continue;
            }
            Path run = runtime.runRoot().resolve(tableId + "-seat-" + plan.seat);
            boolean loginOutlivesSeat = MCP.equals(plan.engine);
            LlmSeat seat = new LlmSeat(tableId, plan.seat, username, plan.selection, run, stopGraceMs,
                    changed -> changed(changed, loginOutlivesSeat));
            synchronized (seats) {
                seats.put(key(tableId, plan.seat), seat);
            }
            JsonObject request;
            try {
                Files.createDirectories(run);
                Path deck = run.resolve("deck.dck");
                writeDeck(deck, plan.deck);
                request = request(plan, args, tableId, username, run, deck, playerCount);
            } catch (IOException ex) {
                seat.fail("Could not prepare the model seat's run folder: " + ex.getMessage());
                continue;
            }
            logger.info("model seat " + plan.seat + " for table " + tableId + ": " + username + " ("
                    + plan.selection.provider + " " + plan.selection.model + " " + plan.selection.effort + ")");
            seat.launch(runtime, request);
        }
    }

    JsonObject request(Plan plan, JsonObject args, UUID tableId, String username, Path run, Path deck, int playerCount) {
        JsonObject request = new JsonObject();
        request.addProperty("provider", plan.selection.provider);
        request.addProperty("model", plan.selection.model);
        request.addProperty("reasoning", plan.selection.effort);
        request.addProperty("catalog", runtime.catalog().toString());
        request.addProperty("table_id", tableId.toString());
        request.addProperty("table_password", JsonArgs.str(args, "password", ""));
        request.addProperty("seat", plan.seat);
        request.addProperty("username", username);
        request.addProperty("server_port", serverPort);
        request.addProperty("deck", deck.toString());
        request.addProperty("game_type", JsonArgs.str(args, "gameType", ""));
        request.addProperty("deck_type", JsonArgs.str(args, "deckType", ""));
        request.addProperty("player_count", playerCount);
        request.addProperty("wins_needed", JsonArgs.getInt(args, "winsNeeded", 1));
        request.addProperty("owner_pid", ProcessHandle.current().pid());
        request.addProperty("run", run.toString());
        request.addProperty("engine", plan.engine);
        if (MCP.equals(plan.engine)) {
            request.addProperty("proxy_url", "ws://127.0.0.1:" + proxyPort);
        }
        return request;
    }

    static void writeDeck(Path file, DeckCardLists deck) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             PrintWriter out = new PrintWriter(writer)) {
            new XmageDeckExporter().writeDeck(out, deck);
        }
    }

    private void changed(LlmSeat seat, boolean loginOutlivesSeat) {
        JsonObject event = seat.toJson();
        event.addProperty("type", "llmSeat");
        events.accept(event.toString());
        if (seat.isTerminal()) {
            boolean cooldown = loginOutlivesSeat || !LlmSeat.FINISHED.equals(seat.status());
            NAMES.release(seat.username, System.currentTimeMillis(), cooldown);
            logger.info("model seat " + seat.username + " " + seat.status()
                    + (seat.reason() == null ? "" : ": " + seat.reason()));
        }
        if (LlmSeat.FAILED.equals(seat.status())) {
            errors.accept(seat.username + " (seat " + seat.seat + ") failed"
                    + (seat.reason() == null ? "" : ": " + seat.reason()));
        }
    }

    boolean stopTable(UUID tableId) {
        boolean any = false;
        for (LlmSeat seat : snapshot()) {
            if (seat.tableId.equals(tableId) && !seat.isTerminal()) {
                seat.stop();
                any = true;
            }
        }
        return any;
    }

    Set<UUID> stopAll() {
        Set<UUID> tables = new HashSet<>();
        for (LlmSeat seat : snapshot()) {
            if (!seat.isTerminal()) {
                seat.stop();
                tables.add(seat.tableId);
            }
        }
        return tables;
    }

    static void removeOwnedTables(SessionImpl session, Collection<UUID> tables) {
        for (UUID tableId : tables) {
            try {
                UUID roomId = session.getMainRoomId();
                if (session.getTable(roomId, tableId).isPresent() && session.isTableOwner(roomId, tableId)) {
                    session.removeTable(roomId, tableId);
                }
            } catch (Exception ex) {
                logger.info("could not remove table " + tableId + " of stopped model seats: " + ex);
            }
        }
    }

    JsonArray describe(UUID tableId) {
        JsonArray out = new JsonArray();
        for (LlmSeat seat : snapshot()) {
            if (tableId == null || seat.tableId.equals(tableId)) {
                out.add(seat.toJson());
            }
        }
        return out;
    }

    int aliveCount() {
        int count = 0;
        for (LlmSeat seat : snapshot()) {
            if (!seat.isTerminal()) {
                count++;
            }
        }
        return count;
    }

    private void pruneFinished() {
        synchronized (seats) {
            seats.values().removeIf(LlmSeat::isTerminal);
        }
    }

    private List<LlmSeat> snapshot() {
        synchronized (seats) {
            return new ArrayList<>(seats.values());
        }
    }

    private static String key(UUID tableId, int seat) {
        return tableId + "#" + seat;
    }
}
