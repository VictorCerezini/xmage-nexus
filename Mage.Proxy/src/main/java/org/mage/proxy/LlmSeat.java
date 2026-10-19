package org.mage.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

final class LlmSeat {

    private static final Logger logger = Logger.getLogger(LlmSeat.class.getName());

    static final String STARTING = "starting";
    static final String JOINED = "joined";
    static final String PLAYING = "playing";
    static final String WAITING_FOR_NEXT_GAME = "waiting_for_next_game";
    static final String FINISHED = "finished";
    static final String STOPPED = "stopped";
    static final String FAILED = "failed";

    private static final ScheduledExecutorService killer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-seat-stop");
        t.setDaemon(true);
        return t;
    });

    final UUID tableId;
    final int seat;
    final String username;
    final String provider;
    final String model;
    final String effort;
    final Path run;
    private final Consumer<LlmSeat> onChange;
    private final long stopGraceMs;

    private volatile Process process = null;
    private volatile String status = STARTING;
    private volatile String reason = null;
    private volatile boolean stopRequested = false;
    private volatile String lastLine = "";

    LlmSeat(UUID tableId, int seat, String username, LlmCatalog.Selection selection, Path run,
            long stopGraceMs, Consumer<LlmSeat> onChange) {
        this.tableId = tableId;
        this.seat = seat;
        this.username = username;
        this.provider = selection.provider;
        this.model = selection.model;
        this.effort = selection.effort;
        this.run = run;
        this.stopGraceMs = stopGraceMs;
        this.onChange = onChange;
    }

    String status() {
        return status;
    }

    String reason() {
        return reason;
    }

    boolean isTerminal() {
        return FINISHED.equals(status) || STOPPED.equals(status) || FAILED.equals(status);
    }

    void launch(LlmRuntime runtime, JsonObject request) {
        ProcessBuilder builder = new ProcessBuilder(runtime.python().toString(), "-m", "llm.seat")
                .directory(runtime.root().toFile())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(run.resolve("seat-stderr.log").toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        onChange.accept(this);
        try {
            process = runtime.start(builder);
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            if (process != null) {
                kill(process);
            }
            finish(FAILED, "Could not start the model seat: " + ex.getMessage());
            return;
        }
        Thread reader = new Thread(this::follow, "llm-seat-" + username);
        reader.setDaemon(true);
        reader.start();
    }

    private void follow() {
        Process proc = process;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = out.readLine(); line != null; line = out.readLine()) {
                String trimmed = line.trim();
                String live = liveStatus(trimmed);
                if (live != null) {
                    if (!stopRequested && !live.equals(status)) {
                        status = live;
                        onChange.accept(this);
                    }
                } else if (!trimmed.isEmpty() && !isFinalStatus(trimmed)) {
                    lastLine = trimmed;
                }
            }
        } catch (IOException ex) {
            logger.log(Level.FINE, "model seat output closed", ex);
        }
        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            kill(proc);
            exit = -1;
        }
        finishFromManifest(exit);
    }

    void stop() {
        if (isTerminal() || stopRequested) {
            return;
        }
        stopRequested = true;
        try {
            Files.write(run.resolve("cancel.requested"), new byte[0]);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "could not ask model seat " + username + " to stop", ex);
        }
        Process proc = process;
        if (proc == null) {
            return;
        }
        killer.schedule(() -> {
            if (proc.isAlive()) {
                logger.info("model seat " + username + " ignored its stop request; killing it");
                kill(proc);
            }
        }, stopGraceMs, TimeUnit.MILLISECONDS);
    }

    private void finishFromManifest(int exit) {
        JsonObject manifest = readManifest();
        String outcome = manifest == null ? null : text(manifest, "status");
        if ("completed".equals(outcome)) {
            finish(FINISHED, null);
        } else if (stopRequested) {
            finish(STOPPED, null);
        } else if ("cancelled".equals(outcome)) {
            finish(STOPPED, text(manifest, "reason"));
        } else if (exit == 0 && outcome == null) {
            finish(FINISHED, null);
        } else {
            String why = manifest == null ? null : text(manifest, "error");
            if (why == null || why.isEmpty()) {
                why = lastLine;
            }
            if (why == null || why.isEmpty()) {
                why = lastLine(run.resolve("seat-stderr.log"));
            }
            finish(FAILED, why.isEmpty() ? "The model seat stopped with exit code " + exit : why);
        }
    }

    void fail(String why) {
        finish(FAILED, why);
    }

    private synchronized void finish(String terminal, String why) {
        if (isTerminal()) {
            return;
        }
        reason = why;
        status = terminal;
        onChange.accept(this);
    }

    private JsonObject readManifest() {
        Path file = run.resolve("manifest.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("tableId", tableId.toString());
        out.addProperty("seat", seat);
        out.addProperty("username", username);
        out.addProperty("provider", provider);
        out.addProperty("model", model);
        out.addProperty("effort", effort);
        out.addProperty("status", status);
        if (reason != null && !reason.isEmpty()) {
            out.addProperty("reason", reason);
        }
        return out;
    }

    static String liveStatus(String line) {
        switch (line) {
            case "joining":
                return STARTING;
            case "waiting_for_start":
                return JOINED;
            case "playing":
                return PLAYING;
            case "waiting_for_next_game":
                return WAITING_FOR_NEXT_GAME;
            default:
                return null;
        }
    }

    private static boolean isFinalStatus(String line) {
        return "completed".equals(line) || "cancelled".equals(line) || "failed".equals(line);
    }

    static void kill(Process proc) {
        List<ProcessHandle> tree = proc.descendants().collect(Collectors.toList());
        proc.destroyForcibly();
        for (ProcessHandle child : tree) {
            child.destroyForcibly();
        }
    }

    static String lastLine(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return "";
    }

    private static String text(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
