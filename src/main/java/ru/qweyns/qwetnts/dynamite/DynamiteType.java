package ru.qweyns.qwetnts.dynamite;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Описание одного кастомного динамита: предмет, параметры взрыва, рецепт, правила.
 */
public final class DynamiteType {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final String id;
    private final String displayName;
    private final String explosionType;
    private final double radiusMultiplier;
    private final int siegeDamage;
    private final int fuseTicks;
    private final float power;
    private final int cutEntityDamage;
    private final boolean worksInWater;
    private final boolean worksInLava;
    private final Map<Material, BreakRule> breakableBlocks;
    private final RaidBlockSettings raidBlock;
    private final Map<Material, Material> transforms;
    private final ItemStack item;
    private final @Nullable Recipe recipe;

    public DynamiteType(String id,
                        String displayName,
                        String explosionType,
                        double radiusMultiplier,
                        int siegeDamage,
                        int fuseTicks,
                        float power,
                        int cutEntityDamage,
                        boolean worksInWater,
                        boolean worksInLava,
                        Map<Material, BreakRule> breakableBlocks,
                        RaidBlockSettings raidBlock,
                        Map<Material, Material> transforms,
                        ItemStack item,
                        @Nullable Recipe recipe) {
        this.id = id;
        this.displayName = displayName;
        this.explosionType = explosionType;
        this.radiusMultiplier = radiusMultiplier;
        this.siegeDamage = siegeDamage;
        this.fuseTicks = fuseTicks;
        this.power = power;
        this.cutEntityDamage = cutEntityDamage;
        this.worksInWater = worksInWater;
        this.worksInLava = worksInLava;
        this.breakableBlocks = Collections.unmodifiableMap(new EnumMap<>(breakableBlocks));
        this.raidBlock = raidBlock;
        this.transforms = Collections.unmodifiableMap(new EnumMap<>(transforms));
        this.item = item;
        this.recipe = recipe;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String explosionType() {
        return explosionType;
    }

    public double radiusMultiplier() {
        return radiusMultiplier;
    }

    public int siegeDamage() {
        return siegeDamage;
    }

    public int fuseTicks() {
        return fuseTicks;
    }

    public float power() {
        return power;
    }

    public int cutEntityDamage() {
        return cutEntityDamage;
    }

    public boolean worksInWater() {
        return worksInWater;
    }

    public boolean worksInLava() {
        return worksInLava;
    }

    public Map<Material, BreakRule> breakableBlocks() {
        return breakableBlocks;
    }

    public RaidBlockSettings raidBlock() {
        return raidBlock;
    }

    public Map<Material, Material> transforms() {
        return transforms;
    }

    public ItemStack item() {
        return item.clone();
    }

    /**
     * Регистрирует Bukkit-рецепт динамита и запоминает ключ в реестре,
     * чтобы корректно вычистить его при /qtnt reload без дубликатов.
     * Кастомные ингредиенты (по PDC) регистрируются в CustomRecipeListener.
     */
    public @Nullable NamespacedKey registerRecipe(QweTnts plugin) {
        if (recipe == null) return null;
        try {
            NamespacedKey key = new NamespacedKey(plugin, "dynamite_" + id);
            ShapedRecipe r = new ShapedRecipe(key, item);
            r.shape(recipe.shape());
            for (var e : recipe.ingredients().entrySet()) {
                r.setIngredient(e.getKey(), e.getValue());
            }
            // Кастомные ингредиенты — в рецепте используем TNT как плейсхолдер,
            // а валидацию PDC берёт на себя CustomRecipeListener.
            for (var e : recipe.customIngredients().entrySet()) {
                r.setIngredient(e.getKey(), Material.TNT);
            }
            boolean added = Bukkit.addRecipe(r);
            if (added) {
                plugin.registry().addRecipeKey(key);
                if (!recipe.customIngredients().isEmpty()) {
                    plugin.customRecipes().markRequiresCustom(key, recipe.shape(), recipe.customIngredients());
                }
            }
            return key;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось зарегистрировать рецепт для " + id, ex);
            return null;
        }
    }

    // --- Вложенные типы ---

    public record BreakRule(int chancePercent, int dropChancePercent) {
    }

    public record RaidBlockSettings(boolean enabled, long durationMs) {
    }

    public record Recipe(String[] shape,
                         Map<Character, Material> ingredients,
                         Map<Character, String> customIngredients) {
    }

    /** Собирает ItemStack динамита с PDC-меткой типа и Adventure display/lore. */
    public static ItemStack buildItem(QweTnts plugin,
                                      DynamiteType type,
                                      Material material,
                                      String displayNameRaw,
                                      List<String> loreRaw,
                                      boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        meta.displayName(mm(displayNameRaw));
        if (loreRaw != null && !loreRaw.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreRaw.size());
            for (String line : loreRaw) {
                lore.add(mm(line));
            }
            meta.lore(lore);
        }
        if (glow) {
            meta.addEnchant(Enchantment.LUCK, 1, true);
            meta.addItemFlags(
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ITEM_SPECIFICS);
        }
        meta.getPersistentDataContainer().set(
                plugin.keys().dynamiteKind,
                PersistentDataType.STRING,
                type.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component mm(String s) {
        if (s == null || s.isBlank()) {
            return Component.empty();
        }
        return MM.deserialize(legacyToMini(s));
    }

    /**
     * Переводит legacy-цвета ({@code &c/&l/§c}) в MiniMessage-теги для
     * совместимости со старыми конфигами. Перед каждым цветом/сбросом
     * вставляем {@code <reset>}, чтобы форматирование не протекало между
     * строками лора.
     */
    static String legacyToMini(String in) {
        StringBuilder out = new StringBuilder(in.length() + 16);
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < in.length()) {
                char code = Character.toLowerCase(in.charAt(i + 1));
                String tag = legacyCodeToTag(code);
                if (tag != null) {
                    // Цвет или reset сбрасывают предыдущие стили целиком.
                    if (isColorOrReset(code)) out.append("<reset>");
                    out.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isColorOrReset(char code) {
        return (code >= '0' && code <= '9')
                || (code >= 'a' && code <= 'f')
                || code == 'r';
    }

    private static String legacyCodeToTag(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'l' -> "b";
            case 'o' -> "i";
            case 'n' -> "u";
            case 'm' -> "strikethrough";
            case 'k' -> "obfuscated";
            case 'r' -> "reset";
            default -> null;
        };
    }
}
