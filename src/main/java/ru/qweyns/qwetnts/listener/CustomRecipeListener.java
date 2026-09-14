package ru.qweyns.qwetnts.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Разрешает рецепты, ингредиенты которых должны соответствовать кастомному
 * динамиту по PDC (например, крафт Динамита Б из Динамита А).
 *
 * <p>Bukkit {@code ShapedRecipe} умеет матчить только Material; для слотов,
 * помеченных в конфиге как {@code tnt:<id>}, регистрируется плейсхолдер TNT,
 * а при подготовке крафта проверяется PDC предмета.</p>
 */
public final class CustomRecipeListener implements Listener {

    private final QweTnts plugin;

    /** Ключ рецепта → (индекс слота в матрице 3x3 → id требуемого типа). */
    private final Map<NamespacedKey, Map<Integer, String>> required = new ConcurrentHashMap<>();

    public CustomRecipeListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /**
     * Зарегистрировать кастомные требования к слотам рецепта.
     *
     * @param key       ключ Bukkit-рецепта
     * @param shape     шейп рецепта (массив строк)
     * @param slotKinds карта «символ → id требуемого кастомного типа»
     */
    public void markRequiresCustom(@Nullable NamespacedKey key,
                                   @Nullable String[] shape,
                                   @Nullable Map<Character, String> slotKinds) {
        if (key == null || shape == null || slotKinds == null || slotKinds.isEmpty()) return;

        Map<Integer, String> slotMap = new HashMap<>();
        for (int row = 0; row < shape.length && row < 3; row++) {
            String line = shape[row];
            if (line == null) continue;
            for (int col = 0; col < line.length() && col < 3; col++) {
                String kind = slotKinds.get(line.charAt(col));
                if (kind != null && !kind.isBlank()) {
                    slotMap.put(row * 3 + col, kind.trim());
                }
            }
        }

        if (!slotMap.isEmpty()) {
            required.put(key, Map.copyOf(slotMap));
        }
    }

    /** Очистить требования (вызывается перед перезагрузкой динамитов). */
    public void clear() {
        required.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(@NotNull PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shaped)) return;

        Map<Integer, String> requirements = required.get(shaped.getKey());
        if (requirements == null || requirements.isEmpty()) return;

        CraftingInventory inventory = event.getInventory();

        try {
            ItemStack[] matrix = inventory.getMatrix();
            if (matrix == null) return;

            for (Map.Entry<Integer, String> entry : requirements.entrySet()) {
                int slot = entry.getKey();
                String kind = entry.getValue();

                if (slot < 0 || slot >= matrix.length) {
                    inventory.setResult(null);
                    return;
                }

                ItemStack stack = matrix[slot];
                if (stack == null || !stack.hasItemMeta()) {
                    inventory.setResult(null);
                    return;
                }

                String found = stack.getItemMeta().getPersistentDataContainer()
                        .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
                if (found == null || !found.equalsIgnoreCase(kind)) {
                    inventory.setResult(null);
                    return;
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Ошибка проверки кастомного рецепта " + shaped.getKey(), ex);
            inventory.setResult(null);
        }
    }
}
