package ru.qweyns.qwetnts.cannon;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

import java.util.UUID;

/**
 * Хранилище состояния пушки прямо в блоке-раздатчике (PDC блок-сущности).
 *
 * <p>Плюс такого подхода: состояние не надо синхронизировать с отдельным
 * файлом на диске, а если блок исчез (сломали, взорвали), метка исчезает
 * вместе с ним — «призраков» не остаётся.</p>
 *
 * <p>Важно: изменения в {@link PersistentDataContainer} применяются только
 * после {@link BlockState#update()}.</p>
 */
public final class CannonBlocks {

    private CannonBlocks() {
    }

    /**
     * Состояние заряженной пушки.
     *
     * @param kind  id динамита или {@link CannonSettings#VANILLA_TNT};
     *              {@code null} — пушка пуста
     * @param count сколько зарядов лежит
     * @param owner кто последний заряжал (нужен QPS, чтобы засчитать атаку)
     */
    public record State(@Nullable String kind, int count, @Nullable UUID owner) {

        private static final State EMPTY = new State(null, 0, null);

        public boolean isEmpty() {
            return kind == null || count <= 0;
        }
    }

    /** Это блок пушки? */
    public static boolean isCannon(@NotNull QweTnts plugin, @Nullable Block block) {
        if (block == null || block.isEmpty()) return false;
        PersistentDataContainer pdc = container(plugin, block);
        return pdc != null && pdc.has(plugin.keys().cannonBlock);
    }

    /** Помечает блок как пушку (вызывается при установке). */
    public static void mark(@NotNull QweTnts plugin, @NotNull Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) return;

        tile.getPersistentDataContainer().set(plugin.keys().cannonBlock,
                PersistentDataType.BYTE, (byte) 1);
        state.update();
    }

    /** Читает заряд пушки. */
    public static @NotNull State read(@NotNull QweTnts plugin, @NotNull Block block) {
        PersistentDataContainer pdc = container(plugin, block);
        if (pdc == null) return State.EMPTY;

        String packed = pdc.get(plugin.keys().cannonAmmo, PersistentDataType.STRING);
        UUID owner = owner(pdc.get(plugin.keys().cannonOwner, PersistentDataType.STRING));
        if (packed == null || packed.isBlank()) return new State(null, 0, owner);

        int sep = packed.lastIndexOf(':');
        if (sep <= 0) return new State(null, 0, owner);

        int count;
        try {
            count = Integer.parseInt(packed.substring(sep + 1).trim());
        } catch (NumberFormatException ex) {
            return new State(null, 0, owner);
        }
        if (count <= 0) return new State(null, 0, owner);

        String kind = packed.substring(0, sep).trim();
        return kind.isEmpty() ? new State(null, 0, owner) : new State(kind, count, owner);
    }

    /** Записывает заряд пушки; {@code kind == null} — опустошить. */
    public static void write(@NotNull QweTnts plugin,
                             @NotNull Block block,
                             @Nullable String kind,
                             int count,
                             @Nullable UUID owner) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) return;

        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(plugin.keys().cannonBlock, PersistentDataType.BYTE, (byte) 1);

        if (kind == null || count <= 0) {
            pdc.remove(plugin.keys().cannonAmmo);
        } else {
            pdc.set(plugin.keys().cannonAmmo, PersistentDataType.STRING, kind + ":" + count);
        }

        if (owner == null) {
            pdc.remove(plugin.keys().cannonOwner);
        } else {
            pdc.set(plugin.keys().cannonOwner, PersistentDataType.STRING, owner.toString());
        }
        state.update();
    }

    /** Снимает с блока все метки пушки. */
    public static void clear(@NotNull QweTnts plugin, @NotNull Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) return;

        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.remove(plugin.keys().cannonBlock);
        pdc.remove(plugin.keys().cannonAmmo);
        pdc.remove(plugin.keys().cannonOwner);
        state.update();
    }

    /**
     * Контейнер PDC блок-сущности.
     *
     * <p>Сначала дешёвая проверка по материалу: {@code getState()} создаёт
     * копию состояния блока, и вызывать его для каждого блока из списка
     * разрушения большого взрыва слишком дорого.</p>
     */
    private static @Nullable PersistentDataContainer container(@NotNull QweTnts plugin,
                                                               @NotNull Block block) {
        Material material = block.getType();
        Material expected = plugin.cannon().item().material();

        if (material != expected) {
            // Материал пушки задаётся в cannon.yml, и его могли поменять
            // после того, как пушки уже поставили: тогда старые блоки не
            // совпадут с expected и пушка «потеряется» (меню не откроется,
            // при поломке предмет не вернётся). Проверяем семейство
            // раздатчиков целиком — это дёшево, а getState() для каждого
            // блока из списка разрушения был бы слишком дорог.
            if (!isDispenserFamily(material)) return null;
        }

        BlockState state;
        try {
            state = block.getState();
        } catch (Exception ex) {
            return null;
        }
        return state instanceof TileState tile ? tile.getPersistentDataContainer() : null;
    }

    /** Раздатчик и выбрасыватель: блоки, на которые PDC пушки рассчитан. */
    private static boolean isDispenserFamily(@NotNull Material material) {
        String name = material.name();
        return name.endsWith("DISPENSER") || name.endsWith("DROPPER");
    }

    private static @Nullable UUID owner(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
