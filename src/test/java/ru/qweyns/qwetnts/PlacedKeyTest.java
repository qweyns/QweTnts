package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.util.Colors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ключи позиций и оформление строк — без сервера. */
class PlacedKeyTest {

    @Test
    void keyRoundTrip() {
        var key = new PlacedDynamiteManager.Key("world", 1, -2, 300);
        assertEquals("1,-2,300", key.serialize());

        var parsed = PlacedDynamiteManager.Key.parse("world", key.serialize());
        assertEquals(key, parsed);
    }

    @Test
    void keyParseRejectsGarbage() {
        assertNull(PlacedDynamiteManager.Key.parse("world", null));
        assertNull(PlacedDynamiteManager.Key.parse("world", ""));
        assertNull(PlacedDynamiteManager.Key.parse("world", "1,2"));
        assertNull(PlacedDynamiteManager.Key.parse("world", "a,b,c"));
    }

    @Test
    void colorsParsesMiniMessageAndLegacy() {
        assertEquals("", Colors.strip("<red>привет"));
        assertTrue(Colors.toLegacy("<red>привет").startsWith("§"));
        assertTrue(Colors.toLegacy("&cпривет").startsWith("§"));
        assertTrue(Colors.toLegacy("&#ff6b35привет").startsWith("§x"));
    }

    @Test
    void colorsEscapesMini() {
        assertEquals("\\<red\\>", Colors.escapeMini("<red>"));
        assertNull(Colors.escapeMini(null));
    }

    @Test
    void colorsNeverReturnsNull() {
        assertNotNull(Colors.format(null));
        assertEquals("", Colors.strip(null));
        assertEquals("", Colors.toLegacy("  "));
    }
}
