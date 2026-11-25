package org.mage.test.serverside;

import mage.cards.Card;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.util.CopierObjectInputStream;
import mage.utils.SystemUtil;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A game saved at the start of a turn and read back, the way the server is to bring a game back after
 * a power cut: the read-back game is the same, and it goes on playing from that turn's beginning
 * (Game.playFromTurnStart).
 *
 * @author VictorCerezini
 */
public class GameSaveAndLoadTest extends CardTestPlayerBase {

    private static final Logger logger = Logger.getLogger(GameSaveAndLoadTest.class);

    @Test
    public void test_TheGameGoesOnFromTheSavedTurnStart() {
        // Glorious Anthem: creatures you control get +1/+1
        addCard(Zone.BATTLEFIELD, playerA, "Glorious Anthem");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // 2/2
        // Bloodcrazed Neonate (2/1): attacks each combat if able, +1/+1 counter when it deals combat damage to a player
        addCard(Zone.BATTLEFIELD, playerA, "Bloodcrazed Neonate");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Raise the Alarm"); // two 1/1 Soldier tokens
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        // turn 1: the Bears (3/3) and the Neonate (3/2) hit for 6, the Neonate gets a counter, two Soldiers come
        attack(1, playerA, "Grizzly Bears");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Raise the Alarm");

        // the save: the start of turn 3, where the game copies itself for rollbacks
        setStopAt(3, PhaseStep.UNTAP);
        execute();
        assertLife(playerB, 20 - 6);
        assertCounterCount(playerA, "Bloodcrazed Neonate", CounterType.P1P1, 1);

        Game saved = currentGame;
        UUID topOfLibrary = playerA.getLibrary().getFromTop(saved).getId();
        Game loaded = saveAndLoad(saved);
        Assert.assertNotSame(saved, loaded);
        Assert.assertEquals("the read-back game must be the saved one", describe(saved), describe(loaded));
        useLoadedGame(loaded);

        // turn 3, played on the read-back game: Bolt for 3, the Bears (3/3) and the Neonate (4/3) hit for 7
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        attack(3, playerA, "Grizzly Bears");
        setStopAt(3, PhaseStep.END_TURN);
        executeFromTurnStart();

        assertLife(playerB, 20 - 6 - 3 - 7);
        assertCounterCount(playerA, "Bloodcrazed Neonate", CounterType.P1P1, 2);
        assertPowerToughness(playerA, "Bloodcrazed Neonate", 2 + 2 + 1, 1 + 2 + 1);
        assertPermanentCount(playerA, "Soldier Token", 2);
        assertPowerToughness(playerA, "Soldier Token", 2, 2);
        assertGraveyardCount(playerA, "Raise the Alarm", 1);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        Assert.assertTrue("turn 3's draw must be the card that was on top of the saved library",
                playerA.getHand().contains(topOfLibrary));
        Assert.assertEquals("the saved game must not have moved", 3, saved.getTurnNum());
        Assert.assertEquals("the saved game must not have moved", 20 - 6, saved.getPlayer(playerB.getId()).getLife());
    }

    @Test
    public void test_AnEffectThatOutlastsTheSaveStillApplies() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        // Frost Breath: tap up to two target creatures, they don't untap during their controller's next untap step
        addCard(Zone.HAND, playerA, "Frost Breath");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Memnite");

        // in player B's turn 2, so their next untap step is in turn 4, after the save
        castSpell(2, PhaseStep.UPKEEP, playerA, "Frost Breath", "Grizzly Bears^Memnite");
        setStopAt(3, PhaseStep.UNTAP);
        execute();
        assertTapped("Grizzly Bears", true);
        assertTapped("Memnite", true);

        Game loaded = saveAndLoad(currentGame);
        Assert.assertEquals("the read-back game must be the saved one", describe(currentGame), describe(loaded));
        useLoadedGame(loaded);

        setStopAt(4, PhaseStep.PRECOMBAT_MAIN);
        executeFromTurnStart();

