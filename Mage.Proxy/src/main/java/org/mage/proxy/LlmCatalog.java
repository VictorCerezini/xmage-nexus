package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class LlmCatalog {

    static final long REFRESH_TIMEOUT_MS = 120_000;

    private static final List<String> PROVIDER_TEXT = Arrays.asList("id", "playerName", "playerType", "auth");
    private static final List<String> MODEL_TEXT = Arrays.asList("id", "name", "defaultEffort");
    private static final List<String> MODEL_NUMBERS = Arrays.asList("contextLength", "maxOutput");
    private static final List<String> QUOTA_NUMBERS = Arrays.asList("used", "limit", "remaining");

    static final class Selection {
        final String provider;
        final String playerName;
        final String model;
        final String effort;

        Selection(String provider, String playerName, String model, String effort) {
            this.provider = provider;
            this.playerName = playerName;
            this.model = model;
            this.effort = effort;
        }
    }

    private final LlmRuntime runtime;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-catalog");
        t.setDaemon(true);
        return t;
    });
    private CompletableFuture<JsonObject> inFlight = null;

    LlmCatalog(LlmRuntime runtime) {
        this.runtime = runtime;
    }

    synchronized CompletableFuture<JsonObject> providers(boolean refresh) {
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }
        if (!refresh && Files.isRegularFile(runtime.catalog())) {
            try {
                return CompletableFuture.completedFuture(read());
            } catch (IOException | RuntimeException ex) {
                CompletableFuture<JsonObject> failed = new CompletableFuture<>();
                failed.completeExceptionally(ex);
                return failed;
            }
        }
        inFlight = CompletableFuture.supplyAsync(this::refreshNow, refresher);
        return inFlight;
    }

    JsonObject read() throws IOException {
        if (!Files.isRegularFile(runtime.catalog())) {
            throw new IllegalArgumentException("No model catalog yet: ask for getLlmProviders first");
        }
        String text = new String(Files.readAllBytes(runtime.catalog()), StandardCharsets.UTF_8);
        return sanitize(JsonParser.parseString(text).getAsJsonObject());
    }

    private JsonObject refreshNow() {
        try {
            Files.createDirectories(runtime.catalog().getParent());
            File errors = runtime.catalog().resolveSibling("catalog-stderr.log").toFile();
            ProcessBuilder builder = new ProcessBuilder(runtime.python().toString(), "-m", "llm.catalog",
                    runtime.catalog().toString())
                    .directory(runtime.root().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(errors);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            Process process = runtime.start(builder);
            process.getOutputStream().close();
            if (!process.waitFor(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("The model catalog did not refresh within two minutes");
            }
            if (process.exitValue() != 0) {
                String reason = LlmSeat.lastLine(errors.toPath());
                throw new IllegalStateException("The model catalog refresh failed"
                        + (reason.isEmpty() ? " (exit code " + process.exitValue() + ")" : ": " + reason));
            }
            return read();
        } catch (IOException ex) {
            throw new CompletionException(new IllegalStateException("Could not refresh the model catalog: " + ex.getMessage(), ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    static JsonObject sanitize(JsonObject raw) {
        JsonArray providers = new JsonArray();
        for (JsonObject provider : objects(raw.get("providers"))) {
            JsonObject clean = copyText(provider, PROVIDER_TEXT);
            if (!clean.has("id")) {
                continue;
            }
            JsonElement quota = provider.get("freeRequestsToday");
            if (quota != null && quota.isJsonObject()) {
                clean.add("freeRequestsToday", copyNumbers(quota.getAsJsonObject(), QUOTA_NUMBERS));
            }
            JsonArray models = new JsonArray();
            for (JsonObject model : objects(provider.get("models"))) {
                JsonObject cleanModel = copyText(model, MODEL_TEXT);
                if (!cleanModel.has("id")) {
                    continue;
                }
                JsonArray efforts = new JsonArray();
                JsonElement rawEfforts = model.get("efforts");
                if (rawEfforts != null && rawEfforts.isJsonArray()) {
                    for (JsonElement effort : rawEfforts.getAsJsonArray()) {
                        if (effort.isJsonPrimitive() && effort.getAsJsonPrimitive().isString()) {
                            efforts.add(effort.getAsString());
                        }
                    }
                }
                cleanModel.add("efforts", efforts);
                cleanModel.addProperty("isDefault", bool(model, "isDefault"));
                for (String key : MODEL_NUMBERS) {
                    JsonElement value = model.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        cleanModel.addProperty(key, value.getAsLong());
                    }
                }
                models.add(cleanModel);
            }
            clean.add("models", models);
            providers.add(clean);
        }
        JsonArray unavailable = new JsonArray();
        for (JsonObject entry : objects(raw.get("unavailable"))) {
            JsonObject clean = copyText(entry, Arrays.asList("id", "reason"));
            if (clean.has("id")) {
                unavailable.add(clean);
            }
        }
        JsonObject out = new JsonObject();
        out.add("providers", providers);
        out.add("unavailable", unavailable);
        return out;
    }

    static Selection select(JsonObject catalog, String providerId, String modelId, String effort) {
        JsonObject provider = null;
        for (JsonObject candidate : objects(catalog.get("providers"))) {
            if (candidate.get("id").getAsString().equals(providerId)) {
                provider = candidate;
            }
        }
        if (provider == null) {
            for (JsonObject entry : objects(catalog.get("unavailable"))) {
                if (entry.get("id").getAsString().equals(providerId)) {
                    String reason = entry.has("reason") ? entry.get("reason").getAsString() : "";
                    throw new IllegalArgumentException("Model provider " + providerId + " is not available"
                            + (reason.isEmpty() ? "" : ": " + reason));
                }
            }
            throw new IllegalArgumentException("Unknown model provider: " + providerId);
        }
        List<JsonObject> models = objects(provider.get("models"));
        JsonObject model = null;
        for (JsonObject candidate : models) {
            boolean wanted = modelId == null || modelId.isEmpty()
                    ? candidate.get("isDefault").getAsBoolean()
                    : candidate.get("id").getAsString().equals(modelId);
            if (wanted) {
                model = candidate;
                break;
            }
        }
        if (model == null && (modelId == null || modelId.isEmpty()) && !models.isEmpty()) {
            model = models.get(0);
        }
        if (model == null) {
            throw new IllegalArgumentException(provider.get("id").getAsString() + " has no model "
                    + (modelId == null || modelId.isEmpty() ? "to play with" : modelId));
        }
        JsonArray efforts = model.getAsJsonArray("efforts");
        String chosen = effort == null || effort.isEmpty()
                ? (model.has("defaultEffort") ? model.get("defaultEffort").getAsString() : "")
                : effort;
        if (efforts.size() > 0 && !efforts.contains(new com.google.gson.JsonPrimitive(chosen))) {
            throw new IllegalArgumentException(model.get("id").getAsString() + " has no reasoning level " + chosen);
        }
        String playerName = provider.has("playerName") ? provider.get("playerName").getAsString() : provider.get("id").getAsString();
        return new Selection(provider.get("id").getAsString(), playerName, model.get("id").getAsString(), chosen);
    }

    private static List<JsonObject> objects(JsonElement element) {
        List<JsonObject> out = new java.util.ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    out.add(item.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static JsonObject copyText(JsonObject source, List<String> keys) {
        JsonObject out = new JsonObject();
        for (String key : keys) {
            JsonElement value = source.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                out.addProperty(key, value.getAsString());
            }
        }
        return out;
    }

    private static JsonObject copyNumbers(JsonObject source, List<String> keys) {
        JsonObject out = new JsonObject();
        for (String key : keys) {
            JsonElement value = source.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.addProperty(key, value.getAsLong());
            }
        }
        return out;
    }

    private static boolean bool(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
}
