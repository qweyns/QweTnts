package ru.qweyns.qwetnts.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

import java.util.ArrayList;
import java.util.List;

/**
 * Сборка предметов плагина: имя, лор, модель и PDC-метка.
 *
 * <p>Один и тот же формат описания предмета используется и у динамитов, и у
 * Тнт-пушки, поэтому сборка вынесена сюда: метка передаётся параметром, а не
 * зашита в код.</p>
 */
public final class Items {

    private Items() {
    }

    /**
     * Собирает предмет с именем, лором, моделью и PDC-меткой.
     *
     * @param tagKey   ключ PDC, которым помечается предмет
     * @param tagValue значение метки (id динамита или {@code cannon})
     */
    @SuppressWarnings("java:S107")
    public static @NotNull ItemStack build(@NotNull QweTnts plugin,
                                           @NotNull NamespacedKey tagKey,
                                           @NotNull String tagValue,
                                           @NotNull Material material,
                                           @Nullable String displayNameRaw,
                                           @Nullable List<String> loreRaw,
                                           boolean glow,
                                           int customModelData,
                                           @Nullable String itemModel,
                                           boolean unbreakable) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        Component name = Colors.formatItem(displayNameRaw);
        if (!name.equals(Component.empty())) {
            meta.displayName(name);
        }

        if (loreRaw != null && !loreRaw.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreRaw.size());
            for (String line : loreRaw) {
                if (line != null) lore.add(Colors.formatItem(line));
            }
            meta.lore(lore);
        }

        if (glow) {
            // Свечение без фейкового зачарования (Paper 1.20.5+).
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }
        if (itemModel != null && !itemModel.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(itemModel.trim());
            if (key != null) meta.setItemModel(key);
        }
        if (unbreakable) {
            meta.setUnbreakable(true);
        }

        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tagValue);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Значение PDC-метки или {@code null}, если метки нет. */
    public static @Nullable String tag(@NotNull NamespacedKey key, @Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** Есть ли на предмете такая PDC-метка. */
    public static boolean hasTag(@NotNull NamespacedKey key, @Nullable ItemStack stack) {
        return tag(key, stack) != null;
    }
}
