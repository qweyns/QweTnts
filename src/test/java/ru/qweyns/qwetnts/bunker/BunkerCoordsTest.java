package ru.qweyns.qwetnts.bunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Разбор координат углов стены.
 *
 * <p>Угол — это три числа, и писать их можно по-разному: строкой через
 * запятую (компактно) или списком. Проверяем оба пути и то, что мусор не
 * превращается в «стену в нулевых координатах».</p>
 */
class BunkerCoordsTest {

    @Test
    void readsCommaSeparatedString() {
        assertArrayEquals(new int[] {-8, 40, -8}, BunkerLoader.parseCoords("-8, 40, -8"));
        assertArrayEquals(new int[] {0, 0, 0}, BunkerLoader.parseCoords("0,0,0"));
    }

    @Test
    void readsListOfNumbers() {
        assertArrayEquals(new int[] {1, 2, 3}, BunkerLoader.parseCoords(List.of(1, 2, 3)));
    }

    @Test
    void readsListOfStrings() {
        // Bukkit отдаёт значения списка как есть, а YAML может прочитать их
        // строками — такой список тоже должен годиться.
        assertArrayEquals(new int[] {-1, 65, 12}, BunkerLoader.parseCoords(List.of("-1", "65", "12")));
    }

    @Test
    void rejectsGarbage() {
        assertNull(BunkerLoader.parseCoords(null));
        assertNull(BunkerLoader.parseCoords(""), "пустая строка — не координаты");
        assertNull(BunkerLoader.parseCoords("1,2"), "нужны все три числа");
        assertNull(BunkerLoader.parseCoords("a,b,c"), "не числа");
        assertNull(BunkerLoader.parseCoords(List.of()), "пустой список");
        assertNull(BunkerLoader.parseCoords(42), "число вместо трёх координат");
    }
}
