package org.mage.test.serverside;

import mage.cards.decks.Deck;
import mage.constants.PhaseStep;
import mage.constants.TableState;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.TwoPlayerDuelType;
import mage.game.TwoPlayerMatch;
import mage.game.events.TableEvent;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.players.Player;
import mage.players.PlayerType;
import mage.server.MainManagerFactory;
import mage.server.TableController;
import mage.server.User;
import mage.server.game.GameFactory;
import mage.server.managers.ManagerFactory;
import mage.server.recovery.SavedTable;
import mage.server.recovery.TableRecovery;
import mage.server.util.ConfigFactory;
import mage.server.util.ConfigWrapper;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The server's side of bringing a game back after a power cut: the save point it takes at each regular
 * turn start, the save files it keeps (TableRecovery), and a table brought back from one, whose players
 * sit down again by name (TableController).
 *
 * @author VictorCerezini
 */
public class TableRecoveryTest extends CardTestPlayerBase {

    private static final String DUEL = "Two Player Duel";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void test_TheSaveTakenWhileTheGamePlaysGoesOnLikeTheGame() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // 2/2
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        // the server saves where the game tells it a regular turn starts, while the game plays on
        Map<Integer, byte[]> saves = savesAtTurnStarts(currentGame);

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        attack(3, playerA, "Grizzly Bears");
        setStopAt(3, PhaseStep.END_TURN);
        execute();
        assertLife(playerB, 20 - 3 - 2);
        Assert.assertEquals("a save at each turn start", Arrays.asList(1, 2, 3), new ArrayList<>(saves.keySet()));

