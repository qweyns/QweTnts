package ru.qweyns.qwetnts.hologram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подстановка в строки голограммы.
 *
 * <p>Проверять это тестом, а не глазами на сервере, стоит потому, что
 * увидеть голограмму можно только зайдя в игру и поджёгши динамит: цена
 * опечатки в имени плейсхолдера — незамеченная мелочь в интерфейсе.</p>
 */
class HologramTextTest {

    @Test
    void substitutesEveryPlaceholder() {
        List<String> out = HologramText.render(
                List.of("<#FDE68A>⚡ <#F2EFFA>%seconds% с", "%name% — %player% (%ticks%)"),
                3, 55, "Динамит B", "Steve");

        assertEquals(2, out.size());
        assertFalse(out.get(0).contains("%seconds%"));
        assertTrue(out.get(0).endsWith("3 с"));
        assertEquals("Динамит B — Steve (55)", out.get(1));
    }

    @Test
    void unknownPlayerBecomesDash() {
        // Источник заряда не всегда известен: снаряд пушки, диспенсер.
        assertEquals("—", HologramText.render(List.of("%player%"), 1, 1, "x", null).get(0));
        assertEquals("—", HologramText.render(List.of("%player%"), 1, 1, "x", "   ").get(0));
    }

    @Test
    void negativeValuesAreClampedToZero() {
        // Фитиль мог уйти в минус между взрывом и тиком обновления.
        List<String> out = HologramText.render(List.of("%seconds%/%ticks%"), -5, -1, "x", "y");
        assertEquals("0/0", out.get(0));
    }

    @Test
    void emptyLinesStayEmpty() {
        assertTrue(HologramText.render(List.of(), 1, 1, "x", "y").isEmpty());
    }
}
