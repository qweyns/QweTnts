package ru.qweyns.qwetnts.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Проверки, одинаковые для всех механик аддона.
 *
 * <p>Раньше «зона спавна» проверялась тремя копиями одного кода: в поджоге,
 * в установке и во взрыве. Копии разошлись — две считали квадрат
 * (сравнивали модули разностей по осям), одна круг. Игроку это видно так:
 * зайти за угол «защиты» можно, а встать на том же расстоянии по диагонали
 * — нет. Теперь правило одно.</p>
 */
public final class Locations {

    private Locations() {
    }

    /**
     * Точка внутри зоны спавна?
     *
     * <p>Радиус считается по-настоящему — по расстоянию, а не по модулям
     * разностей координат: иначе зона была бы квадратом со стороной
     * {@code 2 * radius}, то есть по диагонали защищала бы почти вдвое
     * дальше заявленного.</p>
     *
     * <p>Правило действует только в обычном мире: в аду и в краю точка спавна
     * не имеет такого значения, а порталы стоят где попало.</p>
     *
     * @param radius радиус в блоках; {@code <= 0} — защиты нет
     */
    public static boolean inSpawnRadius(@Nullable Location location, int radius) {
        if (location == null || radius <= 0) return false;

        World world = location.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) return false;

        Location spawn = world.getSpawnLocation();
        if (spawn.getWorld() == null) return false;

        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    /**
     * Квадрат расстояния между точками одного мира.
     *
     * @return {@link Double#POSITIVE_INFINITY}, если миры разные
     */
    public static double distanceSquared(@NotNull Location first, @NotNull Location second) {
        World a = first.getWorld();
        World b = second.getWorld();
        if (a == null || b == null || !a.equals(b)) return Double.POSITIVE_INFINITY;

        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
