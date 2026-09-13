package ru.qweyns.qwetnts.util;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/** Хелперы для {@link Material} и семейств блоков. */
public final class Materials {

    /**
     * Блоки «семейства обсидиана», на которые распространяется рейд-блок:
     * обычный обсидиан, плачущий обсидиан и древние обломки (ANCIENT_DEBRIS —
     * именно он используется на HW; REINFORCED_DEEPSLATE оставлен как
     * резерв на случай, если администратор назначит его ядром уникального привата).
     */
    public static final Set<Material> RAID_BLOCK_FAMILY = Set.of(
            Material.OBSIDIAN,
            Material.CRYING_OBSIDIAN,
            Material.ANCIENT_DEBRIS,
            Material.REINFORCED_DEEPSLATE
    );

    private Materials() {
    }

    /** Безопасный парсинг Material по строке из конфига. */
    public static @Nullable Material parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Material.matchMaterial(key);
    }

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    /** Пустой ли это блок (воздух любого вида). */
    public static boolean isEmpty(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }
}