        // the save from turn 3's start plays that turn again: the Bolt and the attack it hadn't seen yet
        useLoadedGame(load(saves.get(3)));
        Assert.assertEquals(3, currentGame.getTurnNum());
        Assert.assertEquals(20, currentGame.getPlayer(playerB.getId()).getLife());
        setStopAt(3, PhaseStep.END_TURN);
        executeFromTurnStart();
        assertLife(playerB, 20 - 3 - 2);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void test_NoSaveAtTheStartOfAnExtraTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "Time Walk");

        Map<Integer, byte[]> saves = savesAtTurnStarts(currentGame);

        // turn 2 is player A's extra turn, turn 3 player B's
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Time Walk");
        setStopAt(4, PhaseStep.UNTAP);
        execute();
        Assert.assertEquals("no save at the extra turn's start", Arrays.asList(1, 3, 4), new ArrayList<>(saves.keySet()));
        Assert.assertEquals(playerB.getId(), load(saves.get(3)).getActivePlayerId());
    }

    @Test
    public void test_TheSavesAreKeptReadBackAndSetAside() throws Exception {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        setStopAt(2, PhaseStep.UNTAP);
        execute();

        File folder = temporaryFolder.newFolder("recovery");
        TableRecovery recovery = new TableRecovery(folder, getClass().getClassLoader(), "test version");
        UUID tableId = UUID.randomUUID();

        // the first save
        Assert.assertTrue(recovery.save(tableId, savedTable(recovery.getServerVersion(), currentGame)));
        Assert.assertTrue(recovery.waitForWrites(30_000));
        List<TableRecovery.Restorable> restored = recovery.loadAll();
        Assert.assertEquals(1, restored.size());
        Assert.assertEquals(tableId, restored.get(0).getSavedTableId());
        Game game = restored.get(0).getTable().getGame();
        Assert.assertNotSame(currentGame, game);
        Assert.assertEquals(currentGame.getId(), game.getId());
        Assert.assertEquals(2, game.getTurnNum());
        Assert.assertEquals("PlayerA", restored.get(0).getTable().getSeats().get(0).getName());

        // the next save replaces it, and the first one is kept as the previous save
        currentGame.getPlayer(playerB.getId()).setLife(7, currentGame, null);
        Assert.assertTrue(recovery.save(tableId, savedTable(recovery.getServerVersion(), currentGame)));
        Assert.assertTrue(recovery.waitForWrites(30_000));
        Assert.assertEquals(Arrays.asList(tableId + ".previous", tableId + ".table"), fileNames(folder));
        Assert.assertEquals(7, lifeOfB(recovery.loadAll()));

        // a save that doesn't read back: the previous one does
        Files.write(new File(folder, tableId + ".table").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(20, lifeOfB(recovery.loadAll()));

        // another server version doesn't read them, and sets them aside
        TableRecovery updated = new TableRecovery(folder, getClass().getClassLoader(), "next version");
        Assert.assertTrue(updated.loadAll().isEmpty());
        Assert.assertEquals(Arrays.asList(tableId + ".previous.failed", tableId + ".table.failed"), fileNames(folder));
        Assert.assertTrue("set aside, not tried again", recovery.loadAll().isEmpty());

        // a table brought back takes its saves to its new id; the end of its game deletes them
        UUID newTableId = UUID.randomUUID();
        Assert.assertTrue(recovery.save(tableId, savedTable(recovery.getServerVersion(), currentGame)));
        Assert.assertTrue(recovery.waitForWrites(30_000));
        recovery.rename(tableId, newTableId);
        Assert.assertEquals(newTableId, recovery.loadAll().get(0).getSavedTableId());
        recovery.delete(newTableId);
        Assert.assertTrue(recovery.waitForWrites(30_000));
        Assert.assertTrue(recovery.loadAll().isEmpty());
        Assert.assertEquals(Arrays.asList(tableId + ".previous.failed", tableId + ".table.failed"), fileNames(folder));
    }

    @Test
    public void test_ATableComesBackWithItsSeatsScoreAndPlayers() throws Exception {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        setStopAt(3, PhaseStep.UNTAP);
        execute();

        // the real server managers, with the server's own config
        ManagerFactory managers = new MainManagerFactory(new ConfigWrapper(ConfigFactory.loadFromFile("../Mage.Server/config/config.xml")));
        GameFactory.instance.addGameType(DUEL, new TwoPlayerDuelType(), TwoPlayerMatch.class);
        UUID roomId = managers.gamesRoomManager().getMainRoomId();

        // best of three, game 2: player A won the first one. Read back from its save, like on a server start.
        File folder = temporaryFolder.newFolder("recovery");
        TableRecovery recovery = new TableRecovery(folder, getClass().getClassLoader(), "test version");
        Deck deckA = new Deck();
        Deck deckB = new Deck();
        List<SavedTable.SavedSeat> seats = Arrays.asList(
                new SavedTable.SavedSeat(PlayerType.HUMAN, playerA.getId(), "PlayerA", deckA, 1, false, 0),
                new SavedTable.SavedSeat(PlayerType.HUMAN, playerB.getId(), "PlayerB", deckB, 0, false, 0));
        recovery.save(UUID.randomUUID(), new SavedTable(recovery.getServerVersion(), duelOptions(), "PlayerA", seats, 2, 0, currentGame));
        Assert.assertTrue(recovery.waitForWrites(30_000));
        SavedTable saved = recovery.loadAll().get(0).getTable();

        TableController tableController = new TableController(managers, roomId, saved);
        Match match = tableController.getMatch();
        Assert.assertEquals(TableState.DUELING, tableController.getTable().getState());
        Assert.assertSame("the saved game is the match's game", saved.getGame(), match.getGame());
        Assert.assertEquals(tableController.getTable().getId(), saved.getGame().getTableId());
        Assert.assertEquals(2, match.getNumGames());
        Assert.assertEquals(1, match.getPlayer(playerA.getId()).getWins());
        Assert.assertEquals(0, match.getPlayer(playerB.getId()).getWins());
        Assert.assertSame("the saved game's own player sits there", saved.getGame().getPlayer(playerB.getId()),
                tableController.getTable().getSeats()[1].getPlayer());

        // nobody takes a seat that isn't theirs, nor the table
        User someone = managers.userManager().createUser("Someone", "host", null).get();
        Assert.assertFalse(tableController.seatReturningUser(someone));
        Assert.assertFalse(tableController.isOwner(someone.getId()));

        // the players come back by name, the host gets the table back
        User userB = managers.userManager().createUser("PlayerB", "host", null).get();
        Assert.assertTrue(tableController.seatReturningUser(userB));
        Assert.assertFalse("seated once", tableController.seatReturningUser(userB));
        Assert.assertFalse(tableController.isOwner(userB.getId()));
        User userA = managers.userManager().createUser("PlayerA", "host", null).get();
        Assert.assertTrue(tableController.seatReturningUser(userA));
        Assert.assertTrue("the host is back", tableController.isOwner(userA.getId()));
        Assert.assertTrue(userB.isInRunningMatch());

        // it saves again the same way: the next power cut brings it back too
        SavedTable again = tableController.toSavedTable(match.getGame(), "test version");
        Assert.assertEquals("PlayerA", again.getOwnerName());
        Assert.assertEquals(2, again.getStartedGames());
        Assert.assertEquals(1, again.getSeats().get(0).getWins());
        Assert.assertEquals(playerB.getId(), again.getSeats().get(1).getPlayerId());
        Assert.assertSame("the deck read back", saved.getSeats().get(1).getDeck(), again.getSeats().get(1).getDeck());
        Assert.assertNull("another game isn't saved", tableController.toSavedTable(currentGame, "test version"));
    }

    private static MatchOptions duelOptions() {
        MatchOptions options = new MatchOptions("brought back", DUEL, false);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.setDeckType("Constructed - Vintage");
        options.setWinsNeeded(2);
        return options;
    }

    private SavedTable savedTable(String serverVersion, Game game) {
        List<SavedTable.SavedSeat> seats = new ArrayList<>();
        for (Player player : Arrays.asList(game.getPlayer(playerA.getId()), game.getPlayer(playerB.getId()))) {
            seats.add(new SavedTable.SavedSeat(PlayerType.HUMAN, player.getId(), player.getName(), new Deck(), 0, false, 0));
        }
        return new SavedTable(serverVersion, duelOptions(), "PlayerA", seats, 1, 0, game);
    }

    private int lifeOfB(List<TableRecovery.Restorable> restored) {
        Assert.assertEquals(1, restored.size());
        return restored.get(0).getTable().getGame().getPlayer(playerB.getId()).getLife();
    }

    private static List<String> fileNames(File folder) {
        List<String> names = new ArrayList<>(Arrays.asList(folder.list()));
        names.sort(String::compareTo);
        return names;
    }

    /**
     * The game written to bytes each time it says a regular turn starts, as the server does then.
     */
    private static Map<Integer, byte[]> savesAtTurnStarts(Game game) {
        Map<Integer, byte[]> saves = new TreeMap<>();
        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.TURN_STARTED) {
                Assert.assertNull(game.getState().getExtraTurnId());
                saves.put(game.getTurnNum(), serialize(game));
            }
        });
        return saves;
    }

    private static byte[] serialize(Game game) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(game);
        } catch (IOException e) {
            throw new AssertionError("can't save the game: " + e, e);
        }
        return bytes.toByteArray();
    }

    private Game load(byte[] bytes) {
        try (ObjectInputStream in = new mage.util.CopierObjectInputStream(getClass().getClassLoader(), new ByteArrayInputStream(bytes))) {
            return (Game) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new AssertionError("can't read back the game: " + e, e);
        }
    }

    private void useLoadedGame(Game loaded) {
        currentGame = loaded;
        playerA = (TestPlayer) loaded.getPlayer(playerA.getId());
        playerB = (TestPlayer) loaded.getPlayer(playerB.getId());
        activePlayer = loaded.getPlayer(activePlayer.getId());
    }
}
