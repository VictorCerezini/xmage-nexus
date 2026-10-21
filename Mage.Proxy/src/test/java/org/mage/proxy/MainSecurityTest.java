package org.mage.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void onlyTheHostsOwnAppIsToldWhereFriendsCanReachIt() throws Exception {
        Config config = Config.parse(new String[]{"--friendsServer", "127.0.0.1:17171", "--wsPort", "8777", "--httpPort", "8778"});
        JsonObject body = JsonParser.parseString(Main.hostFriendsBody(config,
                Collections.singletonList(new String[]{"Radmin \"VPN\"", "26.10.20.30"}))).getAsJsonObject();
        assertEquals(8777, body.get("wsPort").getAsInt());
        assertEquals(8778, body.get("httpPort").getAsInt());
        JsonObject first = body.getAsJsonArray("addresses").get(0).getAsJsonObject();
        assertEquals("Radmin \"VPN\"", first.get("name").getAsString());
        assertEquals("26.10.20.30", first.get("address").getAsString());

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertTrue(Main.askedByThisMachine(loopback, "127.0.0.1:8778"));
        assertTrue(Main.askedByThisMachine(InetAddress.getByName("::1"), "[::1]:8778"));
        assertTrue(Main.askedByThisMachine(loopback, "localhost"));
        assertFalse(Main.askedByThisMachine(InetAddress.getByName("26.10.20.30"), "127.0.0.1:8778"), "a friend");
        assertFalse(Main.askedByThisMachine(loopback, "26.10.20.30:8778"), "the page at the shared address");
        assertFalse(Main.askedByThisMachine(loopback, "rebind.example:8778"), "a name that resolves here");
        assertFalse(Main.askedByThisMachine(loopback, null));

        assertEquals("http://tauri.localhost", Main.readableBy(true, "http://tauri.localhost"));
        assertEquals("tauri://localhost", Main.readableBy(true, "tauri://localhost"));
        assertNull(Main.readableBy(true, "https://attacker.example"));
        assertNull(Main.readableBy(true, "http://127.0.0.1:8778"), "same origin needs no permission");
        assertNull(Main.readableBy(true, null));
        assertNull(Main.readableBy(false, "http://tauri.localhost"), "not asked by this machine");
    }

    @Test
    void friendsAreOnlyGivenAddressesTheyCanReach() throws Exception {
        assertTrue(Main.shareable(InetAddress.getByName("26.10.20.30")));
        assertTrue(Main.shareable(InetAddress.getByName("192.168.0.12")));
        for (String no : new String[]{"127.0.0.1", "169.254.10.20", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "2001:db8::1"}) {
            assertFalse(Main.shareable(InetAddress.getByName(no)), no);
        }
        for (String[] address : Main.shareableAddresses()) {
            assertNotNull(address[0]);
            assertTrue(Main.shareable(InetAddress.getByName(address[1])), address[1]);
        }
    }

    @Test
    void resolvesOnlyFilesInsideWebDirectory() throws IOException {
        Path web = Files.createDirectory(tempDir.resolve("web"));
        Path index = Files.write(web.resolve("index.html"), new byte[]{1, 2, 3});
        Path outside = Files.write(tempDir.resolve("outside.txt"), new byte[]{4});

        File resolved = Main.resolveWebFile(web.toString(), "/index.html");

        assertEquals(index.toFile().getCanonicalFile(), resolved);
        assertNull(Main.resolveWebFile(web.toString(), "../outside.txt"));
        assertNull(Main.resolveWebFile(web.toString(), "..\\outside.txt"));
        assertNull(Main.resolveWebFile(web.toString(), "nested/../../outside.txt"));
        assertTrue(outside.toFile().isFile());
    }

    @Test
    void detectsTraversalSegmentsBeforeResourceFallback() {
        assertTrue(Main.hasTraversalSegments("/../pom.xml"));
        assertTrue(Main.hasTraversalSegments("/nested/../pom.xml"));
        assertTrue(Main.hasTraversalSegments("..\\pom.xml"));
        assertTrue(!Main.hasTraversalSegments("/assets/app.js"));
    }

    @Test
    void theAdminTokenIsOnlyAcceptedAsABearerHeader() {
        // the query string form is gone: it landed in the access log, the browser history and
        // every proxy in between, and this endpoint is the one thing that lists the users
        assertEquals("secret", Main.stripBearer("Bearer secret"));
        assertEquals("", Main.stripBearer("secret"));
        assertEquals("", Main.stripBearer(null));
        assertEquals("", Main.stripBearer("Basic secret"));
    }

    @Test
    void onlyViteFingerprintedAssetsAreCachedForever() {
        // called with the request path, as serveFile does
        assertTrue(Main.isFingerprinted("/assets/index-SpEjMz13.js"));
        assertTrue(Main.isFingerprinted("/assets/index-CbvLKDBy.css"));
        assertTrue(Main.isFingerprinted("/assets/sounds/turn-bell-a1b2c3d4.mp3"));
        // an unpinned client or entry point must not survive a deploy
        assertTrue(!Main.isFingerprinted("/index.html"));
        assertTrue(!Main.isFingerprinted("/splash-i18n.js"));
        assertTrue(!Main.isFingerprinted("/assets/app.js"));
        assertTrue(!Main.isFingerprinted("/assets/index-toolonghash1.js"));
        // copied verbatim from web/public: overwritten on deploy, never immutable
        assertTrue(!Main.isFingerprinted("/sounds/turn-bell.mp3"));
        assertTrue(!Main.isFingerprinted("/logo-title.jpg"));
        // a bare file name has no /assets/ prefix: never immutable on that alone
        assertTrue(!Main.isFingerprinted("index-SpEjMz13.js"));
    }

    @Test
    void scriptsMayOnlyComeFromTheSameOrigin() {
        // the web client has no inline script: an injected one (a release note, a deck name)
        // must not run, so script-src carries neither 'unsafe-inline' nor 'unsafe-eval'
        String csp = Main.CONTENT_SECURITY_POLICY;
        String scriptSrc = java.util.Arrays.stream(csp.split(";"))
                .map(String::trim).filter(d -> d.startsWith("script-src")).findFirst().orElseThrow();
        assertEquals("script-src 'self'", scriptSrc);
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(!csp.contains("unsafe-eval"));
    }

    @Test
    void theWebClientsFontsMayLoad() {
        // index.html links Google Fonts: blocked, every page this proxy serves fell back to system fonts
        String csp = Main.CONTENT_SECURITY_POLICY;
        assertTrue(csp.contains("style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;"), csp);
        assertTrue(csp.contains("font-src 'self' https://fonts.gstatic.com data:;"), csp);
    }
}
