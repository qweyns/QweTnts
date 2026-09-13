package ru.qweyns.qwetnts.dynamite;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Реестр загруженных типов динамита (по образцу RegionTypeRegistry из QPS).
 */
public final class DynamiteRegistry {

    private final Map<String, DynamiteType> byId = new LinkedHashMap<>();

    /** Ключи зарегистрированных Bukkit-рецептов — нужны для корректной
     *  выгрузки при перезагрузке, чтобы не копились дубликаты. */
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public void register(DynamiteType type) {
        byId.put(idKey(type.id()), type);
    }

    public void addRecipeKey(NamespacedKey key) {
        recipeKeys.add(key);
    }

    public @Nullable DynamiteType byId(String id) {
        if (id == null) return null;
        return byId.get(idKey(id));
    }

    /** Ищет тип по PDC-метке на предмете (используется при активации). */
    public @Nullable DynamiteType byItem(NamespacedKey kindKey, @Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        String kind = stack.getItemMeta().getPersistentDataContainer()
                .get(kindKey, PersistentDataType.STRING);
        return byId(kind);
    }

    /** Ищет тип по строковому идентификатору взрыва (explosion_type в конфиге). */
    public @Nullable DynamiteType byExplosionType(String explosionType) {
        if (explosionType == null) return null;
        String key = explosionType.toLowerCase(Locale.ROOT);
        for (DynamiteType t : byId.values()) {
            if (t.explosionType().toLowerCase(Locale.ROOT).equals(key)) {
                return t;
            }
        }
        return null;
    }

    public Collection<DynamiteType> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** Снимает регистрацию типов и удаляет ранее добавленные Bukkit-рецепты. */
    public void clear(org.bukkit.Server server) {
        for (NamespacedKey key : recipeKeys) {
            server.removeRecipe(key);
        }
        recipeKeys.clear();
        byId.clear();
    }

    public void clear() {
        // Нет ссылки на Server — рецепты не вычистим; используй clear(Server) при перезагрузке.
        byId.clear();
    }

    private static String idKey(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
