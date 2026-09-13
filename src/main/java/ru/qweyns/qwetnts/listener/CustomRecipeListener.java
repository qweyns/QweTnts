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
import ru.qweyns.qwetnts.QweTnts;

import java.util.HashMap;
import java.util.Map;

/**
 * Разрешает рецепты, ингредиенты которых должны соответствовать кастомному
 * динамету по PDC (например, крафт Динамита Б из Динамита А).
 *
 * <p>Bukkit {@code ShapedRecipe} умеет матчить только Material; для слотов,
 * помеченных в конфиге как {@code custom-type: <id>}, мы регистрируем
 * плейсхолдер TNT и при подготовке крафта проверяем PDC предмета.</p>
 */
public final class CustomRecipeListener implements Listener {

    private final QweTnts plugin;
    /** Ключ рецепта -> (индекс слота в матрице 3x3, id требуемого типа). */
    private final Map<NamespacedKey, Map<Integer, String>> required = new HashMap<>();

    public CustomRecipeListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    /**
     * Зарегистрировать кастомные требования к слотам рецепта.
     *
     * @param key   ключ Bukkit-рецепта
     * @param shape шейп рецепта (массив строк)
     * @param slotKinds карта символ -> id требуемого кастомного типа динамита
     */
    public void markRequiresCustom(NamespacedKey key, String[] shape,
                                   Map<Character, String> slotKinds) {
        if (key == null || shape == null || slotKinds == null || slotKinds.isEmpty()) return;
        Map<Integer, String> slotMap = new HashMap<>();
        for (int row = 0; row < shape.length && row < 3; row++) {
            for (int col = 0; col < shape[row].length() && col < 3; col++) {
                char ch = shape[row].charAt(col);
                String kind = slotKinds.get(ch);
                if (kind != null) {
                    slotMap.put(row * 3 + col, kind);
                }
            }
        }
        if (!slotMap.isEmpty()) required.put(key, Map.copyOf(slotMap));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe sr)) return;

        Map<Integer, String> req = required.get(sr.getKey());
        if (req == null || req.isEmpty()) return;

        CraftingInventory inv = event.getInventory();
        ItemStack[] matrix = inv.getMatrix();

        for (var entry : req.entrySet()) {
            int slot = entry.getKey();
            String kind = entry.getValue();
            if (slot >= matrix.length) { inv.setResult(null); return; }
            ItemStack stack = matrix[slot];
            if (stack == null || !stack.hasItemMeta()) { inv.setResult(null); return; }
            String found = stack.getItemMeta().getPersistentDataContainer()
                    .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
            if (!kind.equals(found)) {
                inv.setResult(null);
                return;
            }
        }
    }
}
