package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedDecksTest {

    @TempDir
    Path root;

    private static JsonObject deck(String id, String name) {
        return JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"format\":\"Modern\","
                + "\"cards\":[{\"cardName\":\"Lightning Bolt\",\"setCode\":\"M10\",\"cardNumber\":\"146\",\"amount\":4}],"
                + "\"sideboard\":[],\"updatedAt\":1}").getAsJsonObject();
    }

    private static String nameOf(JsonArray decks, int index) {
        return decks.get(index).getAsJsonObject().get("name").getAsString();
    }

    @Test
    void aFriendsDecksComeBackAsTheyWereSaved() throws IOException {
        SavedDecks decks = new SavedDecks(root);
        assertEquals(0, decks.list("alice").size());

        decks.save("alice", deck("d1", "Burn"));
        decks.save("alice", deck("d1", "Burn v2"));
        decks.save("alice", deck("d2", "Humans"));

        JsonArray saved = decks.list("alice");
        assertEquals(2, saved.size());
        JsonObject first = saved.get(0).getAsJsonObject().get("id").getAsString().equals("d1")
                ? saved.get(0).getAsJsonObject() : saved.get(1).getAsJsonObject();
        assertEquals("Burn v2", first.get("name").getAsString());
        assertEquals(deck("d1", "Burn v2"), first, "the web client's own fields are kept as sent");

        assertTrue(decks.delete("alice", "d1"));
        assertFalse(decks.delete("alice", "d1"));
        assertEquals("Humans", nameOf(decks.list("alice"), 0));
    }

    @Test
    void eachNameHasItsOwnDecksWhateverItsCase() throws IOException {
        SavedDecks decks = new SavedDecks(root);
        decks.save("Alice", deck("d1", "Burn"));
        assertEquals("Burn", nameOf(decks.list("alice"), 0));
        assertEquals(0, decks.list("bob").size());
        assertFalse(decks.delete("bob", "d1"));
        assertEquals(1, decks.list("ALICE").size());
    }

    @Test
    void noNameReachesOutsideTheFolder() {
        for (String name : new String[]{"..", "../../etc", "a/b", "a\\b", "c:", "con", "nul", "Zoë 2", "~7e"}) {
            String folder = SavedDecks.folderName(name);
            assertTrue(folder.matches("player-[a-z0-9_~]+"), name + " -> " + folder);
            assertEquals(root.resolve(folder).getParent(), root, name);
        }
        assertFalse(SavedDecks.folderName("~7e").equals(SavedDecks.folderName("~")), "the escape is escaped");
    }

    @Test
    void refusesWhatItCannotStoreSafely() {
        SavedDecks decks = new SavedDecks(root);
        assertThrows(IllegalArgumentException.class, () -> decks.save("alice", deck("../escape", "x")));
        assertThrows(IllegalArgumentException.class, () -> decks.save("alice", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> decks.delete("alice", "..\\d1"));
        assertThrows(IllegalArgumentException.class, () -> decks.list(null), "not logged in");
        assertThrows(IllegalArgumentException.class, () -> decks.save(" ", deck("d1", "x")));
        JsonObject huge = deck("big", "x");
        huge.addProperty("notes", new String(new char[SavedDecks.MAX_DECK_BYTES]).replace('\0', 'a'));
        assertThrows(IllegalArgumentException.class, () -> decks.save("alice", huge));
    }

    @Test
    void madeUpNamesCannotFillTheDisk() throws IOException {
        SavedDecks decks = new SavedDecks(root, 2, 1000);
        decks.save("alice", deck("d1", "a"));
        decks.save("alice", deck("d2", "b"));
        assertThrows(IllegalArgumentException.class, () -> decks.save("alice", deck("d3", "c")), "per player");
        decks.save("alice", deck("d2", "b again"));

        long used;
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            used = files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
        SavedDecks full = new SavedDecks(root, 2, used + 10);
        assertThrows(IllegalArgumentException.class, () -> full.save("mallory", deck("d1", "a")), "everyone together");
    }

    @Test
    void anUnreadableFileIsSkippedNotFatal() throws IOException {
        SavedDecks decks = new SavedDecks(root);
        decks.save("alice", deck("d1", "Burn"));
        Files.write(root.resolve(SavedDecks.folderName("alice")).resolve("broken.json"),
                "{not json".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, decks.list("alice").size());
    }

    @Test
    void onlyTheFriendsProxyKeepsDecks() throws Exception {
        Config host = Config.parse(new String[]{"--wsPort", "0"});
        Config friends = Config.parse(new String[]{"--wsPort", "0", "--friendsServer", "127.0.0.1:17171"});
        assertNull(new Gateway(host).savedDecks());
        assertNotNull(new Gateway(friends).savedDecks());
    }
}
