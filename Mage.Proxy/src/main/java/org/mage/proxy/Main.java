package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Mage.Proxy entry point.
 * <p>
 * Usage:
 * <pre>
 * java -jar mage-proxy.jar [--host beta.xmage.today] [--port 17171] [--username u] [--password p] [--wsPort 8787] [--httpPort 8788]
 * </pre>
 * Then open http://localhost:8788/index.html (test page) which connects to ws://localhost:8787
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);

        Gateway gateway;
        try {
            gateway = new Gateway(config);
        } catch (IllegalStateException ex) {
            System.err.println("[proxy] " + ex.getMessage());
            System.exit(2);
            return;
        }
        gateway.start();

        // lazy build of the proxy's card DB (deck validation)
        DeckValidation.ensureCardDatabaseAsync();

        // restore ids on disk: a re-login after a deploy (or a host IP change) takes back the
        // session the server still holds instead of being refused with "already connected"
        RestoreIds.configure(config);

        System.out.println("[proxy] XMage proxy started");
        System.out.println("[proxy]   websocket gateway : ws://" + config.getBindAddress() + ":" + config.getWsPort() + "/");
        System.out.println("[proxy]   test page         : http://" + config.getBindAddress() + ":" + config.getHttpPort() + "/index.html");
        System.out.println("[proxy]   protocol version  : " + Config.PROTOCOL_VERSION);
        System.out.println("[proxy]   multi-tenant     : each WebSocket connection = its own XMage session");
        if (gateway.llmRuntime() != null) {
            System.out.println("[proxy]   model seats       : on, catalog " + gateway.llmRuntime().catalog()
                    + ", at most " + gateway.llmRuntime().maxSeats() + " at once");
        }
        if (config.isFriendsMode()) {
            System.out.println("[proxy]   friends mode      : every login goes to " + config.getFriendsServerHost()
                    + ":" + config.getFriendsServerPort() + ", no model seats");
        }

        StatsService stats = config.isStatsServiceEnabled() ? StatsService.forProxy(config, gateway) : null;
        if (stats != null) {
            stats.start();
            System.out.println("[proxy]   statistics API    : /api/ranking, /api/matches (session as " + StatsService.USERNAME + ")");
        }

        startHttpServer(config, gateway, stats);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stats != null) {
                stats.stop();
            }
            for (ProxyClient pc : gateway.getSessions()) {
                pc.shutdown();
            }
            try {
                gateway.stop(1000);
            } catch (InterruptedException ignored) {
            }
        }));
    }

    private static void startHttpServer(Config config, Gateway gateway, StatsService stats) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.getBindAddress(), config.getHttpPort()), 0);
        // Without an executor the JDK uses a single-threaded dispatcher, so one slow
        // /admin/status (it serialises 500 events) or one large static file blocked every other
        // request, including the health checks below.
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "proxy-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> serveFile(exchange, config.getWebDir()));
        server.createContext("/health", exchange -> serveHealth(exchange, true));
        server.createContext("/ready", exchange -> serveHealth(exchange, false));
        StatsApi.Source source = stats != null ? stats : StatsApi.UNAVAILABLE;
        server.createContext("/api/", exchange -> serveStats(exchange, source, config.getAllowedOrigins()));
        if (!config.getAdminToken().isEmpty()) {
            server.createContext("/admin/status", exchange -> serveAdmin(exchange, gateway, config.getAdminToken()));
        }
        if (config.isFriendsMode()) {
            server.createContext(FRIENDS_PATH, exchange -> serveFriends(exchange, config));
        }
        server.start();
    }

    /** The public statistics API: routing and CORS live in {@link StatsApi}, this only writes the result. */
    private static void serveStats(HttpExchange exchange, StatsApi.Source source, java.util.Set<String> allowedOrigins) {
        try {
            java.net.URI uri = exchange.getRequestURI();
            StatsApi.Response response = StatsApi.handle(exchange.getRequestMethod(), uri.getPath(),
                    uri.getRawQuery(), exchange.getRequestHeaders().getFirst("Origin"), allowedOrigins, source);
            for (java.util.Map.Entry<String, String> header : response.headers.entrySet()) {
                exchange.getResponseHeaders().add(header.getKey(), header.getValue());
            }
            byte[] bytes = response.body == null ? null
                    : response.body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status, bytes == null ? -1 : bytes.length);
            if (bytes != null) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    /** Where the page a friends-mode proxy serves asks how to reach it. */
    static final String FRIENDS_PATH = "/friends.json";

    /**
     * The page cannot know the WebSocket port: it was opened on the http one. Only the port is
     * told, the page connects to the address it was opened at, and the server is the proxy's to
     * pick ({@link Gateway#toFriendsServer}).
     */
    static String friendsBody(Config config) {
        return "{\"wsPort\":" + config.getWsPort() + "}\n";
    }

    /**
     * The host's own app asks too, from this machine, to show the host the link to send: it is
     * also told the page's port and the addresses a friend could open it at. Only this machine
     * is told them, a friend's page gets {@link #friendsBody}.
     */
    static String hostFriendsBody(Config config, List<String[]> addresses) {
        JsonObject body = new JsonObject();
        body.addProperty("wsPort", config.getWsPort());
        body.addProperty("httpPort", config.getHttpPort());
        JsonArray list = new JsonArray();
        for (String[] address : addresses) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", address[0]);
            entry.addProperty("address", address[1]);
            list.add(entry);
        }
        body.add("addresses", list);
        return body + "\n";
    }

    /** The desktop app's page (Windows, then macOS and Linux): no other page may read the host's answer. */
    static final Set<String> DESKTOP_ORIGINS = new HashSet<>(Arrays.asList(
            "http://tauri.localhost", "https://tauri.localhost", "tauri://localhost"));

    /** The origin the answer may be read from, or null: only the desktop app's page, asking from this machine. */
    static String readableBy(boolean fromThisMachine, String origin) {
        return fromThisMachine && origin != null && DESKTOP_ORIGINS.contains(origin) ? origin : null;
    }

    /** This machine's addresses on the networks that are up, with each network's name (a VPN such as Radmin VPN, the home network). */
    static List<String[]> shareableAddresses() {
        List<String[]> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                String name = nic.getDisplayName() != null ? nic.getDisplayName() : nic.getName();
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (shareable(address)) {
                        found.add(new String[]{name, address.getHostAddress()});
                    }
                }
            }
        } catch (SocketException ignored) {
            // no list: the app shows no link rather than a wrong one
        }
        return found;
    }

    /** An address another machine could reach and a person could type: IPv4, not loopback nor self-assigned (169.254). */
    static boolean shareable(InetAddress address) {
        return address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress() && !address.isMulticastAddress();
    }

    /**
     * Asked from this machine and at its loopback address: a name that resolves to it (DNS
     * rebinding) would make another site's page look like this machine's.
     */
    static boolean askedByThisMachine(InetAddress remote, String hostHeader) {
        if (!remote.isLoopbackAddress() || hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(java.util.Locale.ROOT);
        int port = host.lastIndexOf(':');
        if (port > host.lastIndexOf(']')) {
            host = host.substring(0, port);
        }
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }

    private static void serveFriends(HttpExchange exchange, Config config) {
        boolean fromThisMachine = askedByThisMachine(exchange.getRemoteAddress().getAddress(),
                exchange.getRequestHeaders().getFirst("Host"));
        String body = fromThisMachine ? hostFriendsBody(config, shareableAddresses()) : friendsBody(config);
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        String reader = readableBy(fromThisMachine, exchange.getRequestHeaders().getFirst("Origin"));
        if (reader != null) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", reader);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
        try {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    /**
     * {@code /health} is liveness: the process answers. {@code /ready} is readiness: it is only
     * 200 once the card database is built, so a load balancer or the launcher waits instead of
     * sending players into a proxy whose deck validation answers FAILED. Both are unauthenticated
     * and carry no session data.
     */
    private static void serveHealth(HttpExchange exchange, boolean liveness) {
        DeckValidation.State state = DeckValidation.getState();
        boolean ready = state == DeckValidation.State.READY;
        boolean ok = liveness || ready;
        String body = "{\"ok\":" + ok + ",\"liveness\":" + liveness + ",\"ready\":" + ready
                + ",\"cardDb\":" + quote(state.name()) + ",\"sessions\":" + Activity.sessionCount()
                + ",\"threads\":" + ManagementFactory.getThreadMXBean().getThreadCount()
                + ",\"uptimeSeconds\":" + Activity.uptimeSeconds() + "}\n";
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        try {
            exchange.sendResponseHeaders(ok ? 200 : 503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /**
     * Resolves an HTTP request path to a file inside webDir.
     * Returns null when the canonical path escapes webDir (path traversal),
     * does not exist, or webDir is not a directory.
     */
    static File resolveWebFile(String webDir, String path) throws IOException {
        File base = new File(webDir).getCanonicalFile();
        if (!base.isDirectory()) {
            return null;
        }
        String p = (path == null || path.isEmpty()) ? "/" : path;
        File file = new File(base, p).getCanonicalFile();
        String filePath = file.getPath();
        String basePath = base.getPath();
        if (!filePath.equals(basePath) && !filePath.startsWith(basePath + File.separator)) {
            return null;
        }
        return file.isFile() ? file : null;
    }

    /** True when the decoded path tries to climb a level (guard for the resource fallback). */
    static boolean hasTraversalSegments(String path) {
        if (path == null) {
            return false;
        }
        String p = path;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.equals("..") || p.startsWith("../") || p.contains("/../") || p.endsWith("/..")
                || p.contains("\\..") || p.startsWith("..\\")) {
            return true;
        }
        return false;
    }

    private static void serveAdmin(HttpExchange exchange, Gateway gateway, String token) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        // Header only: a token in the query string lands in the access log, the browser history
        // and every proxy in between (ops/status scrapes this endpoint from journald).
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!token.equals(stripBearer(auth))) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        com.google.gson.JsonObject out = com.google.gson.JsonParser
                .parseString(Activity.snapshot()).getAsJsonObject();
        // thread and session counters: the leak this proxy had for weeks was invisible without them
        out.add("runtime", gateway.runtime());
        out.add("sessions", gateway.sessionsDetail());
        byte[] body = out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    static String stripBearer(String auth) {
        if (auth == null) {
            return "";
        }
        return auth.startsWith("Bearer ") ? auth.substring("Bearer ".length()) : "";
    }

    private static void serveFile(HttpExchange exchange, String webDir) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }
            // prevent path traversal: only files inside webDir
            File file = resolveWebFile(webDir, path);
            if (file != null) {
                byte[] content = Files.readAllBytes(file.toPath());
                exchange.getResponseHeaders().add("Content-Type", contentType(file.getName()));
                applySecurityHeaders(exchange, path);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
                return;
            }
            if (hasTraversalSegments(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            // fallback: serve from the jar resources (/web/index.html)
            java.io.InputStream in = Main.class.getResourceAsStream("/web" + path);
            if (in != null) {
                byte[] content;
                try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    content = bos.toByteArray();
                }
                exchange.getResponseHeaders().add("Content-Type", contentType(path));
                applySecurityHeaders(exchange, path);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        } catch (Exception ex) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }

    /**
     * The bundled web client is code this proxy serves to every player, so the browser is told not
     * to sniff a response, not to embed it elsewhere, and only ever to run its own scripts.
     * Fingerprinted build assets may be cached forever; {@code index.html} and the splash may not,
     * or a deploy would keep serving the old client. Its fonts come from Google Fonts, the same two
     * hosts the desktop app's own policy allows.
     */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; connect-src 'self' ws: wss: http: https:; "
                    + "img-src 'self' data: blob: https:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                    + "font-src 'self' https://fonts.gstatic.com data:; "
                    + "script-src 'self'; media-src 'self' data: blob:";

    private static void applySecurityHeaders(HttpExchange exchange, String name) {
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().add("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().add("Referrer-Policy", "same-origin");
        exchange.getResponseHeaders().add("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        if (isFingerprinted(name)) {
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        }
    }

    /**
     * True for a Vite build output: the request path is under {@code /assets/} and the file name
     * carries the 8-character base64url hash Vite appends. That is the real signal, and it beats an
     * extension list: anything copied verbatim from {@code web/public} (sounds, logos, the splash)
     * keeps its plain name, so a deploy overwrites it and it must not be cached for a year.
     *
     * <p>Called with the request path, not the file name: the {@code /assets/} prefix only exists
     * in the path, and a bare {@code index-SpEjMz13.js} would then never be recognised.
     */
    static boolean isFingerprinted(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        int assets = lower.indexOf("/assets/");
        if (assets < 0) {
            return false;
        }
        String file = lower.substring(assets + "/assets/".length());
        int slash = file.lastIndexOf('/');
        if (slash >= 0) {
            file = file.substring(slash + 1);
        }
        int dot = file.lastIndexOf('.');
        if (dot < 9 || dot - 9 != file.lastIndexOf('-')) {
            return false;
        }
        for (int i = dot - 8; i < dot; i++) {
            char c = file.charAt(i);
            boolean base64url = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_' || c == '-';
            if (!base64url) {
                return false;
            }
        }
        return true;
    }

    static String contentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (lower.endsWith(".otf")) {
            return "font/otf";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
