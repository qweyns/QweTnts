package ru.qweyns.qwetnts.cannon;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.util.Colors;

/**
 * Меню пушки: 3×3, по краям — декоративные панели, которые нельзя взять,
 * сдвинуть или заменить. В центральный слот кладётся динамит.
 *
 * <p>Это собственное окно плагина, а не inventories самого раздатчика:
 * так рамку точно никто не вытащит, а содержимое не уедет по воронке
 * и не достанется грабителю через hopper.</p>
 */
public final class CannonMenu {

    /** Размер окна: 3×3, как у раздатчика. */
    public static final int SIZE = 9;
    /** Центральный слот — единственный, куда можно что-то положить. */
    public static final int AMMO_SLOT = 4;

    private final CannonSettings settings;

    public CannonMenu(@NotNull CannonSettings settings) {
        this.settings = settings;
    }

    /**
     * Собирает окно пушки.
     *
     * @param location где стоит пушка (нужно, чтобы сохранить заряд при закрытии)
     * @param ammo     чем пушка заряжена прямо сейчас
     */
    public @NotNull Inventory create(@NotNull Location location, @Nullable ItemStack ammo) {
        Inventory inventory = Bukkit.createInventory(new CannonHolder(location), SIZE, title());

        for (int slot = 0; slot < SIZE; slot++) {
            if (slot != AMMO_SLOT) {
                // Свежий предмет на каждый слот: общий экземпляр потом
                // невозможно правильно пометить как «не трогать».
                inventory.setItem(slot, border());
            }
        }
        if (ammo != null && !ammo.getType().isAir()) {
            inventory.setItem(AMMO_SLOT, ammo);
        }
        return inventory;
    }

    /** Заголовок окна. */
    public @NotNull Component title() {
        return Colors.format(settings.menu().title());
    }

    /** Декоративная панель рамки. */
    public @NotNull ItemStack border() {
        ItemStack pane = new ItemStack(settings.menu().border());
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            Component name = Colors.formatItem(settings.menu().borderName());
            meta.displayName(name.equals(Component.empty()) ? Component.text(" ") : name);
            pane.setItemMeta(meta);
        }
        return pane;
    }

    /** Рамка ли это (слот, который трогать нельзя)? */
    public static boolean isBorder(int rawSlot) {
        return rawSlot >= 0 && rawSlot < SIZE && rawSlot != AMMO_SLOT;
    }

    /** Пустая ли ячейка (для пустых слотов {@code getItem} возвращает {@code null}). */
    public static boolean isAir(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    /**
     * Держатель окна: хранит, где стоит пушка, чтобы при закрытии сохранить
     * заряд именно в неё.
     */
    public record CannonHolder(@NotNull Location location) implements InventoryHolder {

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
