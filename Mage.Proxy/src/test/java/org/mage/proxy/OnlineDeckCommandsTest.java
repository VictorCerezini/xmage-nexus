package org.mage.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnlineDeckCommandsTest {

    @Test
    void moxfieldUrlResolvesToDeckIdApiUrl() {
        String url = OnlineDeckCommands.resolveApiUrl("moxfield", "https://moxfield.com/decks/nX0-VuJKOEuFVfDDLnKrTA");
        assertEquals("https://api.moxfield.com/v2/decks/all/nX0-VuJKOEuFVfDDLnKrTA", url);
    }

    @Test
    void moxfieldBareIdIsAccepted() {
        String url = OnlineDeckCommands.resolveApiUrl("moxfield", "nX0-VuJKOEuFVfDDLnKrTA");
        assertEquals("https://api.moxfield.com/v2/decks/all/nX0-VuJKOEuFVfDDLnKrTA", url);
    }

    @Test
    void archidektUrlResolvesToTheFullDeckEndpoint() {
        String url = OnlineDeckCommands.resolveApiUrl("archidekt", "https://archidekt.com/decks/123456/some-deck-name");
        assertEquals("https://archidekt.com/api/decks/123456/", url);
    }

    @Test
    void unknownSourceResolvesToNull() {
        assertNull(OnlineDeckCommands.resolveApiUrl("mtggoldfish", "https://mtggoldfish.com/deck/123"));
    }

    @Test
    void blankIdResolvesToNull() {
        assertNull(OnlineDeckCommands.resolveApiUrl("moxfield", ""));
        assertNull(OnlineDeckCommands.resolveApiUrl("moxfield", null));
    }

    @Test
    void extractIdFallsBackToTrimmedInputWhenPatternDoesNotMatch() {
        assertEquals("abc123", OnlineDeckCommands.extractId("moxfield", "  abc123  "));
    }

    @Test
    void moxfieldFullUrlDoesNotCaptureTheHttpsPrefix() {
        // Regresión: una alternativa "^" de ancho variable en el patron
        // "secuestra" el prefijo "https" de la URL completa antes de llegar
        // al marcador real "moxfield.com/decks/".
        assertEquals("nX0-VuJKOEuFVfDDLnKrTA", OnlineDeckCommands.extractId("moxfield", "https://moxfield.com/decks/nX0-VuJKOEuFVfDDLnKrTA"));
    }

    @Test
    void resolvesEdhrecSlugAndRejectsAnythingElse() {
        assertEquals("https://json.edhrec.com/pages/commanders/atraxa-praetors-voice.json",
                OnlineDeckCommands.resolveApiUrl("edhrec", "atraxa-praetors-voice"));
        assertNull(OnlineDeckCommands.resolveApiUrl("edhrec", "../../etc/passwd"));
        assertNull(OnlineDeckCommands.resolveApiUrl("edhrec", "Has Spaces"));
        assertNull(OnlineDeckCommands.resolveApiUrl("edhrec", ""));
    }

    @Test
    void aMoxfieldDeckOverTwoMegabytesIsReadWhole() throws IOException {
        // a deck with a big maybeboard: 2.8 MB, refused as "response too large" by the old 2 MB cap
        int size = 2_800_000;
        assertEquals(size, OnlineDeckCommands.readLimited(body(size), OnlineDeckCommands.MAX_BODY_BYTES).length());
    }

    @Test
    void anAnswerPastTheCapIsStillRefused() {
        IOException ex = assertThrows(IOException.class,
                () -> OnlineDeckCommands.readLimited(body(OnlineDeckCommands.MAX_BODY_BYTES + 1), OnlineDeckCommands.MAX_BODY_BYTES));
        assertEquals("response too large", ex.getMessage());
    }

    private static InputStream body(int chars) {
        char[] c = new char[chars];
        Arrays.fill(c, 'x');
        return new ByteArrayInputStream(new String(c).getBytes(StandardCharsets.UTF_8));
    }
}
