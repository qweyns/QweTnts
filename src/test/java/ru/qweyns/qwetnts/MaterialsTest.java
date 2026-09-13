package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.util.Materials;

import static org.junit.jupiter.api.Assertions.*;

class MaterialsTest {

    @Test
    void parseDoesNotThrow() {
        // Проверяем, что метод не бросает и возвращает null/не-null по известным именам.
        assertNotNull(Materials.parse("obsidian"));
        assertNotNull(Materials.parse("crying_obsidian"));
        assertNotNull(Materials.parse("tnt"));
        assertNull(Materials.parse(null));
        assertNull(Materials.parse(""));
        assertNull(Materials.parse("   "));
        assertNull(Materials.parse("not_a_real_material_xyz_12345"));
    }

    @Test
    void isLiquid() {
        assertTrue(Materials.isLiquid(Materials.parse("water")));
        assertTrue(Materials.isLiquid(Materials.parse("lava")));
        assertFalse(Materials.isLiquid(Materials.parse("stone")));
    }
}
