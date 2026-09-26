package ru.qweyns.qwetnts.cannon;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Items;

import java.util.logging.Level;

/**
 * Предмет Тнт-пушки: раздатчик с PDC-меткой.
 *
 * <p>Метка — единственное, чем пушка отличается от обычного раздатчика:
 * блоки отличаются ровно так же, поэтому переименованный раздатчик пушкой
 * не станет.</p>
 */
public final class CannonItem {

    /** Значение PDC-метки предмета и блока. */
    private static final String TAG = "cannon";

    private final QweTnts plugin;
    private final CannonSettings settings;
    private final ItemStack prototype;

    public CannonItem(@NotNull QweTnts plugin, @NotNull CannonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.prototype = Items.build(plugin, plugin.keys().cannonItem, TAG,
                settings.item().material(),
                settings.item().displayName(),
                settings.item().lore(),
                settings.item().glow(),
                settings.item().customModelData(),
                settings.item().itemModel(),
                settings.item().unbreakable());
    }

    /** Копия предмета пушки (прототип не отдаём: его могут испортить). */
    public @NotNull ItemStack create() {
        ItemStack copy = prototype.clone();
        copy.setAmount(1);
        return copy;
    }

    /** Это предмет пушки? */
    public boolean isCannon(@Nullable ItemStack stack) {
        return stack != null && Items.hasTag(plugin.keys().cannonItem, stack);
    }

    /** Настройки, по которым собран предмет. */
    public @NotNull CannonSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------
    // Рецепт
    // ------------------------------------------------------------------

    /**
     * Регистрирует крафт пушки, если он включён. Ключ запоминается в реестре,
     * чтобы {@code /qtnt reload} не копил дубликаты рецептов.
     */
    public @Nullable NamespacedKey registerRecipe() {
        var recipe = settings.recipe();
        if (recipe == null || !settings.craftable()) return null;

        NamespacedKey key = new NamespacedKey(plugin, "cannon");
        try {
            ShapedRecipe shaped = new ShapedRecipe(key, create());
            shaped.shape(recipe.shape());
            for (var entry : recipe.ingredients().entrySet()) {
                shaped.setIngredient(entry.getKey(), entry.getValue());
            }
            // Кастомные ингредиенты проверяет CustomRecipeListener.
            for (var entry : recipe.customIngredients().entrySet()) {
                shaped.setIngredient(entry.getKey(), Material.TNT);
            }

            if (plugin.getServer().addRecipe(shaped)) {
                plugin.registry().addRecipeKey(key);
                if (!recipe.customIngredients().isEmpty()) {
                    plugin.customRecipes().markRequiresCustom(key, recipe.shape(),
                            recipe.customIngredients());
                }
                return key;
            }
            return null;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось зарегистрировать рецепт Тнт-пушки", ex);
            return null;
        }
    }
}
