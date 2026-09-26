package ru.qweyns.qwetnts.component;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Реестр компонентов крафта (по образцу {@code DynamiteRegistry}).
 *
 * <p>Компоненты загружаются <b>до</b> динамитов: рецепт Динамита Б2 требует
 * Взрывчатое вещество, и его материал нужен как плейсхолдер при регистрации
 * рецепта.</p>
 */
public final class ComponentRegistry {

    private final Map<String, ComponentType> byId = new LinkedHashMap<>();
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public void register(@NotNull ComponentType type) {
        byId.put(idKey(type.id()), type);
    }

    public void addRecipeKey(@NotNull NamespacedKey key) {
        recipeKeys.add(key);
    }

    public @Nullable ComponentType byId(@Nullable String id) {
        if (id == null) return null;
        return byId.get(idKey(id));
    }

    /**
     * Компонент по PDC-метке предмета.
     *
     * <p>Отличает «наше» Взрывчатое вещество от обычного пороха с тем же
     * материалом — именно поэтому рецепты и требуют метку, а не материал.</p>
     */
    public @Nullable ComponentType byItem(@NotNull QweTnts plugin, @Nullable ItemStack stack) {
        if (stack == null) return null;
        String id = Items.tag(plugin.keys().component, stack);
        return byId(id);
    }

    /** Материал компонента — плейсхолдер для рецептов, где он ингредиент. */
    public @NotNull Material materialOf(@Nullable String id, @NotNull Material fallback) {
        ComponentType type = byId(id);
        return type == null ? fallback : type.item().material();
    }

    public @NotNull Collection<ComponentType> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** Снимает регистрацию компонентов и убирает их Bukkit-рецепты. */
    public void clear(@NotNull Server server) {
        for (NamespacedKey key : recipeKeys) {
            server.removeRecipe(key);
        }
        recipeKeys.clear();
        byId.clear();
    }

    private static String idKey(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
