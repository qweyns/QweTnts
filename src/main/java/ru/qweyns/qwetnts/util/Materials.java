package ru.qweyns.qwetnts.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Хелперы над {@link Material}: семейства блоков, взрывоустойчивость, парсинг. */
public final class Materials {

    /** Взрывоустойчивость обсидиана, плачущего обсидиана и древних обломков. */
    public static final double OBSIDIAN_RESISTANCE = 1200.0;

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

    /**
     * Окончания имён {@link Material} блоков, которые при правом клике открывают
     * свой интерфейс или выполняют собственное действие: двери, люки, калитки,
     * кнопки, кровати, таблички, шалкеры.
     *
     * <p>Сознательно НЕ используем {@code Tag.DOORS} и прочие константы
     * {@code org.bukkit.Tag}: они инициализируются через {@code Bukkit.getTag(...)}
     * и падают вне запущенного сервера (в том числе в модульных тестах).</p>
     */
    private static final List<String> INTERACTABLE_SUFFIXES = List.of(
            "_DOOR",
            "_TRAPDOOR",
            "_FENCE_GATE",
            "_BUTTON",
            "_BED",
            "_SIGN",
            "_SHULKER_BOX"
    );

    /** Наковальни: общего окончания в имени нет. */
    private static final Set<Material> ANVILS = Set.of(
            Material.ANVIL,
            Material.CHIPPED_ANVIL,
            Material.DAMAGED_ANVIL
    );

    /** Верстаки и прочие блоки-интерфейсы, не попадающие в BlockData. */
    private static final Set<Material> INTERACTABLE_BLOCKS = Set.of(
            Material.CRAFTING_TABLE,
            Material.ENCHANTING_TABLE,
            Material.SMITHING_TABLE,
            Material.CARTOGRAPHY_TABLE,
            Material.LOOM,
            Material.STONECUTTER,
            Material.GRINDSTONE,
            Material.LECTERN,
            Material.BELL,
            Material.NOTE_BLOCK,
            Material.JUKEBOX,
            Material.BEACON,
            Material.COMPOSTER,
            Material.LODESTONE,
            Material.RESPAWN_ANCHOR
    );

    private Materials() {
    }

    /** Безопасный парсинг {@link Material} по строке из конфига. */
    public static @Nullable Material parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Material.matchMaterial(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Разбор нескольких материалов (ключи конфига) с пропуском неизвестных. */
    public static @NotNull Set<Material> parseAll(@Nullable Iterable<String> raw) {
        if (raw == null) return Set.of();
        Set<Material> out = new LinkedHashSet<>();
        for (String value : raw) {
            Material material = parse(value);
            if (material != null) out.add(material);
        }
        return Collections.unmodifiableSet(out);
    }

    public static boolean isLiquid(@Nullable Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    /** Пустой ли это блок (воздух любого вида). */
    public static boolean isEmpty(@Nullable Material m) {
        return m == null || m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** Можно ли вообще трогать этот блок взрывом (бедрок, портал, барьер — нет). */
    public static boolean isIndestructible(@Nullable Material m) {
        if (m == null) return true;
        return switch (m) {
            case BEDROCK, BARRIER, END_PORTAL, END_PORTAL_FRAME, NETHER_PORTAL,
                 LIGHT, STRUCTURE_BLOCK, STRUCTURE_VOID, SPAWN_POINT,
                 REINFORCED_DEEPSLATE, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK,
                 REPEATING_COMMAND_BLOCK, JIGSAW, MOVING_PISTON, PETRIFIED_OAK_SLAB -> true;
            default -> false;
        };
    }

    /**
     * Взрывоустойчивость блока (0 для воздуха/жидкостей).
     *
     * <p>Именно это значение vanilla сравнивает с силой взрыва:
     * эффективное сопротивление = {@code (resistance + 0.3) * 0.3}
     * (см. {@link ru.qweyns.qwetnts.dynamite.BlastMath}).</p>
     */
    public static double blastResistance(@Nullable Block block) {
        if (block == null) return 0.0;
        Material material = block.getType();
        if (isEmpty(material)) return 0.0;
        return Math.max(0.0, material.getBlastResistance());
    }

    /**
     * Перехватывает ли блок правый клик сам (сундук, дверь, верстак, кнопка...).
     *
     * <p>{@code Material#isInteractable()} помечен {@code @Deprecated} в Paper,
     * поэтому проверяем по состоянию блока: {@code InventoryHolder}, верстаки,
     * семейства по имени {@code Material} и интерфейсы {@code BlockData}.</p>
     */
    public static boolean isInteractable(@NotNull Block block) {
        if (block.getState() instanceof InventoryHolder) return true;

        Material m = block.getType();
        if (INTERACTABLE_BLOCKS.contains(m) || ANVILS.contains(m)) return true;

        String name = m.name();
        for (String suffix : INTERACTABLE_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }

        BlockData data = block.getBlockData();
        return data instanceof Openable   // двери, люки, калитки
                || data instanceof Powerable  // кнопки, рычаги, плиты
                || data instanceof Lightable; // костры, свечи
    }
}
