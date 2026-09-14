package ru.qweyns.qwetnts.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.BlastMath;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.BreakRule;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Breaking;
import ru.qweyns.qwetnts.dynamite.DynamiteType.RaidBlockSettings;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Recipe;
import ru.qweyns.qwetnts.util.Materials;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Читает один YAML-файл из {@code dynamites/} и собирает {@link DynamiteType}.
 *
 * <p>Любой мусор в конфиге не роняет плагин: битый ключ пропускается с
 * предупреждением в консоль, критичные ошибки (нет материала предмета,
 * нулевой фитиль) приводят к пропуску всего динамита.</p>
 */
public final class DynamiteLoader {

    private static final float MAX_SAFE_POWER = 32.0f;

    private final QweTnts plugin;

    public DynamiteLoader(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    public @NotNull Optional<DynamiteType> load(@Nullable File file) {
        if (file == null || !file.isFile()) return Optional.empty();

        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось прочитать файл динамита " + file.getName(), ex);
            return Optional.empty();
        }

        String id = firstNonBlank(yaml.getString("name"), stripExt(file.getName()))
                .toLowerCase(Locale.ROOT);
        if (id.isBlank()) {
            plugin.getLogger().warning("Файл " + file.getName() + ": не задан name — пропускаю.");
            return Optional.empty();
        }

        String displayName = firstNonBlank(yaml.getString("display_name"), id);
        String explosionType = firstNonBlank(
                yaml.getString("explosion-type"), id.toUpperCase(Locale.ROOT));

        double radiusMultiplier = Math.max(0.01, yaml.getDouble("radius-multiplier", 1.0));
        int siegeDamage = Math.max(0, yaml.getInt("siege-damage", 1));

        int fuseSeconds = Math.max(1, yaml.getInt("fuse-seconds", 4));
        float power = (float) yaml.getDouble("power", 4.0);
        if (power <= 0f) {
            plugin.getLogger().warning("[" + id + "] power должен быть больше 0 — пропускаю динамит.");
            return Optional.empty();
        }
        if (power > MAX_SAFE_POWER) {
            plugin.getLogger().warning("[" + id + "] power=" + power
                    + " — это может вызывать лаги. Рекомендуется power <= " + MAX_SAFE_POWER + ".");
        }

        int cutEntityDamage = Math.min(100, Math.max(0, yaml.getInt("cut-entity-damage", 0)));
        boolean worksInWater = yaml.getBoolean("works-in-water", false);
        boolean worksInLava = yaml.getBoolean("works-in-lava", false);

        // Переключатель автоподжога можно переопределить для конкретного динамита
        Boolean autoIgnite = null;
        if (yaml.contains("auto-ignite")) {
            autoIgnite = yaml.getBoolean("auto-ignite");
        }

        Breaking breaking = loadBreaking(id, yaml.getConfigurationSection("breaking"));
        Map<Material, Material> transforms = loadTransforms(id, yaml.getConfigurationSection("transformable-blocks"));

        boolean raidEnabled = yaml.getBoolean("raid-block.enabled", false);
        long raidMs = Math.max(0L, yaml.getLong("raid-block.duration-seconds", 300L)) * 1000L;
        RaidBlockSettings raidBlock = raidEnabled && raidMs > 0
                ? new RaidBlockSettings(true, raidMs)
                : RaidBlockSettings.DISABLED;

        // --- предмет ---
        Material itemMat = Materials.parse(firstNonBlank(yaml.getString("item.material"), "TNT"));
        if (itemMat == null) {
            plugin.getLogger().warning("[" + id + "] неверный материал предмета: "
                    + yaml.getString("item.material") + " — пропускаю динамит.");
            return Optional.empty();
        }

        String itemName = firstNonBlank(yaml.getString("item.display_name"), "&fДинамит " + displayName);
        List<String> itemLore = sanitizeList(yaml.getStringList("item.lore"));
        boolean glow = yaml.getBoolean("item.glow", false);

        Recipe recipe = loadRecipe(id, yaml.getConfigurationSection("recipe"));

        // Временный «держатель» — buildItem нужен тип с уже известным id.
        DynamiteType holder = new DynamiteType(
                id, displayName, explosionType,
                radiusMultiplier, siegeDamage,
                fuseSeconds * 20, power, cutEntityDamage,
                worksInWater, worksInLava,
                autoIgnite,
                breaking, transforms, raidBlock,
                new ItemStack(itemMat), null);

        ItemStack built = DynamiteType.buildItem(plugin, holder, itemMat, itemName, itemLore, glow);

        return Optional.of(new DynamiteType(
                id, displayName, explosionType,
                radiusMultiplier, siegeDamage,
                fuseSeconds * 20, power, cutEntityDamage,
                worksInWater, worksInLava,
                autoIgnite,
                breaking, transforms, raidBlock,
                built, recipe));
    }

    // ------------------------------------------------------------------
    // Правила разрушения
    // ------------------------------------------------------------------

