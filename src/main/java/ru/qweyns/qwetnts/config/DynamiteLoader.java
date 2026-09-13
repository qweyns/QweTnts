package ru.qweyns.qwetnts.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.BreakRule;
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
 * См. §5.1 ТЗ.
 */
public final class DynamiteLoader {

    private final QweTnts plugin;

    public DynamiteLoader(QweTnts plugin) {
        this.plugin = plugin;
    }

    public Optional<DynamiteType> load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        String id = yaml.getString("name", stripExt(file.getName()))
                .toLowerCase(Locale.ROOT);
        String displayName = yaml.getString("display_name", id);
        String explosionType = yaml.getString("explosion-type",
                id.toUpperCase(Locale.ROOT));
        double radiusMultiplier = yaml.getDouble("radius-multiplier", 1.0);
        int siegeDamage = yaml.getInt("siege-damage", 1);

        int fuseTicks = yaml.getInt("fuse-seconds", 4) * 20;
        float power = (float) yaml.getDouble("power", 4.0);
        int cutEntityDamage = yaml.getInt("cut-entity-damage", 0);
        boolean worksInWater = yaml.getBoolean("works-in-water", false);
        boolean worksInLava = yaml.getBoolean("works-in-lava", false);

        Map<Material, BreakRule> breakableBlocks = loadBreakableBlocks(
                yaml.getConfigurationSection("breakable-blocks"));

        boolean raidEnabled = yaml.getBoolean("raid-block.enabled", false);
        long raidMs = yaml.getLong("raid-block.duration-seconds", 300) * 1000L;
        RaidBlockSettings raidBlock = new RaidBlockSettings(raidEnabled, raidMs);

        Map<Material, Material> transforms = loadTransforms(
                yaml.getConfigurationSection("transformable-blocks"));

        // --- предмет ---
        String itemMatRaw = yaml.getString("item.material", "TNT");
        Material itemMat = Materials.parse(itemMatRaw);
        if (itemMat == null) {
            plugin.getLogger().warning(
                    "Неверный материал предмета в " + file.getName() + ": " + itemMatRaw);
            return Optional.empty();
        }
        String itemName = yaml.getString("item.display_name", "&fДинамит " + displayName);
        List<String> itemLore = yaml.getStringList("item.lore");
        boolean glow = yaml.getBoolean("item.glow", false);

        Recipe recipe = loadRecipe(yaml.getConfigurationSection("recipe"));

        // Валидации §2
        List<String> problems = new ArrayList<>();
        if (fuseTicks <= 0) problems.add("fuse-seconds должен быть > 0, сброшен до 4");
        if (power > 32) {
            plugin.getLogger().warning("Динамит " + id + " имеет power=" + power
                    + " — это может вызывать лаги. Рекомендуется power <= 32.");
        }
        if (!problems.isEmpty()) {
            for (String p : problems) plugin.getLogger().warning("[" + id + "] " + p);
            if (fuseTicks <= 0) fuseTicks = 4 * 20;
        }

        // Временный holder для buildItem.
        DynamiteType itemHolder = new DynamiteType(
                id, displayName, explosionType,
                radiusMultiplier, siegeDamage,
                fuseTicks, power, cutEntityDamage,
                worksInWater, worksInLava,
                new EnumMap<>(Material.class),
                new RaidBlockSettings(false, 0),
                new EnumMap<>(Material.class),
                new ItemStack(itemMat), null);

        ItemStack built = DynamiteType.buildItem(
                plugin, itemHolder, itemMat, itemName, itemLore, glow);

        return Optional.of(new DynamiteType(
                id, displayName, explosionType,
                radiusMultiplier, siegeDamage,
                fuseTicks, power, cutEntityDamage,
                worksInWater, worksInLava,
                breakableBlocks, raidBlock, transforms,
                built, recipe));
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Map<Material, BreakRule> loadBreakableBlocks(ConfigurationSection section) {
        Map<Material, BreakRule> map = new EnumMap<>(Material.class);
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            Material mat = Materials.parse(key);
            if (mat == null) {
                plugin.getLogger().warning(
                        "Незнакомый материал breakable-blocks: " + key);
                continue;
            }
            int chance = clampPercent(section.getInt(key + ".chance", 100));
            int drop = clampPercent(section.getInt(key + ".drop-chance", 0));
            map.put(mat, new BreakRule(chance, drop));
        }
        return map;
    }

    private int clampPercent(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private Map<Material, Material> loadTransforms(ConfigurationSection section) {
        Map<Material, Material> map = new EnumMap<>(Material.class);
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            Material from = Materials.parse(key);
            String toRaw = section.getString(key + ".to");
            Material to = Materials.parse(toRaw);
            if (from == null || to == null) {
                plugin.getLogger().warning(
                        "Незнакомый transformable-blocks: " + key + " -> " + toRaw);
                continue;
            }
            map.put(from, to);
        }
        return map;
    }

    private Recipe loadRecipe(ConfigurationSection section) {
        if (section == null) return null;

        List<String> shape = new ArrayList<>();
        if (section.isList("shape")) {
            for (String row : section.getStringList("shape")) {
                if (row != null && !row.isBlank()) shape.add(row);
            }
        } else {
            String shapeRaw = section.getString("shape");
            if (shapeRaw == null || shapeRaw.isBlank()) return null;
            for (String row : shapeRaw.split(":")) {
                if (!row.isBlank()) shape.add(row);
            }
        }
        if (shape.isEmpty()) return null;

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        Map<Character, String> custom = new LinkedHashMap<>();

        ConfigurationSection ingSec = section.getConfigurationSection("ingredients");
        if (ingSec != null) parseIngredients(ingSec, ingredients, custom);
        if (ingredients.isEmpty() && custom.isEmpty()) {
            // Резерв: плоский формат
            parseIngredients(section, ingredients, custom);
        }

        // Валидация: все символы шейпа должны быть определены
        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == ' ') continue;
                if (!ingredients.containsKey(c) && !custom.containsKey(c)) {
                    plugin.getLogger().warning("В рецепте не определён ингредиент: " + c);
                }
            }
        }

        return new Recipe(shape.toArray(new String[0]), ingredients, custom);
    }

    private void parseIngredients(ConfigurationSection section,
                                  Map<Character, Material> ingredients,
                                  Map<Character, String> custom) {
        for (String k : section.getKeys(false)) {
            if ("shape".equals(k)) continue;
            if (k.length() != 1) continue;
            char ch = k.charAt(0);
            Object raw = section.get(k);
            if (raw instanceof String s) {
                Material m = Materials.parse(s);
                if (m != null) ingredients.put(ch, m);
                else if (s.startsWith("tnt:")) custom.put(ch, s.substring(4));
                else plugin.getLogger().warning("Неизвестный ингредиент " + k + "=" + s);
            } else if (raw instanceof ConfigurationSection cs) {
                String matRaw = cs.getString("material");
                Material m = Materials.parse(matRaw);
                String kind = cs.getString("custom-type");
                if (kind != null) custom.put(ch, kind);
                else if (m != null) ingredients.put(ch, m);
            }
        }
    }
}