        assertTapped("Grizzly Bears", true);
        assertTapped("Memnite", true);
    }

    @Test
    public void test_ASampleOfAllCardsSurvivesASaveAndLoad() {
        // every 50th card of the card list sorted by class, about 600 different cards: permanents on the
        // battlefield, instants and sorceries in the hand, so the save holds their abilities and effects
        List<CardInfo> sample = sampleOfAllCards(50);
        for (int i = 0; i < sample.size(); i++) {
            CardInfo info = sample.get(i);
            boolean spell = info.getTypes().contains(CardType.INSTANT) || info.getTypes().contains(CardType.SORCERY);
            addCard(spell ? Zone.HAND : Zone.BATTLEFIELD, i % 2 == 0 ? playerA : playerB, info.getName());
        }
        setStopAt(1, PhaseStep.UNTAP);
        execute();

        // every permanent on its own first, so a failure names all the cards that can't be saved, not only the first
        List<String> broken = new ArrayList<>();
        for (Permanent permanent : currentGame.getBattlefield().getAllPermanents()) {
            try {
                serialize(permanent);
            } catch (IOException e) {
                broken.add(permanent.getName() + ": " + e);
            }
        }
        Assert.assertTrue("cards that can't be saved:\n" + String.join("\n", broken), broken.isEmpty());

        Game loaded = saveAndLoad(currentGame);
        Assert.assertEquals("the read-back game must be the saved one", describe(currentGame), describe(loaded));
        useLoadedGame(loaded);

        // and the read-back game plays its first turn
        setStopAt(2, PhaseStep.UNTAP);
        executeFromTurnStart();
        Assert.assertEquals(2, currentGame.getTurnNum());
    }

    /**
     * Writes the game the way a save to disk does (Java serialization, gzip) and reads it back into new objects.
     */
    private Game saveAndLoad(Game game) {
        try {
            byte[] bytes = serialize(game);
            logger.info("saved game: " + bytes.length / 1024 + " KB");
            try (ObjectInputStream in = new CopierObjectInputStream(getClass().getClassLoader(),
                    new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
                return (Game) in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new AssertionError("can't save and read back the game: " + e, e);
        }
    }

    private static byte[] serialize(Object object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(bytes))) {
            out.writeObject(object);
        }
        return bytes.toByteArray();
    }

    /**
     * The test plays on with the read-back game and its own player objects.
     */
    private void useLoadedGame(Game loaded) {
        currentGame = loaded;
        playerA = (TestPlayer) loaded.getPlayer(playerA.getId());
        playerB = (TestPlayer) loaded.getPlayer(playerB.getId());
        activePlayer = loaded.getPlayer(activePlayer.getId());
    }

    /**
     * One card class each, sorted, so the sample is the same on every run. No back faces or split halves:
     * those come with their card.
     */
    private static List<CardInfo> sampleOfAllCards(int every) {
        Map<String, CardInfo> byClass = new TreeMap<>();
        for (CardInfo info : CardRepository.instance.findCards(new CardCriteria().nightCard(false))) {
            // addCard reads "SET-name" (so a card named "AAT-1" reads as set AAT) and "name@alias":
            // names like those can't be added by name
            String name = info.getName();
            boolean readAsSet = !SystemUtil.parseSetAndCardNameCommand(name).get(0).isEmpty();
            if (!info.isSplitCardHalf() && !readAsSet && !name.contains(ALIAS_PREFIX)) {
                byClass.putIfAbsent(info.getClassName(), info);
            }
        }
        List<CardInfo> sample = new ArrayList<>();
        int i = 0;
        for (CardInfo info : byClass.values()) {
            if (i++ % every == 0) {
                sample.add(info);
            }
        }
        return sample;
    }

    /**
     * What there is to see and count in the game, with every object's id, to compare a game with its read-back copy.
     */
    private static String describe(Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append("turn ").append(game.getTurnNum()).append(", active player ").append(game.getActivePlayerId()).append('\n');
        for (Player player : game.getState().getPlayers().values()) {
            sb.append(player.getName()).append(' ').append(player.getId())
                    .append(": life ").append(player.getLife())
                    .append(", counters ").append(counters(player.getCountersAsCopy()))
                    .append('\n');
            sb.append("  library ").append(cards(player.getLibrary().getCards(game))).append('\n');
            sb.append("  hand ").append(cards(sorted(player.getHand().getCards(game)))).append('\n');
            sb.append("  graveyard ").append(cards(sorted(player.getGraveyard().getCards(game)))).append('\n');
        }
        sb.append("exile ").append(cards(sorted(game.getExile().getAllCards(game)))).append('\n');
        List<Permanent> permanents = new ArrayList<>(game.getBattlefield().getAllPermanents());
        permanents.sort(Comparator.comparing(p -> p.getId().toString()));
        for (Permanent p : permanents) {
            sb.append("  ").append(p.getId()).append(' ').append(p.getName())
                    .append(", controller ").append(p.getControllerId())
                    .append(p.isTapped() ? ", tapped" : "")
                    .append(", ").append(p.getPower().getValue()).append('/').append(p.getToughness().getValue())
                    .append(", damage ").append(p.getDamage())
                    .append(", counters ").append(counters(p.getCounters(game)))
                    .append(", attached to ").append(p.getAttachedTo())
                    .append(", abilities ").append(p.getAbilities(game).size())
                    .append('\n');
        }
        sb.append("effects ").append(game.getState().getContinuousEffects().getTotalEffectsCount())
                .append(", triggers ").append(game.getState().getTriggers().size());
        return sb.toString();
    }

    private static List<Card> sorted(Collection<Card> cards) {
        List<Card> list = new ArrayList<>(cards);
        list.sort(Comparator.comparing(c -> c.getId().toString()));
        return list;
    }

    private static String cards(List<Card> cards) {
        return cards.stream().map(c -> c.getId() + " " + c.getName()).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String counters(Counters counters) {
        return new TreeMap<>(counters).values().stream()
                .map(c -> c.getName() + " " + c.getCount())
                .collect(Collectors.joining(", "));
    }
}
