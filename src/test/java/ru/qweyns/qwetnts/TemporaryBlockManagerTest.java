package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.blocks.TemporaryBlockManager.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ключ позиции временного блока. Это единственная часть менеджера, которую
 * можно проверить без запущенного сервера (per-record, никаких Bukkit-вызовов).
 */
class TemporaryBlockManagerTest {

    @Test
    void keyRoundTrips() {
        Key key = new Key("world", -12, 64, 3007);
        Key parsed = Key.parse("world", key.serialize());

        assertNotNull(parsed);
        assertEquals(key, parsed);
        assertEquals("-12,64,3007", key.serialize());
    }

    @Test
    void keyHandlesSpaces() {
        Key parsed = Key.parse("world_nether", " 1 , 2 ,3 ");
        assertNotNull(parsed);
        assertEquals(new Key("world_nether", 1, 2, 3), parsed);
    }

    @Test
    void keyRejectsGarbage() {
        assertNull(Key.parse("world", null));
        assertNull(Key.parse("world", ""));
        assertNull(Key.parse("world", "1,2"));
        assertNull(Key.parse("world", "1,2,3,4"));
        assertNull(Key.parse("world", "a,b,c"));
    }
}
