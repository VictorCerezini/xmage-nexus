package org.mage.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySecurityTest {

    @Test
    void defaultPolicyAllowsOnlyLocalBrowserOrigins() {
        Gateway gateway = new Gateway(Config.parse(new String[0]), 0);

        assertTrue(gateway.originAllowed(null));
        assertTrue(gateway.originAllowed("http://localhost:5173"));
        assertTrue(gateway.originAllowed("http://127.0.0.1:8788"));
        assertFalse(gateway.originAllowed("https://attacker.example"));
        assertFalse(gateway.originAllowed("not a uri"));
    }

    @Test
    void allowsTheWindowsTauriLauncherVirtualHost() {
        Gateway gateway = new Gateway(Config.parse(new String[0]), 0);

        // WebView2 (Windows) no soporta el esquema custom tauri:// y sirve la
        // app vía el virtual host http://tauri.localhost; macOS/Linux usan
        // tauri://localhost (host ya "localhost", cubierto arriba).
        assertTrue(gateway.originAllowed("http://tauri.localhost"));
        assertTrue(gateway.originAllowed("https://tauri.localhost"));
        assertFalse(gateway.originAllowed("http://evil-tauri.localhost.attacker.example"));
    }

    @Test
    void explicitOriginsReplaceTheDefaultPolicy() {
        Config config = Config.parse(new String[]{"--allowedOrigins", "https://client.example"});
        Gateway gateway = new Gateway(config, 0);

        assertTrue(gateway.originAllowed("https://client.example"));
        assertFalse(gateway.originAllowed("http://localhost:5173"));
    }

    @Test
    void friendsModeAcceptsItsOwnPageAtThisMachinesAddresses() throws Exception {
        Config config = Config.parse(new String[]{"--friendsServer", "127.0.0.1:17171", "--httpPort", "8790"});
        Gateway gateway = new Gateway(config, 0);

        assertTrue(gateway.originAllowed("http://127.0.0.1:8790"));
        assertTrue(gateway.originAllowed("http://[::1]:8790"));
        assertFalse(gateway.originAllowed("http://8.8.8.8:8790"), "an address of another machine");
        assertFalse(gateway.originAllowed("https://attacker.example"));
        assertFalse(Gateway.isOwnPageOrigin("http://127.0.0.1:8791", 8790), "another port");
        assertFalse(Gateway.isOwnPageOrigin("https://127.0.0.1:8790", 8790), "not the plain http page");
        assertFalse(Gateway.isOwnPageOrigin("http://rebind.example:8790", 8790), "a name is never resolved");
        assertFalse(Gateway.isOwnPageOrigin("http://999.1.1.1:8790", 8790));
        String own = firstNonLoopbackAddress();
        if (own != null) {
            assertTrue(gateway.originAllowed("http://" + own + ":8790"), own);
            assertFalse(new Gateway(Config.parse(new String[]{"--httpPort", "8790"}), 0)
                    .originAllowed("http://" + own + ":8790"), "only in friends mode");
        }
    }

    private static String firstNonLoopbackAddress() throws Exception {
        for (java.net.NetworkInterface nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            for (java.net.InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    @Test
    void friendsModeLogsInToTheHostsServerWhateverThePageAsks() {
        String pinned = Gateway.toFriendsServer(
                "{\"requestId\":\"1\",\"action\":\"connect\",\"args\":{\"host\":\"beta.xmage.today\",\"port\":17172,\"username\":\"ann\"}}",
                "127.0.0.1", 17171);
        JsonObject args = JsonParser.parseString(pinned).getAsJsonObject().getAsJsonObject("args");
        assertEquals("127.0.0.1", args.get("host").getAsString());
        assertEquals(17171, args.get("port").getAsInt());
        assertEquals("ann", args.get("username").getAsString());

        JsonObject bare = JsonParser.parseString(Gateway.toFriendsServer("{\"action\":\"connect\"}", "127.0.0.1", 17171))
                .getAsJsonObject().getAsJsonObject("args");
        assertEquals("127.0.0.1", bare.get("host").getAsString());

        String chat = "{\"requestId\":\"2\",\"action\":\"sendChatMessage\",\"args\":{\"text\":\"connect\",\"host\":\"x\"}}";
        assertEquals(chat, Gateway.toFriendsServer(chat, "127.0.0.1", 17171));
        assertEquals("\"connect\" not json", Gateway.toFriendsServer("\"connect\" not json", "127.0.0.1", 17171));
    }

    @Test
    void friendsServerNeedsAHostAndAPort() {
        Config config = Config.parse(new String[]{"--friendsServer", "127.0.0.1:17171"});
        assertTrue(config.isFriendsMode());
        assertEquals("127.0.0.1", config.getFriendsServerHost());
        assertEquals(17171, config.getFriendsServerPort());
        assertFalse(Config.parse(new String[0]).isFriendsMode());
        for (String bad : new String[]{"127.0.0.1", "127.0.0.1:", ":17171", "127.0.0.1:port"}) {
            assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[]{"--friendsServer", bad}), bad);
        }
        assertEquals("{\"wsPort\":8789}\n", Main.friendsBody(Config.parse(new String[]{"--friendsServer", "127.0.0.1:17171", "--wsPort", "8789"})));
    }

    @Test
    void pingIsRecognizedWithoutRoutingItToTheSessionQueue() {
        assertEquals("hb-1", Gateway.pingRequestId("{\"requestId\":\"hb-1\",\"action\":\"ping\",\"args\":{}}"));
        assertEquals("", Gateway.pingRequestId("{\"action\":\"ping\"}"));
        assertNull(Gateway.pingRequestId("{\"requestId\":\"1\",\"action\":\"sendChatMessage\",\"args\":{\"text\":\"ping\"}}"));
        assertNull(Gateway.pingRequestId("{\"requestId\":\"2\",\"action\":\"joinGame\",\"args\":{}}"));
        assertNull(Gateway.pingRequestId("\"ping\" not json"));
    }
}
