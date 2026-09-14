package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.dynamite.BlastMath;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка модели взрывоустойчивости — той самой, из-за которой обсидиан
 * не берётся обычной ТНТ, но берётся C4 по явному правилу.
 */
class BlastMathTest {

    private static final Random RANDOM = new Random(2024);

    @Test
    void effectiveResistanceFollowsVanillaFormula() {
        // Ванильная формула: (resistance + 0.3) * 0.3
        assertEquals(0.0, BlastMath.effectiveResistance(0.0, 0.3), 1e-9);
        assertEquals(1.89, BlastMath.effectiveResistance(6.0, 0.3), 1e-6);
        assertEquals(360.09, BlastMath.effectiveResistance(1200.0, 0.3), 1e-6);
    }

    @Test
    void stoneBreaksByTntButObsidianDoesNot() {
        double power = 4.0;   // обычная ТНТ

        assertTrue(BlastMath.canBreak(6.0, power, Double.MAX_VALUE, 0.3),
                "булыжник (6) обычная ТНТ берёт");
        assertFalse(BlastMath.canBreak(1200.0, power, Double.MAX_VALUE, 0.3),
                "обсидиан (1200) обычная ТНТ не берёт");
    }

    @Test
    void maxResistanceIsAHardCap() {
        // Даже огромная мощность не пробьёт блок выше потолка: именно поэтому
        // reinforced deepslate и бедрок не трогаем никогда.
        assertFalse(BlastMath.canBreak(3_600_000.0, 10_000.0, 1200.0, 0.3));
        assertTrue(BlastMath.canBreak(1200.0, 500.0, 1200.0, 0.3),
                "явное правило + высокий потолок — обсидиан пробивается");
    }

    @Test
    void zeroOrNegativePowerBreaksNothing() {
        assertFalse(BlastMath.canBreak(1.0, 0.0, Double.MAX_VALUE, 0.3));
        assertFalse(BlastMath.canBreak(1.0, -5.0, Double.MAX_VALUE, 0.3));
    }

    @Test
    void scaleChangesThreshold() {
        // Увеличив масштаб, делаем блок хрупким; уменьшив — почти неуязвимым.
        assertTrue(BlastMath.canBreak(1200.0, 4.0, 1200.0, 0.002));
        assertFalse(BlastMath.canBreak(1200.0, 4.0, 1200.0, 1.0));
    }

    @Test
    void chanceRollsAreBounded() {
        assertFalse(BlastMath.roll(0, RANDOM));
        assertFalse(BlastMath.roll(-50, RANDOM));
        assertTrue(BlastMath.roll(100, RANDOM));
        assertTrue(BlastMath.roll(500, RANDOM));

        int hits = 0;
        for (int i = 0; i < 10_000; i++) {
            if (BlastMath.roll(50, RANDOM)) hits++;
        }
        assertTrue(hits > 4_000 && hits < 6_000, "шанс 50% даёт около половины: " + hits);
    }

    @Test
    void randomizedPowerStaysWithinVanillaSpread() {
        for (int i = 0; i < 1_000; i++) {
            double power = BlastMath.randomizedPower(4.0, RANDOM);
            assertTrue(power >= 4.0 * 0.7 - 1e-9 && power <= 4.0 * 1.3 + 1e-9);
        }
        assertEquals(0.0, BlastMath.randomizedPower(0.0, RANDOM), 1e-9);
    }

    @Test
    void scanRadiusIsCapped() {
        assertEquals(4, BlastMath.scanRadius(4.0, 8));
        assertEquals(8, BlastMath.scanRadius(64.0, 8), "потолок анти-лага режет радиус");
        assertEquals(1, BlastMath.scanRadius(0.1, 8));
    }

    @Test
    void percentIsClamped() {
        assertEquals(0, BlastMath.clampPercent(-10));
        assertEquals(100, BlastMath.clampPercent(150));
        assertEquals(35, BlastMath.clampPercent(35));
    }
}