    private @NotNull Breaking loadBreaking(@NotNull String id, @Nullable ConfigurationSection section) {
        if (section == null) return Breaking.NONE;

        double maxResistance = Math.max(0.0, section.getDouble("max-resistance", 0.0));
        double scale = section.getDouble("resistance-scale", BlastMath.VANILLA_SCALE);
        if (scale <= 0.0) scale = BlastMath.VANILLA_SCALE;
        int defaultDrop = BlastMath.clampPercent(section.getInt("default-drop-chance", 100));

        Map<Material, BreakRule> rules = new EnumMap<>(Material.class);
        ConfigurationSection blocks = section.getConfigurationSection("blocks");
        if (blocks != null) {
            for (String key : blocks.getKeys(false)) {
                Material material = Materials.parse(key);
                if (material == null) {
                    plugin.getLogger().warning("[" + id + "] неизвестный материал в breaking.blocks: " + key);
                    continue;
                }
                int breakChance = percent(blocks, key, "break-chance", 100);
                int dropChance = percent(blocks, key, "drop-chance", 100);
                rules.put(material, new BreakRule(breakChance, dropChance));
            }
        }

        return new Breaking(maxResistance, scale, defaultDrop,
                rules.isEmpty() ? Map.of() : rules);
    }

    /** Читает percent-значение из вложенной секции: {@code MAT: {break-chance: 50}}. */
    private int percent(@NotNull ConfigurationSection blocks,
                        @NotNull String key,
                        @NotNull String param,
                        int def) {
        Object raw = blocks.get(key);
        if (raw instanceof ConfigurationSection cs) {
            return BlastMath.clampPercent(cs.getInt(param, def));
        }
        // Упрощённый формат: "MAT: 50" — считаем это шансом сломать.
        return BlastMath.clampPercent(blocks.getInt(key, def));
    }

    private @NotNull Map<Material, Material> loadTransforms(@NotNull String id,
                                                            @Nullable ConfigurationSection section) {
        Map<Material, Material> map = new EnumMap<>(Material.class);
        if (section == null) return map;

        for (String key : section.getKeys(false)) {
            Material from = Materials.parse(key);
            String toRaw = section.getString(key + ".to");
            if (from == null) {
                plugin.getLogger().warning("[" + id + "] неизвестный материал в transformable-blocks: " + key);
                continue;
            }
            Material to = Materials.parse(toRaw);
            if (to == null) {
                plugin.getLogger().warning("[" + id + "] неверный материал назначения для "
                        + key + ": " + toRaw);
                continue;
            }
            map.put(from, to);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Рецепт
    // ------------------------------------------------------------------

    private @Nullable Recipe loadRecipe(@NotNull String id, @Nullable ConfigurationSection section) {
        if (section == null) return null;

        List<String> shape = new ArrayList<>();
        if (section.isList("shape")) {
            for (String row : section.getStringList("shape")) {
                if (row != null && !row.isBlank()) shape.add(row);
            }
        } else {
            String raw = section.getString("shape");
            if (raw != null && !raw.isBlank()) {
                for (String row : raw.split(":")) {
                    if (!row.isBlank()) shape.add(row);
                }
            }
        }
        if (shape.isEmpty()) return null;

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        Map<Character, String> custom = new LinkedHashMap<>();

        ConfigurationSection ingSec = section.getConfigurationSection("ingredients");
        if (ingSec != null) {
            parseIngredients(ingSec, ingredients, custom);
        }
        if (ingredients.isEmpty() && custom.isEmpty()) {
            parseIngredients(section, ingredients, custom); // плоский формат
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

        return new Recipe(shape.toArray(new String[0]), ingredients, custom);
    }

    private void parseIngredients(@NotNull ConfigurationSection section,
                                  @NotNull Map<Character, Material> ingredients,
                                  @NotNull Map<Character, String> custom) {
        for (String key : section.getKeys(false)) {
            if ("shape".equalsIgnoreCase(key) || key.length() != 1) continue;
            char ch = key.charAt(0);
            Object raw = section.get(key);

            if (raw instanceof String s) {
                Material material = Materials.parse(s);
                if (material != null) {
                    ingredients.put(ch, material);
                } else if (s.toLowerCase(Locale.ROOT).startsWith("tnt:")) {
                    custom.put(ch, s.substring(4));
                }
            } else if (raw instanceof ConfigurationSection cs) {
                String kind = cs.getString("custom-type");
                Material material = Materials.parse(cs.getString("material"));
                if (kind != null && !kind.isBlank()) {
                    custom.put(ch, kind.trim());
                } else if (material != null) {
                    ingredients.put(ch, material);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private static @NotNull String stripExt(@NotNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static @NotNull String firstNonBlank(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static @NotNull List<String> sanitizeList(@Nullable List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(source.size());
        for (String line : source) {
            if (line != null) out.add(line);
        }
        return out;
    }
}
