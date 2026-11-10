package org.mage.proxy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The log line for each answer a player sends to the game (see Activity.answerLine). */
class ActivityAnswerTest {

    private static final String GAME = "4e60f95e-2b92-4206-8b1e-00e2133c7ebe";
    private static final String TARGET = "f07e1eb7-04ff-4167-b32c-e324d4bf2e2d";

    private static JsonObject args(String... pairs) {
        JsonObject o = new JsonObject();
        o.addProperty("gameId", GAME);
        for (int i = 0; i < pairs.length; i += 2) o.addProperty(pairs[i], pairs[i + 1]);
        return o;
    }

    @Test
    void aPickedTargetIsLoggedWithItsId() {
        assertEquals("user=ana game=4e60f95e sendPlayerUUID value=" + TARGET,
                Activity.answerLine("ana", "sendPlayerUUID", args("value", TARGET)));
    }

    @Test
    void aPassOrYesNoIsLoggedWithItsValue() {
        JsonObject a = args();
        a.addProperty("value", false);
        assertEquals("user=ana game=4e60f95e sendPlayerBoolean value=false", Activity.answerLine("ana", "sendPlayerBoolean", a));
    }

    @Test
    void aPlayerActionIsLoggedWithItsNameAndASimpleValue() {
        JsonObject a = args("action", "UNDO_LAST_PRIORITY");
        a.addProperty("data", 2);
        assertEquals("user=ana game=4e60f95e sendPlayerAction action=UNDO_LAST_PRIORITY data=2",
                Activity.answerLine("ana", "sendPlayerAction", a));
        assertEquals("user=ana game=4e60f95e sendPlayerAction action=PASS_PRIORITY_AUTO",
                Activity.answerLine("ana", "sendPlayerAction", args("action", "PASS_PRIORITY_AUTO")));
    }

    @Test
    void typedTextIsNeverWrittenOnlyItsLength() {
        String line = Activity.answerLine("ana", "sendPlayerString", args("value", "my secret words"));
        assertEquals("user=ana game=4e60f95e sendPlayerString length=15", line);
        assertFalse(line.contains("secret"));
        // nor free text smuggled in another field
        String action = Activity.answerLine("ana", "sendPlayerAction", args("action", "X", "data", "free text here"));
        assertFalse(action.contains("free text"), action);
        assertEquals("user=ana game=4e60f95e sendPlayerUUID value=?",
                Activity.answerLine("ana", "sendPlayerUUID", args("value", "not an id\nfake line")));
    }

    @Test
    void manaFromThePoolIsLoggedByType() {
        assertEquals("user=ana game=4e60f95e sendPlayerManaType manaType=BLACK",
                Activity.answerLine("ana", "sendPlayerManaType", args("manaType", "BLACK", "playerId", TARGET)));
    }
}
