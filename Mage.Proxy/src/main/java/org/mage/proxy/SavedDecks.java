package org.mage.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The decks of the friends who join from a browser, kept on the host (friends mode only).
 *
 * <p>Otherwise they live in the friend's browser at the host's address: gone with another browser
 * or computer, a private window or cleared site data. One file per deck under one folder per player
 * name; the deck is the web client's own JSON, stored as sent, and only its id is read. Whoever
 * logs in with a name gets that name's decks: the same trust the game gives a name on a server
 * without passwords.
 */
final class SavedDecks {

    private static final Logger logger = Logger.getLogger(SavedDecks.class.getName());

    static final int MAX_DECK_BYTES = 256 * 1024;
    static final int MAX_DECKS_PER_PLAYER = 200;
    /** Every player together: made-up names must not fill the host's disk. */
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final Pattern DECK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SUFFIX = ".json";

    private final Path root;
    private final int maxDecksPerPlayer;
    private final long maxTotalBytes;

    SavedDecks(Path root) {
        this(root, MAX_DECKS_PER_PLAYER, MAX_TOTAL_BYTES);
    }

    SavedDecks(Path root, int maxDecksPerPlayer, long maxTotalBytes) {
        this.root = root;
        this.maxDecksPerPlayer = maxDecksPerPlayer;
        this.maxTotalBytes = maxTotalBytes;
    }

    synchronized JsonArray list(String player) throws IOException {
        JsonArray decks = new JsonArray();
        Path folder = folder(player);
        if (!Files.isDirectory(folder)) {
            return decks;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    JsonElement deck = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    if (deck.isJsonObject()) {
                        decks.add(deck);
                    }
                } catch (JsonParseException | IOException ex) {
                    logger.warning("skipping unreadable saved deck " + file + ": " + ex.getMessage());
                }
            }
        }
        return decks;
    }

    synchronized void save(String player, JsonObject deck) throws IOException {
        JsonElement idField = deck.get("id");
        String id = idField != null && idField.isJsonPrimitive() ? idField.getAsString() : "";
        checkId(id);
        byte[] body = deck.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_DECK_BYTES) {
            throw new IllegalArgumentException("deck is too large to save (" + body.length / 1024 + " KB)");
        }
        Path folder = folder(player);
        Path file = folder.resolve(id + SUFFIX);
        long replaced = Files.isRegularFile(file) ? Files.size(file) : -1;
        if (replaced < 0 && deckCount(folder) >= maxDecksPerPlayer) {
            throw new IllegalArgumentException("at most " + maxDecksPerPlayer + " decks can be saved per player");
        }
        if (totalBytes() - Math.max(replaced, 0) + body.length > maxTotalBytes) {
            throw new IllegalArgumentException("the host's deck storage is full");
        }
        Files.createDirectories(folder);
        // a crash mid-write leaves the old deck, never half of the new one
        Path partial = folder.resolve(id + SUFFIX + ".partial");
        Files.write(partial, body);
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    synchronized boolean delete(String player, String id) throws IOException {
        checkId(id);
        return Files.deleteIfExists(folder(player).resolve(id + SUFFIX));
    }

    /**
     * A folder name that is only ever a plain name: lower case, as player names are compared, and
     * every byte outside [a-z0-9_] written as ~hex, so no name reaches outside the root. The prefix
     * keeps names like "con" or "nul" off the ones Windows reserves.
     */
    static String folderName(String player) {
        StringBuilder name = new StringBuilder("player-");
        for (byte b : player.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                name.append((char) c);
            } else {
                name.append('~').append(String.format("%02x", c));
            }
        }
        return name.toString();
    }

    private Path folder(String player) {
        if (player == null || player.trim().isEmpty()) {
            throw new IllegalArgumentException("log in to keep decks on the host");
        }
        return root.resolve(folderName(player.trim()));
    }

    private static void checkId(String id) {
        if (!DECK_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad deck id");
        }
    }

    private static int deckCount(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
            for (Path ignored : files) {
                count++;
            }
        }
        return count;
    }

    private long totalBytes() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
    }
}
