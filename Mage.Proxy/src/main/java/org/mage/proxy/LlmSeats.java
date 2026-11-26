package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.nio.file.StandardCopyOption;
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
    /** The table chat personalities a host can pick for a model seat; the seat holds what each one means. */
    static final List<String> PERSONALITIES = List.of("standard", "competitive", "spicy");
    static final String DEFAULT_PERSONALITY = "standard";
    /** In a seat's run folder while it plays: what starts it again after the proxy stopped. */
    static final String RESTORE_NOTE = "seat-restore.json";
    /** A restore note a seat that started again took, kept for its history. */
    static final String RESTORE_NOTE_TAKEN = "seat-restore.taken.json";

    static final Names NAMES = new Names();

    static final class Plan {
        final int seat;
        final LlmCatalog.Selection selection;
        /** The model that takes over when the chosen one stays at capacity; null for none. */
        final LlmCatalog.Selection backup;
        final DeckCardLists deck;
        final String engine;
        /** The host lets this model read and write the game chat; off unless asked for. */
        final boolean chat;
        /** How the model talks in table chat: one of {@link #PERSONALITIES}. */
        final String personality;

        Plan(int seat, LlmCatalog.Selection selection, LlmCatalog.Selection backup, DeckCardLists deck, String engine,
                boolean chat, String personality) {
            this.seat = seat;
            this.selection = selection;
            this.backup = backup;
            this.deck = deck;
            this.engine = engine;
            this.chat = chat;
            this.personality = personality;
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

        /**
         * Holds the name a seat had before the proxy stopped: the server seats it again by that name, so
         * a cooldown doesn't keep it; a seat playing under it now does.
         */
        synchronized boolean hold(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            Long until = held.get(key);
            if (until != null && until == Long.MAX_VALUE) {
                return false;
            }
            held.put(key, Long.MAX_VALUE);
            return true;
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
            String backupModel = JsonArgs.str(entry, "backupModel", "");
            LlmCatalog.Selection backup = null;
            if (!backupModel.isEmpty()) {
                // Any signed-in provider's model; a backup on the same plan shares its limits, the user's choice.
                String backupProvider = JsonArgs.str(entry, "backupProvider", selection.provider);
                if (backupProvider.equals(selection.provider) && backupModel.equals(selection.model)) {
                    throw new IllegalArgumentException("The backup model of model seat " + seat + " must differ from its model");
                }
                backup = LlmCatalog.select(current, backupProvider, backupModel, JsonArgs.str(entry, "backupEffort", ""));
            }
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
            boolean chat = JsonArgs.getBool(entry, "chat", false);
            if (chat && !BRIDGE.equals(engine)) {
                throw new IllegalArgumentException("Model seat " + seat + " can chat only with the bridge engine");
            }
            String personality = JsonArgs.str(entry, "personality", DEFAULT_PERSONALITY);
            if (!PERSONALITIES.contains(personality)) {
                throw new IllegalArgumentException("Model seat " + seat + " has an unknown personality: " + personality);
            }
            if (!chat && !DEFAULT_PERSONALITY.equals(personality)) {
                throw new IllegalArgumentException("Model seat " + seat + " needs table chat for a personality");
            }
            plans.add(new Plan(seat, selection, backup, deck, engine, chat, personality));
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
                writeRestoreNote(run, plan.selection.playerName, request);
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
        if (plan.backup != null) {
            request.addProperty("backup_provider", plan.backup.provider);
            request.addProperty("backup_model", plan.backup.model);
            request.addProperty("backup_reasoning", plan.backup.effort);
        }
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
        request.addProperty("chat", plan.chat);
        if (plan.chat) {
            request.addProperty("personality", plan.personality);
        }
        if (MCP.equals(plan.engine)) {
            request.addProperty("proxy_url", "ws://127.0.0.1:" + proxyPort);
        }
        return request;
    }

    /**
     * Starts again the model seats this host had playing when the proxy stopped (a power cut, the app
     * closed), at the tables still running on the server, like a table the server brought back after it
     * stopped too. Each seat comes back with its name, model and deck, and the server seats it again by
     * its name; it doesn't join the table again.
     *
     * @return how many seats started again
     */
    int restore(Collection<TableView> tables) {
        if (!enabled() || ownerName.isEmpty() || !LlmRuntime.isLoopbackHost(serverHost)) {
            return 0;
        }
        List<Path> notes = new ArrayList<>();
        try (java.util.stream.Stream<Path> runs = Files.list(runtime.runRoot())) {
            runs.map(run -> run.resolve(RESTORE_NOTE)).filter(Files::isRegularFile).sorted().forEach(notes::add);
        } catch (IOException ex) {
            return 0; // no run folder yet: no seat ever played here
        }
        int restored = 0;
        for (Path note : notes) {
            if (restoreSeat(note, tables)) {
                restored++;
            }
        }
        return restored;
    }

    /**
     * {@link #restore} in the background, with the tables on the server now: a host's login doesn't wait
     * for its model seats.
     */
    void restoreAfterLogin(SessionImpl session) {
        if (!enabled()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                UUID roomId = session.getMainRoomId();
                int restored = roomId == null ? 0 : restore(session.getTables(roomId));
                if (restored > 0) {
                    logger.info(restored + " model seat(s) of " + ownerName + " started again");
                }
            } catch (Exception ex) {
                logger.info("model seats of " + ownerName + " not started again: " + ex);
            }
        }, "llm-seat-restore");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean restoreSeat(Path note, Collection<TableView> tables) {
        JsonObject saved;
        try {
            saved = JsonParser.parseString(new String(Files.readAllBytes(note), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            logger.info("unreadable model seat restore note " + note + ": " + ex);
            return false;
        }
        JsonElement raw = saved.get("request");
        if (!ownerName.equalsIgnoreCase(JsonArgs.str(saved, "owner", "")) || raw == null || !raw.isJsonObject()) {
            return false;
        }
        JsonObject request = raw.getAsJsonObject();
        String username = JsonArgs.str(request, "username", "");
        UUID tableId;
        try {
            tableId = UUID.fromString(JsonArgs.str(request, "table_id", ""));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (username.isEmpty() || JsonArgs.getInt(request, "server_port", 0) != serverPort
                || !seatedAtRunningTable(tables, tableId, username)) {
            return false; // another server, or its table is gone: nothing to come back to
        }
        if (!NAMES.hold(username)) {
            return false; // it plays already
        }
        long now = System.currentTimeMillis();
        if (NAMES.live() > runtime.maxSeats()) {
            NAMES.release(username, now, false);
            errors.accept(username + " can't start again: at most " + runtime.maxSeats() + " model seats can play at once on this proxy");
            return false;
        }
        Path run = note.getParent();
        try {
            // taken: a second login of the host doesn't start it twice
            Files.move(note, run.resolve(RESTORE_NOTE_TAKEN), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            NAMES.release(username, now, false);
            logger.info("model seat restore note " + note + " not taken: " + ex);
            return false;
        }
        int seatNumber = JsonArgs.getInt(request, "seat", 0);
        String engine = JsonArgs.str(request, "engine", BRIDGE);
        Path back = nextRun(run);
        JsonObject relaunch = request.deepCopy();
        relaunch.addProperty("run", back.toString());
        relaunch.addProperty("owner_pid", ProcessHandle.current().pid());
        relaunch.addProperty("catalog", runtime.catalog().toString());
        relaunch.addProperty("restored_from", run.toString());
        if (MCP.equals(engine)) {
            relaunch.addProperty("proxy_url", "ws://127.0.0.1:" + proxyPort);
        }
        LlmCatalog.Selection selection = new LlmCatalog.Selection(JsonArgs.str(request, "provider", ""),
                JsonArgs.str(saved, "player_name", username), JsonArgs.str(request, "model", ""),
                JsonArgs.str(request, "reasoning", ""));
        boolean loginOutlivesSeat = MCP.equals(engine);
        LlmSeat seat = new LlmSeat(tableId, seatNumber, username, selection, back, stopGraceMs,
                changed -> changed(changed, loginOutlivesSeat));
        synchronized (seats) {
            seats.put(key(tableId, seatNumber), seat);
        }
        try {
            Files.createDirectories(back);
            writeRestoreNote(back, selection.playerName, relaunch);
        } catch (IOException ex) {
            seat.fail("Could not prepare the model seat's run folder: " + ex.getMessage());
            return false;
        }
        logger.info("model seat " + seatNumber + " for table " + tableId + ": " + username + " starts again ("
                + selection.provider + " " + selection.model + " " + selection.effort + ")");
        seat.launch(runtime, relaunch);
        return true;
    }

    /** The run folder of a seat started again: next to its last one. */
    static Path nextRun(Path run) {
        String base = run.getFileName().toString().replaceFirst("-back\\d+$", "");
        for (int n = 1; ; n++) {
            Path candidate = run.resolveSibling(base + "-back" + n);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    static boolean seatedAtRunningTable(Collection<TableView> tables, UUID tableId, String username) {
        for (TableView table : tables) {
            if (!tableId.equals(table.getTableId())) {
                continue;
            }
            if (table.getTableState() != TableState.DUELING && table.getTableState() != TableState.SIDEBOARDING) {
                return false;
            }
            for (SeatView seat : table.getSeats()) {
                if (username.equalsIgnoreCase(seat.getPlayerName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * What starts a seat again after the proxy stopped. Without the table's password: a seat that comes
     * back doesn't join the table again.
     */
    private void writeRestoreNote(Path run, String playerName, JsonObject request) throws IOException {
        JsonObject saved = new JsonObject();
        saved.addProperty("owner", ownerName);
        saved.addProperty("player_name", playerName);
        JsonObject kept = request.deepCopy();
        kept.remove("table_password");
        saved.add("request", kept);
        Path tmp = run.resolve(RESTORE_NOTE + ".tmp");
        Files.write(tmp, saved.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, run.resolve(RESTORE_NOTE), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forgetRestore(LlmSeat seat) {
        try {
            Files.deleteIfExists(seat.run.resolve(RESTORE_NOTE));
        } catch (IOException ex) {
            logger.info("model seat restore note of " + seat.username + " not deleted: " + ex);
        }
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
            if (LlmSeat.FINISHED.equals(seat.status())) {
                // its match is over: nothing to start again. A seat stopped with the proxy keeps its note,
                // and so does a seat that failed: it may have failed because its server stopped (a power cut
                // can stop it a moment before the proxy). Either way its table may come back, and the
                // note starts it again only at that table running with its player seated.
                forgetRestore(seat);
            }
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
                forgetRestore(seat); // its host stopped it: it doesn't come back
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
