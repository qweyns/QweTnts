package ru.qweyns.qwetnts.dynamite;

import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Чистая математика разрушения блоков взрывом — без Bukkit, чтобы логику
 * можно было покрыть модульными тестами.
 *
 * <h2>Модель</h2>
 * <p>Vanilla считает сопротивление взрыву так (класс {@code Explosion}):</p>
 * <pre>
 *     эффективное сопротивление = (resistance + 0.3) * 0.3
 *     луч «тратит» мощность, пока она не станет меньше сопротивления
 * </pre>
 * <p>Отсюда известные факты: у обсидиана resistance = 1200 → эффективное
 * 360, поэтому обычная ТНТ (power 4) его не берёт, а «укреплённый глубинный
 * сланец» (3600000) не берёт вообще никто.</p>
 *
 * <p>Плагин использует ту же модель, но с настраиваемыми параметрами:</p>
 * <ol>
 *   <li>блок из «чёрного списка» ({@code bedrock}, командные блоки и т.п.)
 *       не разрушается никогда;</li>
 *   <li>для материала есть явное правило ({@code breaking.blocks}) — решение
 *       принимает только шанс из конфига, сопротивление не важно: именно так
 *       C4 и Разрывная волна ломают обсидиан;</li>
 *   <li>иначе работает порог {@code max-resistance}: блоки с сопротивлением
 *       выше него не трогаем вообще;</li>
 *   <li>иначе сравниваем эффективное сопротивление с мощностью заряда.</li>
 * </ol>
 */
public final class BlastMath {

    /** Множитель из ванильного {@code Explosion}: {@code (r + 0.3) * 0.3}. */
    public static final double VANILLA_SCALE = 0.3;

    private BlastMath() {
    }

    /** Эффективное сопротивление блока по ванильной формуле. */
    public static double effectiveResistance(double resistance, double scale) {
        if (resistance <= 0.0) return 0.0;
        double safeScale = scale <= 0.0 ? VANILLA_SCALE : scale;
        return (resistance + 0.3) * safeScale;
    }

    /**
     * Пробивает ли заряд такой мощности блок с таким сопротивлением.
     *
     * @param resistance сопротивление блока ({@code Material#getBlastResistance()})
     * @param power      мощность взрыва (yield)
     * @param maxResistance жёсткий потолок сопротивления (больше — никогда не ломаем)
     * @param scale      множитель формулы (0.3 — как в vanilla)
     */
    public static boolean canBreak(double resistance, double power, double maxResistance, double scale) {
        if (resistance < 0.0 || power <= 0.0) return false;
        if (resistance > maxResistance) return false;
        return effectiveResistance(resistance, scale) <= power;
    }

    /** Сработал ли шанс в процентах (0 — никогда, 100 — всегда). */
    public static boolean roll(int chancePercent, @NotNull RandomGenerator random) {
        int chance = clampPercent(chancePercent);
        if (chance <= 0) return false;
        if (chance >= 100) return true;
        return random.nextInt(100) < chance;
    }

    /**
     * Мощность с ванильным разбросом: реальный взрыв всегда «дышит»
     * в пределах ±30 %, иначе подсчёт был бы детерминированным и предсказуемым.
     */
    public static double randomizedPower(double power, @NotNull RandomGenerator random) {
        if (power <= 0.0) return 0.0;
        return power * (0.7 + random.nextDouble() * 0.6);
    }

    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Радиус скана блоков вокруг эпицентра: не больше потолка из антилага. */
    public static int scanRadius(double power, int maxRadius) {
        int radius = (int) Math.ceil(power);
        return Math.max(1, Math.min(radius, Math.max(1, maxRadius)));
    }

    /** Общий генератор случайных чисел для игровых событий. */
    public static @NotNull RandomGenerator random() {
        return RandomHolder.INSTANCE;
    }

    private static final class RandomHolder {
        private static final RandomGenerator INSTANCE = new Random();
    }
}
