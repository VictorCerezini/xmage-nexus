package mage.server.recovery;

import mage.util.CopierObjectInputStream;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The saves that bring tables back after the server stopped (a power cut), one per table in a folder of
 * the server's data: written at the start of each regular turn, read back when the server starts.
 * <p>
 * A save is written to a temporary file that replaces the last one only when it's complete, and the
 * last one is kept as the previous save, so a cut while writing leaves a save to read back. A save that
 * can't be read is set aside (renamed .failed), so the server doesn't try it on every start.
 *
 * @author VictorCerezini
 */
public class TableRecovery {

    private static final Logger logger = Logger.getLogger(TableRecovery.class);

    static final String SAVE = ".table";
    static final String PREVIOUS = ".previous";
    static final String WRITING = ".writing";
    static final String FAILED = ".failed";

    private final File folder;
    private final ClassLoader classLoader;
    private final String serverVersion;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(new XmageThreadFactory("TABLE RECOVERY", true));

    /**
     * @param folder        where the saves are
     * @param classLoader   reads the saved classes back (the server's plugins: players, game types)
     * @param serverVersion a save is read back only by the server version that wrote it
     */
    public TableRecovery(File folder, ClassLoader classLoader, String serverVersion) {
        this.folder = folder;
        this.classLoader = classLoader;
        this.serverVersion = serverVersion;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * Saves a table: written to bytes now, in the calling thread (the game thread, so the game doesn't
     * change meanwhile), and to disk in the background. A save that fails keeps the last one.
     *
     * @return false if the table couldn't be written to bytes
     */
    public boolean save(UUID tableId, SavedTable table) {
        byte[] bytes;
        long start = System.nanoTime();
        try {
            bytes = toBytes(table);
            if (logger.isDebugEnabled()) {
                logger.debug("Table " + tableId + " saved for recovery: " + bytes.length / 1024 + " KB in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms");
            }
        } catch (Exception e) {
            // a card that can't be saved, or a change from another thread while writing: the last save stays
            logger.warn("Table " + tableId + " can't be saved for recovery, its last save stays: " + e);
            return false;
        }
        writer.execute(() -> write(tableId, bytes));
        return true;
    }

    /**
     * Deletes a table's saves, after those it's still writing: its game ended or the table is gone.
     */
    public void delete(UUID tableId) {
        writer.execute(() -> {
            for (String suffix : new String[]{WRITING, SAVE, PREVIOUS}) {
                try {
                    Files.deleteIfExists(path(tableId, suffix));
                } catch (IOException e) {
                    logger.warn("Can't delete the recovery save " + path(tableId, suffix) + ": " + e);
                }
            }
        });
    }

    /**
     * A table brought back gets a new id: its saves go with it.
     */
    public void rename(UUID fromTableId, UUID toTableId) {
        for (String suffix : new String[]{SAVE, PREVIOUS}) {
            Path from = path(fromTableId, suffix);
            if (Files.exists(from)) {
                try {
                    Files.move(from, path(toTableId, suffix), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    logger.warn("Can't rename the recovery save " + from + ": " + e);
                }
            }
        }
    }

    /**
     * Sets a table's saves aside, so they aren't read again on the next start.
     */
    public void setAside(UUID tableId) {
        for (String suffix : new String[]{SAVE, PREVIOUS}) {
            Path from = path(tableId, suffix);
            if (Files.exists(from)) {
                try {
                    Files.move(from, path(tableId, suffix + FAILED), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    logger.warn("Can't set aside the recovery save " + from + ": " + e);
                }
            }
        }
    }

    /**
     * Waits for the saves being written, for tests and a clean stop.
     */
    public boolean waitForWrites(long timeoutMillis) throws InterruptedException {
        try {
            writer.submit(() -> {
            }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /**
     * Every saved table that reads back, with the id it was saved under. A save that doesn't read back
     * (written by another server version, or broken) falls back to the previous one; with neither, the
     * table's saves are set aside.
     */
    public List<Restorable> loadAll() {
        List<Restorable> tables = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files == null) {
            return tables;
        }
        Set<UUID> tableIds = new TreeSet<>();
        for (File file : files) {
            String name = file.getName();
            for (String suffix : new String[]{SAVE, PREVIOUS}) {
                if (name.endsWith(suffix)) {
                    try {
                        tableIds.add(UUID.fromString(name.substring(0, name.length() - suffix.length())));
                    } catch (IllegalArgumentException ignore) {
                        // not a save
                    }
                }
            }
        }
        for (UUID tableId : tableIds) {
            SavedTable table = read(path(tableId, SAVE));
            if (table == null) {
                table = read(path(tableId, PREVIOUS));
            }
            if (table == null) {
                logger.warn("Table " + tableId + " can't be brought back: no save reads back, they're set aside");
                setAside(tableId);
            } else {
                tables.add(new Restorable(tableId, table));
            }
        }
        return tables;
    }

    private SavedTable read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try (ObjectInputStream in = new CopierObjectInputStream(classLoader,
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
            Object version = in.readObject();
            if (!serverVersion.equals(version)) {
                logger.warn("The recovery save " + path + " is from server version " + version
                        + ", this is " + serverVersion + ": it can't be read back");
                return null;
            }
            return (SavedTable) in.readObject();
        } catch (Exception e) {
            logger.warn("The recovery save " + path + " can't be read back: " + e);
            return null;
        }
    }

    /**
     * The server version first, so a save from another version isn't read any further.
     */
    byte[] toBytes(SavedTable table) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(bytes))) {
            out.writeObject(serverVersion);
            out.writeObject(table);
        }
        return bytes.toByteArray();
    }

    private void write(UUID tableId, byte[] bytes) {
        Path writing = path(tableId, WRITING);
        Path save = path(tableId, SAVE);
        try {
            Files.createDirectories(folder.toPath());
            // on the disk before it replaces anything: after a power cut, the save is whole or not there
            try (FileChannel channel = FileChannel.open(writing, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (Files.exists(save)) {
                move(save, path(tableId, PREVIOUS));
            }
            move(writing, save);
        } catch (IOException e) {
            logger.warn("Can't write the recovery save " + save + ": " + e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Path path(UUID tableId, String suffix) {
        return folder.toPath().resolve(tableId + suffix);
    }

    /**
     * A saved table read back, with the id it was saved under.
     */
    public static class Restorable {

        private final UUID savedTableId;
        private final SavedTable table;

        Restorable(UUID savedTableId, SavedTable table) {
            this.savedTableId = savedTableId;
            this.table = table;
        }

        public UUID getSavedTableId() {
            return savedTableId;
        }

        public SavedTable getTable() {
            return table;
        }
    }
}
