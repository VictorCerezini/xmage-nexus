package org.mage.proxy;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

final class LlmRuntime {

    interface Launcher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    private final Path root;
    private final Path python;
    private final Path catalog;
    private final int maxSeats;
    private final Launcher launcher;

    LlmRuntime(Path root, Path python, Path catalog, int maxSeats, Launcher launcher) {
        this.root = root;
        this.python = python;
        this.catalog = catalog;
        this.maxSeats = maxSeats;
        this.launcher = launcher;
    }

    static LlmRuntime fromConfig(Config config) {
        if (!config.isLlmEnabled()) {
            return null;
        }
        if (!isLoopbackHost(config.getBindAddress())) {
            throw new IllegalStateException("--llm needs the proxy bound to loopback, not --bind "
                    + config.getBindAddress() + ": model seats spend this machine's subscriptions");
        }
        for (String origin : config.getAllowedOrigins()) {
            if (!Gateway.isLocalOrigin(origin)) {
                throw new IllegalStateException("--llm cannot run with the non-local allowed origin " + origin);
            }
        }
        Path root = findRoot(config.getLlmRoot());
        Path python = config.getLlmPython().isEmpty() ? defaultPython(root) : Paths.get(config.getLlmPython());
        if (!Files.isRegularFile(python)) {
            throw new IllegalStateException("No Python for model seats at " + python
                    + ": run uv sync --project llm, or pass --llmPython");
        }
        Path catalog = root.resolve(config.getLlmCatalog()).normalize();
        return new LlmRuntime(root, python.toAbsolutePath(), catalog, config.getLlmMaxSeats(), ProcessBuilder::start);
    }

    static Path findRoot(String configured) {
        if (!configured.isEmpty()) {
            Path root = Paths.get(configured).toAbsolutePath().normalize();
            if (!isRoot(root)) {
                throw new IllegalStateException("--llmRoot " + root + " has no llm/seat.py");
            }
            return root;
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        for (Path candidate : new Path[]{cwd, cwd.getParent()}) {
            if (candidate != null && isRoot(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No llm/seat.py in " + cwd + " or its parent: pass --llmRoot");
    }

    private static boolean isRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("llm").resolve("seat.py"));
    }

    static Path defaultPython(Path root) {
        Path venv = root.resolve("llm").resolve(".venv");
        return File.separatorChar == '\\'
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (IOException ex) {
            return false;
        }
    }

    Path root() {
        return root;
    }

    Path python() {
        return python;
    }

    Path catalog() {
        return catalog;
    }

    Path runRoot() {
        return root.resolve(".run").resolve("llm");
    }

    int maxSeats() {
        return maxSeats;
    }

    Process start(ProcessBuilder builder) throws IOException {
        return launcher.start(builder);
    }
}
