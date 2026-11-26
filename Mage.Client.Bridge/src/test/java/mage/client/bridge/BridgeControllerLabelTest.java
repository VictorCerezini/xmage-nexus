package mage.client.bridge;

import mage.constants.CardType;
import mage.util.ShortIdRegistry;
import mage.util.SubTypes;
import mage.view.CardsView;
import mage.view.GameView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server lists an Aura or Equipment under the player whose permanent it is attached to, so it shows next to
 * that permanent: an opponent's Aura on the model's creature is in the model's battlefield. Who controls it comes
 * from the permanent itself, not from the battlefield it is listed in.
 */
class BridgeControllerLabelTest {

    private static final Unsafe UNSAFE = initUnsafe();

    private final UUID me = UUID.randomUUID();
    private final UUID opponent = UUID.randomUUID();
    private final UUID azula = UUID.randomUUID();
    private final UUID downpour = UUID.randomUUID();
    private final UUID grolnok = UUID.randomUUID();
    private final UUID necromancy = UUID.randomUUID();
    private final UUID oldView = UUID.randomUUID();

    private final BridgeViewLocator locator = new BridgeViewLocator(new ShortIdRegistry(), message -> { });
    private final BridgeCardFormatter formatter = new BridgeCardFormatter(locator);

    @Test
    void aTargetSaysWhoControlsAnAuraNotWhoseCreatureItEnchants() throws Exception {
        GameView view = view();

        assertThat(formatter.describeTarget(downpour, null, view, me)).isEqualTo("Amphibian Downpour (Dann141's)");
        assertThat(formatter.describeTarget(necromancy, null, view, me)).isEqualTo("Necromancy (yours)");
        assertThat(formatter.describeTarget(azula, null, view, me)).isEqualTo("Fire Lord Azula (yours)");
        assertThat(formatter.describeTarget(grolnok, null, view, me)).isEqualTo("Grolnok, the Omnivore (Dann141's)");
        // a view without the permanent's controller: the battlefield it is listed in, as before
        assertThat(formatter.describeTarget(oldView, null, view, me)).isEqualTo("Sol Ring (yours)");

        Map<String, Object> theirAura = new HashMap<>();
        formatter.buildTargetInfo(theirAura, downpour, null, view, me);
        assertThat(theirAura).containsEntry("controller", "Dann141");
        Map<String, Object> myAura = new HashMap<>();
        formatter.buildTargetInfo(myAura, necromancy, null, view, me);
        assertThat(myAura).doesNotContainKey("controller");
    }

    @Test
    void theBoardMarksAnAuraAnotherPlayerControlsWhereItIsListed() throws Exception {
        GameView view = view();
        List<Map<String, Object>> players = new BridgeGameStateBuilder(formatter, locator).buildPlayersArray(view, me);

        assertThat(permanentEntry(players, "ChatGPT 1", "Amphibian Downpour")).containsEntry("controller", "Dann141");
        assertThat(permanentEntry(players, "ChatGPT 1", "Fire Lord Azula")).doesNotContainKey("controller");
        assertThat(permanentEntry(players, "ChatGPT 1", "Sol Ring")).doesNotContainKey("controller");
        assertThat(permanentEntry(players, "Dann141", "Necromancy")).containsEntry("controller", "ChatGPT 1");
        assertThat(permanentEntry(players, "Dann141", "Grolnok, the Omnivore")).doesNotContainKey("controller");
    }

    private GameView view() throws Exception {
        PlayerView mine = player(me, "ChatGPT 1", "p1");
        mine.getBattlefield().put(azula, permanent(azula, "p10", "Fire Lord Azula", "ChatGPT 1", CardType.CREATURE));
        // Dann141's Aura on the model's Azula
        mine.getBattlefield().put(downpour, permanent(downpour, "p11", "Amphibian Downpour", "Dann141", CardType.ENCHANTMENT));
        mine.getBattlefield().put(oldView, permanent(oldView, "p12", "Sol Ring", null, CardType.ARTIFACT));
        PlayerView theirs = player(opponent, "Dann141", "p2");
        theirs.getBattlefield().put(grolnok, permanent(grolnok, "p20", "Grolnok, the Omnivore", "Dann141", CardType.CREATURE));
        // the model's Aura on Dann141's creature
        theirs.getBattlefield().put(necromancy, permanent(necromancy, "p21", "Necromancy", "ChatGPT 1", CardType.ENCHANTMENT));
        return gameView(List.of(mine, theirs));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permanentEntry(List<Map<String, Object>> players, String player, String name) {
        for (Map<String, Object> entry : players) {
            if (player.equals(entry.get("name"))) {
                for (Map<String, Object> permanent : (List<Map<String, Object>>) entry.get("battlefield")) {
                    if (name.equals(permanent.get("name"))) {
                        return permanent;
                    }
                }
            }
        }
        throw new AssertionError(name + " is not in " + player + "'s battlefield");
    }

    private static GameView gameView(List<PlayerView> players) throws Exception {
        GameView view = (GameView) UNSAFE.allocateInstance(GameView.class);
        setField(view, "players", players);
        setField(view, "myHand", new CardsView());
        setField(view, "stack", new CardsView());
        setField(view, "exiles", List.of());
        setField(view, "lookedAt", List.of());
        setField(view, "activePlayerName", "ChatGPT 1");
        setField(view, "priorityPlayerName", "ChatGPT 1");
        setField(view, "turn", 12);
        setField(view, "gameSeq", 1);
        return view;
    }

    private static PlayerView player(UUID playerId, String name, String shortId) throws Exception {
        PlayerView view = (PlayerView) UNSAFE.allocateInstance(PlayerView.class);
        setField(view, "playerId", playerId);
        setField(view, "name", name);
        setField(view, "shortId", shortId);
        setField(view, "life", 40);
        setField(view, "libraryCount", 60);
        setField(view, "handCount", 3);
        setField(view, "isActive", true);
        setField(view, "battlefield", new LinkedHashMap<UUID, Object>());
        setField(view, "graveyard", new CardsView());
        setField(view, "exile", new CardsView());
        setField(view, "commandList", List.of());
        setField(view, "counters", List.of());
        setField(view, "manaPool", null);
        return view;
    }

    private static PermanentView permanent(UUID id, String shortId, String name, String controller, CardType type)
            throws Exception {
        PermanentView view = (PermanentView) UNSAFE.allocateInstance(PermanentView.class);
        setField(view, "id", id);
        setField(view, "shortId", shortId);
        setField(view, "name", name);
        setField(view, "displayName", name);
        setField(view, "manaCostLeftStr", List.of());
        setField(view, "manaCostRightStr", List.of());
        setField(view, "rules", List.of());
        setField(view, "cardTypes", List.of(type));
        setField(view, "subTypes", new SubTypes());
        setField(view, "superTypes", List.of());
        setField(view, "tapped", false);
        setField(view, "nameController", controller);
        return view;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("removal")
    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to access Unsafe", e);
        }
    }
}
