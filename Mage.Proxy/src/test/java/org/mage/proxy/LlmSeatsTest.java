package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSeatsTest {

    private static final UUID TABLE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String BEARS = "{\"name\":\"Mirror Bears\",\"cards\":["
            + "{\"cardName\":\"Forest\",\"setCode\":\"M12\",\"cardNumber\":\"246\",\"amount\":20},"
            + "{\"cardName\":\"Runeclaw Bear\",\"setCode\":\"M12\",\"cardNumber\":\"193\",\"amount\":20}],\"sideboard\":[]}";

    @TempDir
    Path dir;

    private final List<ProcessBuilder> launched = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeProcess> processes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile IOException launchFailure = null;

    static final class FakeProcess extends Process {
        private final LinkedBlockingQueue<Integer> out = new LinkedBlockingQueue<>();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final CountDownLatch stdinClosed = new CountDownLatch(1);
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream() {
            @Override
            public void close() {
                stdinClosed.countDown();
            }
        };
        private volatile int exit = -1;
        private volatile boolean killed = false;
        private final InputStream stdout = new InputStream() {
            private boolean ended = false;

            @Override
            public int read() throws IOException {
                if (ended) {
                    return -1;
                }
                try {
                    int b = out.take();
                    ended = b < 0;
                    return b;
                } catch (InterruptedException ex) {
                    throw new IOException(ex);
                }
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int first = read();
                if (first < 0) {
                    return -1;
                }
                buffer[offset] = (byte) first;
                int count = 1;
                while (count < length && !ended) {
                    Integer next = out.peek();
                    if (next == null) {
                        break;
                    }
                    if (next < 0) {
                        break;
                    }
                    buffer[offset + count++] = (byte) (int) out.poll();
                }
                return count;
            }
        };

        void say(String line) {
            for (byte b : (line + "\n").getBytes(StandardCharsets.UTF_8)) {
                out.add(b & 0xff);
            }
        }

        void end(int code) {
            if (exited.getCount() == 0) {
                return;
            }
            exit = code;
            out.add(-1);
            exited.countDown();
        }

        JsonObject request() throws InterruptedException {
            assertTrue(stdinClosed.await(5, TimeUnit.SECONDS), "the request was never sent");
            return JsonParser.parseString(new String(stdin.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exit;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (exited.getCount() != 0) {
                throw new IllegalThreadStateException("running");
            }
            return exit;
        }

        @Override
        public void destroy() {
            end(1);
        }

        @Override
        public Process destroyForcibly() {
            killed = true;
            end(137);
            return this;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() != 0;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    @BeforeEach
    void freshNames() {
        LlmSeats.NAMES.clear();
    }

    private LlmRuntime runtime(int maxSeats) throws IOException {
        Path catalog = dir.resolve(".run").resolve("llm").resolve("catalog.json");
        Files.createDirectories(catalog.getParent());
        Files.write(catalog, ("{\"providers\":[{\"id\":\"chatgpt_subscription\",\"playerName\":\"ChatGPT\","
                + "\"playerType\":\"ChatGPT subscription\",\"auth\":\"existing Codex ChatGPT login\","
                + "\"account\":\"someone@example.com\",\"models\":["
                + "{\"id\":\"gpt-6-luna\",\"name\":\"GPT-6-Luna\",\"efforts\":[\"low\",\"high\"],\"defaultEffort\":\"low\",\"isDefault\":true},"
                + "{\"id\":\"gpt-6-astra\",\"name\":\"GPT-6-Astra\",\"efforts\":[\"low\",\"high\"],\"defaultEffort\":\"high\",\"isDefault\":false}]}],"
                + "\"unavailable\":[{\"id\":\"claude_subscription\",\"reason\":\"Claude Code is not signed in\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        return new LlmRuntime(dir, dir.resolve("python.exe"), catalog, maxSeats, builder -> {
            if (launchFailure != null) {
                throw launchFailure;
            }
            launched.add(builder);
            FakeProcess process = new FakeProcess();
            processes.add(process);
            return process;
        });
    }

    private LlmSeats seats(int maxSeats) throws IOException {
        LlmRuntime runtime = runtime(maxSeats);
        LlmSeats seats = new LlmSeats(runtime, new LlmCatalog(runtime), events::add, errors::add, 200);
        seats.setServer("127.0.0.1", 17171);
        seats.setOwnerName("Owner");
        return seats;
    }

    private static JsonObject table(String llmSeats) {
        return JsonParser.parseString("{\"name\":\"LLM seat test\",\"gameType\":\"Two Player Duel\","
                + "\"deckType\":\"Constructed - Freeform Unlimited\",\"winsNeeded\":2,\"password\":\"table-pw\","
                + "\"playerTypes\":[\"HUMAN\",\"HUMAN\",\"COMPUTER_MAD\"],\"llmSeats\":" + llmSeats + "}").getAsJsonObject();
    }

    private static String seat(int number, String extra) {
        return "{\"seat\":" + number + ",\"provider\":\"chatgpt_subscription\",\"deck\":" + BEARS + extra + "}";
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private List<String> statuses() {
        synchronized (events) {
            return events.stream()
                    .map(json -> JsonParser.parseString(json).getAsJsonObject().get("status").getAsString())
                    .collect(Collectors.toList());
        }
    }

    private static void manifest(Path run, String json) throws IOException {
        Files.write(run.resolve("manifest.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void modelSeatsAreOffUnlessAskedFor() {
        assertFalse(Config.parse(new String[0]).isLlmEnabled());
        assertFalse(Config.parse(new String[]{"--llm", "off"}).isLlmEnabled());
        assertTrue(Config.parse(new String[]{"--llm", "on"}).isLlmEnabled());
        assertEquals(Config.DEFAULT_LLM_CATALOG, Config.parse(new String[]{"--llm", "on"}).getLlmCatalog());
        assertEquals("custom/catalog.json", Config.parse(new String[]{"--llm", "custom/catalog.json"}).getLlmCatalog());
        assertEquals(Config.DEFAULT_LLM_MAX_SEATS, Config.parse(new String[0]).getLlmMaxSeats());
        assertNull(LlmRuntime.fromConfig(Config.parse(new String[0])));
    }

    @Test
    void aProxyThatOthersCanReachRefusesModelSeats() {
        IllegalStateException bind = assertThrows(IllegalStateException.class,
                () -> LlmRuntime.fromConfig(Config.parse(new String[]{"--llm", "on", "--bind", "0.0.0.0"})));
        assertTrue(bind.getMessage().contains("loopback"));
        IllegalStateException origin = assertThrows(IllegalStateException.class,
                () -> LlmRuntime.fromConfig(Config.parse(new String[]{"--llm", "on", "--allowedOrigins", "https://play.example.com"})));
        assertTrue(origin.getMessage().contains("https://play.example.com"));
        assertTrue(LlmRuntime.isLoopbackHost("127.0.0.1"));
        assertTrue(LlmRuntime.isLoopbackHost("localhost"));
        assertTrue(LlmRuntime.isLoopbackHost("::1"));
        assertFalse(LlmRuntime.isLoopbackHost("beta.xmage.today"));
        assertFalse(LlmRuntime.isLoopbackHost("192.168.1.20"));
    }

    @Test
    void theRepositoryRootNeedsTheSupervisor() throws IOException {
        assertThrows(IllegalStateException.class, () -> LlmRuntime.findRoot(dir.toString()));
        Files.createDirectories(dir.resolve("llm"));
        Files.write(dir.resolve("llm").resolve("seat.py"), new byte[0]);
        assertEquals(dir.toAbsolutePath().normalize(), LlmRuntime.findRoot(dir.toString()));
        assertTrue(LlmRuntime.defaultPython(dir).startsWith(dir.resolve("llm").resolve(".venv")));
    }

    @Test
    void theCatalogKeepsOnlyKnownFields() throws IOException {
        LlmRuntime runtime = runtime(4);
        JsonObject catalog = new LlmCatalog(runtime).read();
        JsonObject provider = catalog.getAsJsonArray("providers").get(0).getAsJsonObject();
        assertFalse(provider.has("account"));
        assertEquals("ChatGPT", provider.get("playerName").getAsString());
        assertEquals(2, provider.getAsJsonArray("models").size());
        assertEquals("claude_subscription", catalog.getAsJsonArray("unavailable").get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(catalog.toString().contains("someone@example.com"));
    }

    @Test
    void aSelectionFallsBackToTheDefaultModelAndEffort() throws IOException {
        JsonObject catalog = new LlmCatalog(runtime(4)).read();
        LlmCatalog.Selection defaults = LlmCatalog.select(catalog, "chatgpt_subscription", "", "");
        assertEquals("gpt-6-luna", defaults.model);
        assertEquals("low", defaults.effort);
        assertEquals("ChatGPT", defaults.playerName);
        assertEquals("high", LlmCatalog.select(catalog, "chatgpt_subscription", "gpt-6-astra", "").effort);
        IllegalArgumentException signedOut = assertThrows(IllegalArgumentException.class,
                () -> LlmCatalog.select(catalog, "claude_subscription", "", ""));
        assertTrue(signedOut.getMessage().contains("not signed in"));
        assertThrows(IllegalArgumentException.class, () -> LlmCatalog.select(catalog, "chatgpt_subscription", "gpt-4", ""));
        assertThrows(IllegalArgumentException.class, () -> LlmCatalog.select(catalog, "chatgpt_subscription", "", "ultra"));
        assertThrows(IllegalArgumentException.class, () -> LlmCatalog.select(catalog, "nobody", "", ""));
    }

    @Test
    void aBadModelSeatIsRefusedBeforeAnyTableExists() throws IOException {
        LlmSeats seats = seats(4);
        assertEquals(0, seats.prepare(JsonParser.parseString("{\"playerTypes\":[\"HUMAN\"]}").getAsJsonObject()).size());
        assertRefused(seats, table("[" + seat(3, "") + "]"), "must be a HUMAN seat");
        assertRefused(seats, table("[" + seat(4, "") + "]"), "outside the table");
        assertRefused(seats, table("[" + seat(2, "") + "," + seat(2, "") + "]"), "two models");
        assertRefused(seats, table("[{\"seat\":2,\"provider\":\"chatgpt_subscription\"}]"), "needs a deck");
        assertRefused(seats, table("[]"), "non-empty");
        JsonObject tooLong = table("[" + seat(2, "") + "]");
        tooLong.addProperty("winsNeeded", 11);
        assertRefused(seats, tooLong, "1 to 10 wins");
        seats.setServer("beta.xmage.today", 17171);
        assertRefused(seats, table("[" + seat(2, "") + "]"), "on this machine");
        assertTrue(processes.isEmpty());
    }

    @Test
    void aDisabledProxyRefusesModelSeats() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LlmSeats.disabled().prepare(table("[" + seat(2, "") + "]")));
        assertTrue(ex.getMessage().contains("--llm"));
    }

    private static void assertRefused(LlmSeats seats, JsonObject args, String why) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> seats.prepare(args));
        assertTrue(ex.getMessage().contains(why), ex.getMessage());
    }

    @Test
    void aModelSeatRunsTheSupervisorWithTheSeatRequest() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, ",\"model\":\"gpt-6-astra\",\"effort\":\"low\"") + "]");
        seats.start(seats.prepare(args), args, TABLE);

        await("the launch", () -> processes.size() == 1);
        ProcessBuilder builder = launched.get(0);
        assertEquals(java.util.Arrays.asList(dir.resolve("python.exe").toString(), "-m", "llm.seat"), builder.command());
        assertEquals(dir.toFile(), builder.directory());
        JsonObject request = processes.get(0).request();
        Path run = dir.resolve(".run").resolve("llm").resolve(TABLE + "-seat-2");
        assertEquals("chatgpt_subscription", request.get("provider").getAsString());
        assertEquals("gpt-6-astra", request.get("model").getAsString());
        assertEquals("low", request.get("reasoning").getAsString());
        assertEquals(TABLE.toString(), request.get("table_id").getAsString());
        assertEquals("table-pw", request.get("table_password").getAsString());
        assertEquals(2, request.get("seat").getAsInt());
        assertEquals("ChatGPT 2", request.get("username").getAsString());
        assertEquals(17171, request.get("server_port").getAsInt());
        assertEquals("Two Player Duel", request.get("game_type").getAsString());
        assertEquals("Constructed - Freeform Unlimited", request.get("deck_type").getAsString());
        assertEquals(3, request.get("player_count").getAsInt());
        assertEquals(2, request.get("wins_needed").getAsInt());
        assertEquals(ProcessHandle.current().pid(), request.get("owner_pid").getAsLong());
        assertEquals(run.toString(), request.get("run").getAsString());
        assertEquals(dir.resolve(".run").resolve("llm").resolve("catalog.json").toString(), request.get("catalog").getAsString());
        String deck = new String(Files.readAllBytes(Path.of(request.get("deck").getAsString())), StandardCharsets.UTF_8);
        assertTrue(deck.contains("20 [M12:246] Forest"), deck);
        assertTrue(deck.contains("20 [M12:193] Runeclaw Bear"), deck);
        assertFalse(events.stream().anyMatch(json -> json.contains("table-pw")));
    }

    @Test
    void theSeatReportsEachStatusUntilItFinishes() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        FakeProcess process = processes.get(0);
        Path run = Path.of(process.request().get("run").getAsString());
        process.say("joining");
        process.say("waiting_for_start");
        process.say("playing");
        process.say("waiting_for_next_game");
        process.say("playing");
        process.say("completed");
        manifest(run, "{\"status\":\"completed\"}");
        process.end(0);

        await("the finish", () -> statuses().contains("finished"));
        assertEquals(java.util.Arrays.asList("starting", "joined", "playing", "waiting_for_next_game", "playing", "finished"), statuses());
        JsonObject last = JsonParser.parseString(events.get(events.size() - 1)).getAsJsonObject();
        assertEquals("llmSeat", last.get("type").getAsString());
        assertEquals(TABLE.toString(), last.get("tableId").getAsString());
        assertEquals(2, last.get("seat").getAsInt());
        assertEquals("ChatGPT 2", last.get("username").getAsString());
        assertEquals("gpt-6-luna", last.get("model").getAsString());
        assertEquals("low", last.get("effort").getAsString());
        assertTrue(errors.isEmpty());
        assertEquals(0, seats.aliveCount());
        assertEquals("ChatGPT 2", LlmSeats.NAMES.allocate("ChatGPT", 2, "Owner", System.currentTimeMillis()));
    }

    @Test
    void aFailedSeatSaysWhyAndRaisesANonFatalError() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        FakeProcess process = processes.get(0);
        Path run = Path.of(process.request().get("run").getAsString());
        process.say("joining");
        process.say("failed");
        process.say("RuntimeError: The ChatGPT game bridge stopped while joining; see bridge.log");
        manifest(run, "{\"status\":\"failed\",\"error\":\"The ChatGPT game bridge stopped while joining; see bridge.log\"}");
        process.end(1);

        await("the failure", () -> statuses().contains("failed"));
        JsonObject last = JsonParser.parseString(events.get(events.size() - 1)).getAsJsonObject();
        assertEquals("The ChatGPT game bridge stopped while joining; see bridge.log", last.get("reason").getAsString());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("ChatGPT 2 (seat 2) failed: The ChatGPT game bridge"), errors.get(0));
    }

    @Test
    void aSeatThatDiesWithoutAManifestReportsItsLastError() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        FakeProcess process = processes.get(0);
        Path run = Path.of(process.request().get("run").getAsString());
        Files.write(run.resolve("seat-stderr.log"), "Traceback\nModuleNotFoundError: No module named 'psutil'\n".getBytes(StandardCharsets.UTF_8));
        process.end(1);

        await("the failure", () -> statuses().contains("failed"));
        assertEquals("ModuleNotFoundError: No module named 'psutil'", seats.describe(TABLE).get(0).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void aSeatThatCannotStartFailsAndFreesItsName() throws Exception {
        LlmSeats seats = seats(4);
        launchFailure = new IOException("python.exe not found");
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the failure", () -> statuses().contains("failed"));
        assertTrue(seats.describe(TABLE).get(0).getAsJsonObject().get("reason").getAsString().contains("python.exe not found"));
        assertEquals(0, LlmSeats.NAMES.live());
    }

    @Test
    void stoppingATableAsksItsSeatsToCancel() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        FakeProcess process = processes.get(0);
        Path run = Path.of(process.request().get("run").getAsString());
        process.say("playing");
        await("playing", () -> statuses().contains("playing"));

        assertFalse(seats.stopTable(UUID.randomUUID()));
        assertTrue(seats.stopTable(TABLE));
        assertTrue(Files.isRegularFile(run.resolve("cancel.requested")));
        manifest(run, "{\"status\":\"cancelled\",\"reason\":\"XMage table was cancelled or left\"}");
        process.say("cancelled");
        process.end(0);

        await("the stop", () -> statuses().contains("stopped"));
        assertTrue(errors.isEmpty());
        assertFalse(process.killed);
        assertEquals("ChatGPT 1", LlmSeats.NAMES.allocate("ChatGPT", 2, "Owner", System.currentTimeMillis()),
                "a stopped seat's name waits before another seat may log in with it");
    }

    @Test
    void aSeatThatIgnoresItsStopIsKilled() throws Exception {
        LlmSeats seats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        FakeProcess process = processes.get(0);
        process.request();

        assertEquals(Collections.singleton(TABLE), seats.stopAll());
        await("the kill", () -> process.killed);
        await("the stop", () -> statuses().contains("stopped"));
    }

    @Test
    void namesAvoidTheOwnerTheLimitAndLiveSeats() {
        long now = System.currentTimeMillis();
        assertEquals("ChatGPT 2", LlmSeats.NAMES.allocate("ChatGPT", 2, "Owner", now));
        assertEquals("ChatGPT 1", LlmSeats.NAMES.allocate("ChatGPT", 2, "Owner", now));
        assertEquals("Claude 1", LlmSeats.NAMES.allocate("Claude", 2, "Claude 2", now));
        assertEquals("OpenRouter 2", LlmSeats.NAMES.allocate("OpenRouter", 2, "", now));
        assertNull(LlmSeats.NAMES.allocate("A very long provider", 2, "", now));
        assertEquals(4, LlmSeats.NAMES.live());
        LlmSeats.NAMES.release("ChatGPT 1", now, true);
        assertEquals("ChatGPT 3", LlmSeats.NAMES.allocate("ChatGPT", 1, "", now));
        assertEquals("ChatGPT 1", LlmSeats.NAMES.allocate("ChatGPT", 1, "", now + LlmSeats.NAME_COOLDOWN_MS));
    }

    @Test
    void theProxyCapsHowManyModelSeatsPlayAtOnce() throws Exception {
        LlmSeats seats = seats(1);
        JsonObject args = table("[" + seat(2, "") + "]");
        seats.start(seats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        assertRefused(seats, table("[" + seat(2, "") + "]"), "At most 1 model seats");
        assertRefused(seats, JsonParser.parseString("{\"playerTypes\":[\"HUMAN\",\"HUMAN\"],\"llmSeats\":["
                + seat(1, "") + "," + seat(2, "") + "]}").getAsJsonObject(), "At most 1");
    }

    @Test
    void createTableRefusesModelSeatsOnADisabledProxy() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        TableCommands.handle("createTable", null, "c1", table("[" + seat(2, "") + "]"), ctx);
        JsonObject res = lastResult(ctx);
        assertFalse(res.get("ok").getAsBoolean());
        assertEquals(ProxyProtocol.ERR_INVALID_ARGUMENT, res.get("errorCode").getAsString());
        assertEquals(0, ctx.session.tablesCreated);
    }

    @Test
    void leavingATableStopsItsModelSeatsAndRemovesIt() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        ctx.llmSeats.start(ctx.llmSeats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        Path run = Path.of(processes.get(0).request().get("run").getAsString());

        TableCommands.handle("leaveTable", null, "l1", JsonParser.parseString("{\"tableId\":\"" + TABLE + "\"}").getAsJsonObject(), ctx);
        assertTrue(lastResult(ctx).get("ok").getAsBoolean());
        assertEquals(TABLE, ctx.session.leftTable);
        assertTrue(Files.isRegularFile(run.resolve("cancel.requested")));
        assertEquals(TABLE, ctx.session.removedTable);
    }

    @Test
    void leavingATableWithoutModelSeatsRemovesNothing() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        TableCommands.handle("leaveTable", null, "l2", JsonParser.parseString("{\"tableId\":\"" + TABLE + "\"}").getAsJsonObject(), ctx);
        assertEquals(TABLE, ctx.session.leftTable);
        assertNull(ctx.session.removedTable);
    }

    @Test
    void removingATableStopsItsModelSeats() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        ctx.llmSeats.start(ctx.llmSeats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        Path run = Path.of(processes.get(0).request().get("run").getAsString());

        TableCommands.handle("removeTable", null, "r1", JsonParser.parseString("{\"tableId\":\"" + TABLE + "\"}").getAsJsonObject(), ctx);
        assertTrue(lastResult(ctx).get("ok").getAsBoolean());
        assertTrue(Files.isRegularFile(run.resolve("cancel.requested")));
        assertEquals(TABLE, ctx.session.removedTable);
    }

    @Test
    void anotherAccountsTableCannotBeRemovedToStopItsSeats() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        ctx.llmSeats.start(ctx.llmSeats.prepare(args), args, TABLE);
        await("the launch", () -> processes.size() == 1);
        Path run = Path.of(processes.get(0).request().get("run").getAsString());
        ctx.session.owner = false;

        TableCommands.handle("removeTable", null, "r2", JsonParser.parseString("{\"tableId\":\"" + TABLE + "\"}").getAsJsonObject(), ctx);
        assertEquals(ProxyProtocol.ERR_NOT_AUTHORIZED, lastResult(ctx).get("errorCode").getAsString());
        assertFalse(Files.exists(run.resolve("cancel.requested")));
    }

    @Test
    void getLlmProvidersSaysWhenModelSeatsAreOff() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        assertTrue(LlmCommands.handle("getLlmProviders", null, "p1", new JsonObject(), ctx));
        JsonObject res = lastResult(ctx);
        assertTrue(res.get("ok").getAsBoolean());
        assertFalse(res.getAsJsonObject("data").get("enabled").getAsBoolean());
    }

    @Test
    void getLlmProvidersReturnsTheCleanCatalog() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        LlmCommands.handle("getLlmProviders", null, "p2", new JsonObject(), ctx);
        await("the answer", () -> !ctx.gateway.sent.isEmpty());
        JsonObject data = lastResult(ctx).getAsJsonObject("data");
        assertTrue(data.get("enabled").getAsBoolean());
        JsonArray providers = data.getAsJsonArray("providers");
        assertEquals("chatgpt_subscription", providers.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(data.toString().contains("someone@example.com"));
    }

    @Test
    void aCatalogRefreshRunsTheCatalogModule() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        JsonObject args = JsonParser.parseString("{\"refresh\":true}").getAsJsonObject();
        LlmCommands.handle("getLlmProviders", null, "p3", args, ctx);
        await("the refresh", () -> processes.size() == 1);
        assertEquals("llm.catalog", launched.get(0).command().get(2));
        processes.get(0).end(0);
        await("the answer", () -> !ctx.gateway.sent.isEmpty());
        assertTrue(lastResult(ctx).get("ok").getAsBoolean());
    }

    @Test
    void aFailedCatalogRefreshIsAnError() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        Files.write(dir.resolve(".run").resolve("llm").resolve("catalog-stderr.log"),
                "RuntimeError: codex is not installed\n".getBytes(StandardCharsets.UTF_8));
        LlmCommands.handle("getLlmProviders", null, "p4", JsonParser.parseString("{\"refresh\":true}").getAsJsonObject(), ctx);
        await("the refresh", () -> processes.size() == 1);
        processes.get(0).end(1);
        await("the answer", () -> !ctx.gateway.sent.isEmpty());
        JsonObject res = lastResult(ctx);
        assertFalse(res.get("ok").getAsBoolean());
        assertTrue(res.toString().contains("codex is not installed"), res.toString());
    }

    @Test
    void getLlmSeatsListsTheSeatsOfATable() throws Exception {
        TableStagingCommandsTest.StubCtx ctx = new TableStagingCommandsTest.StubCtx();
        ctx.llmSeats = seats(4);
        JsonObject args = table("[" + seat(2, "") + "]");
        ctx.llmSeats.start(ctx.llmSeats.prepare(args), args, TABLE);
        LlmCommands.handle("getLlmSeats", null, "s1", JsonParser.parseString("{\"tableId\":\"" + TABLE + "\"}").getAsJsonObject(), ctx);
        JsonArray seats = lastResult(ctx).getAsJsonArray("data");
        assertEquals(1, seats.size());
        assertEquals("ChatGPT 2", seats.get(0).getAsJsonObject().get("username").getAsString());
        LlmCommands.handle("getLlmSeats", null, "s2", JsonParser.parseString("{\"tableId\":\"" + UUID.randomUUID() + "\"}").getAsJsonObject(), ctx);
        assertEquals(0, lastResult(ctx).getAsJsonArray("data").size());
    }

    private static JsonObject lastResult(TableStagingCommandsTest.StubCtx ctx) {
        return JsonParser.parseString(ctx.gateway.sent.get(ctx.gateway.sent.size() - 1)).getAsJsonObject();
    }
}
