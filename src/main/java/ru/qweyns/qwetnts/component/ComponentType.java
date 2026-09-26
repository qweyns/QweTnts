package ru.qweyns.qwetnts.component;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Items;

import java.util.Map;
import java.util.logging.Level;

/**
 * Компонент крафта: предмет, который нужен как ингредиент в рецептах, но сам
 * взрывчаткой не является.
 *
 * <p>Первый и главный пример — <b>Взрывчатое вещество</b> из HolyWorld Lite:
 * 9 пороха собираются в одну «единицу вещества», а вещество можно разложить
 * обратно в 9 пороха. Из него и из Динамита A собирается Динамит Б2.</p>
 *
 * <h2>Чем отличается от динамита</h2>
 * <p>У компонента нет ни взрыва, ни правил ломания, ни поджога: это просто
 * предмет с PDC-меткой {@code qwetnts:component}, который другие рецепты
 * требуют по этой метке (точно так же, как рецепт Динамита B требует
 * именно Динамит A).</p>
 *
 * @param reverseMaterial во что разлагается компонент (обратный крафт)
 * @param reverseAmount   сколько штук получается при разложении
 */
public record ComponentType(@NotNull String id,
                            @NotNull DynamiteType.ItemSpec item,
                            @Nullable DynamiteType.Recipe recipe,
                            @NotNull Material reverseMaterial,
                            int reverseAmount) {

    /** Префикс требования в рецепте: {@code component:<id>}. */
    public static final String PREFIX = "component:";

    /** Новый предмет компонента с PDC-меткой. Каждый вызов — новый экземпляр. */
    public @NotNull ItemStack create(@NotNull QweTnts plugin) {
        return Items.build(plugin, plugin.keys().component, id,
                item.material(), item.displayName(), item.lore(),
                item.glow(), item.customModelData(), item.itemModel(), item.unbreakable());
    }

    /** @return {@code true}, если можно разложить обратно (например, в порох). */
    public boolean reversible() {
        return reverseAmount > 0 && reverseMaterial != Material.AIR;
    }

    /**
     * Регистрирует Bukkit-рецепты компонента: прямой крафт и, если задан,
     * обратный (бесформенный).
     *
     * <p>Обратный крафт требует <b>точно такой же</b> предмет
     * ({@link RecipeChoice.ExactChoice}), иначе обычный порох или порох с
     * чужой меткой превращался бы в вещество по второму кругу.</p>
     */
    public void registerRecipes(@NotNull QweTnts plugin) {
        if (recipe != null) {
            registerForward(plugin);
        }
        if (reversible()) {
            registerReverse(plugin);
        }
    }

    private void registerForward(@NotNull QweTnts plugin) {
        try {
            NamespacedKey key = new NamespacedKey(plugin, "component_" + id);
            ShapedRecipe shaped = new ShapedRecipe(key, create(plugin));
            shaped.shape(recipe.shape());

            for (Map.Entry<Character, Material> entry : recipe.ingredients().entrySet()) {
                shaped.setIngredient(entry.getKey(), entry.getValue());
            }
            // Компонент в компоненте — редкость, но и её поддерживаем:
            // плейсхолдер берём по материалу нужного компонента.
            for (Map.Entry<Character, String> entry : recipe.customIngredients().entrySet()) {
                shaped.setIngredient(entry.getKey(),
                        plugin.components().materialOf(stripPrefix(entry.getValue()), Material.TNT));
            }

            if (plugin.getServer().addRecipe(shaped)) {
                plugin.components().addRecipeKey(key);
                if (!recipe.customIngredients().isEmpty()) {
                    plugin.customRecipes().markRequiresCustom(key, recipe.shape(),
                            recipe.customIngredients());
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось зарегистрировать рецепт компонента " + id, ex);
        }
    }

    private void registerReverse(@NotNull QweTnts plugin) {
        try {
            NamespacedKey key = new NamespacedKey(plugin, "component_" + id + "_reverse");
            ShapelessRecipe shapeless = new ShapelessRecipe(key,
                    new ItemStack(reverseMaterial, reverseAmount));
            shapeless.addIngredient(new RecipeChoice.ExactChoice(create(plugin)));

            if (plugin.getServer().addRecipe(shapeless)) {
                plugin.components().addRecipeKey(key);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось зарегистрировать обратный рецепт компонента " + id, ex);
        }
    }

    /** Убирает префикс {@code component:} из требования рецепта. */
    public static @NotNull String stripPrefix(@NotNull String requirement) {
        return requirement.startsWith(PREFIX) ? requirement.substring(PREFIX.length()) : requirement;
    }
}
