package ru.qweyns.qwetnts.component;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Materials;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Читает {@code components.yml} из корня папки плагина.
 *
 * <p>Файл один на все компоненты: держать для каждого предмета, у которого
 * нет ни взрыва, ни настроек, отдельный файл в {@code dynamites/} было бы
 * неоправданно.</p>
 */
public final class ComponentLoader {

    private static final Material DEFAULT_MATERIAL = Material.GUNPOWDER;

    private ComponentLoader() {
    }

    /**
     * @return список компонентов; битый файл или пустая секция — не ошибка,
     *         вернётся пустой список с предупреждением в лог
     */
    public static @NotNull List<ComponentType> load(@NotNull QweTnts plugin, @NotNull File file) {
        List<ComponentType> out = new ArrayList<>();
        if (!file.isFile()) return out;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось прочитать " + file.getName() + ": " + ex.getMessage(), ex);
            return out;
        }

        ConfigurationSection root = yaml.getConfigurationSection("components");
        if (root == null) {
            plugin.getLogger().warning(file.getName()
                    + ": нет секции components — компоненты крафта не загружены.");
            return out;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;

            try {
                out.add(parse(plugin, id, section));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Не удалось разобрать компонент " + id, ex);
            }
        }
        return out;
    }

    private static @NotNull ComponentType parse(@NotNull QweTnts plugin,
                                                @NotNull String id,
                                                @NotNull ConfigurationSection section) {
        Material material = Materials.parse(section.getString("material"));
        if (material == null) {
            material = DEFAULT_MATERIAL;
            plugin.getLogger().warning("[" + id + "] неизвестный материал компонента, "
                    + "использую " + DEFAULT_MATERIAL);
        }

        DynamiteType.ItemSpec item = new DynamiteType.ItemSpec(
                material,
                section.getString("display_name", id),
                section.getStringList("lore"),
                section.getBoolean("glow", true),
                Math.max(0, section.getInt("custom-model-data", 0)),
                blankToNull(section.getString("item-model")),
                section.getBoolean("unbreakable", false));

        DynamiteType.Recipe recipe = null;
        if (section.getBoolean("craftable", true)) {
            recipe = parseRecipe(plugin, id, section);
        }

        ConfigurationSection reverse = section.getConfigurationSection("reverse");
        Material reverseMaterial = Material.AIR;
        int reverseAmount = 0;
        if (reverse != null && reverse.getBoolean("enabled", true)) {
            Material parsed = Materials.parse(reverse.getString("result"));
            if (parsed != null) {
                reverseMaterial = parsed;
                reverseAmount = Math.max(0, Math.min(64, reverse.getInt("amount", 1)));
            } else {
                plugin.getLogger().warning("[" + id + "] неизвестный материал "
                        + "в reverse.result — обратный крафт отключён.");
            }
        }

        return new ComponentType(id, item, recipe, reverseMaterial, reverseAmount);
    }

    private static @Nullable DynamiteType.Recipe parseRecipe(@NotNull QweTnts plugin,
                                                             @NotNull String id,
                                                             @NotNull ConfigurationSection section) {
        ConfigurationSection root = section.getConfigurationSection("recipe");
        if (root == null) return null;

        List<String> shape = new ArrayList<>();
        for (String row : root.getStringList("shape")) {
            if (row != null && !row.isBlank()) shape.add(row);
        }
        if (shape.isEmpty()) return null;

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        Map<Character, String> custom = new LinkedHashMap<>();

        ConfigurationSection ingSec = root.getConfigurationSection("ingredients");
        if (ingSec != null) {
            for (String key : ingSec.getKeys(false)) {
                if (key.length() != 1) continue;
                char ch = key.charAt(0);
                Object raw = ingSec.get(key);

                if (raw instanceof String value) {
                    Material parsed = Materials.parse(value);
                    if (parsed != null) {
                        ingredients.put(ch, parsed);
                    } else if (value.toLowerCase(Locale.ROOT).startsWith(ComponentType.PREFIX)) {
                        custom.put(ch, value);
                    }
                } else if (raw instanceof ConfigurationSection cs) {
                    String componentId = cs.getString("component");
                    Material parsed = Materials.parse(cs.getString("material"));
                    if (componentId != null && !componentId.isBlank()) {
                        custom.put(ch, ComponentType.PREFIX + componentId.trim());
                    } else if (parsed != null) {
                        ingredients.put(ch, parsed);
                    }
                }
            }
        }

        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == ' ') continue;
                if (!ingredients.containsKey(c) && !custom.containsKey(c)) {
                    plugin.getLogger().warning("[" + id + "] в рецепте не определён ингредиент: " + c);
                }
            }
        }

        return new DynamiteType.Recipe(shape.toArray(new String[0]), ingredients, custom);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
