package ru.qweyns.qwetnts.dynamite;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Colors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Описание одного динамита: предмет, параметры взрыва, что именно он ломает,
 * рейд-блок, рецепт.
 *
 * <p>Объект неизменяемый и потокобезопасный: создаётся {@link DynamiteLoader}
 * при старте и на {@code /qtnt reload}, дальше только читается.</p>
 */
public final class DynamiteType {

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
    private final @Nullable Boolean autoIgniteOverride;
    private final Breaking breaking;
    private final Map<Material, Material> transforms;
    private final RaidBlockSettings raidBlock;
    private final ItemStack item;
    private final @Nullable Recipe recipe;

    public DynamiteType(@NotNull String id,
                        @NotNull String displayName,
                        @NotNull String explosionType,
                        double radiusMultiplier,
                        int siegeDamage,
                        int fuseTicks,
                        float power,
                        int cutEntityDamage,
                        boolean worksInWater,
                        boolean worksInLava,
                        @Nullable Boolean autoIgniteOverride,
                        @NotNull Breaking breaking,
                        @NotNull Map<Material, Material> transforms,
                        @NotNull RaidBlockSettings raidBlock,
                        @NotNull ItemStack item,
                        @Nullable Recipe recipe) {
        this.id = id;
        this.displayName = displayName;
        this.explosionType = explosionType;
        this.radiusMultiplier = Math.max(0.01, radiusMultiplier);
        this.siegeDamage = Math.max(0, siegeDamage);
        this.fuseTicks = Math.max(1, fuseTicks);
        this.power = Math.max(0f, power);
        this.cutEntityDamage = BlastMath.clampPercent(cutEntityDamage);
        this.worksInWater = worksInWater;
        this.worksInLava = worksInLava;
        this.autoIgniteOverride = autoIgniteOverride;
        this.breaking = breaking;
        this.transforms = Collections.unmodifiableMap(new EnumMap<>(transforms));
        this.raidBlock = raidBlock;
        this.item = item;
        this.recipe = recipe;
    }

    public @NotNull String id() { return id; }
    public @NotNull String displayName() { return displayName; }
    public @NotNull String explosionType() { return explosionType; }
    public double radiusMultiplier() { return radiusMultiplier; }
    public int siegeDamage() { return siegeDamage; }
    public int fuseTicks() { return fuseTicks; }
    public float power() { return power; }
    public int cutEntityDamage() { return cutEntityDamage; }
    public boolean worksInWater() { return worksInWater; }
    public boolean worksInLava() { return worksInLava; }
    public @NotNull Breaking breaking() { return breaking; }
    public @NotNull Map<Material, Material> transforms() { return transforms; }
    public @NotNull RaidBlockSettings raidBlock() { return raidBlock; }
    public @Nullable Recipe recipe() { return recipe; }

    /**
     * Поджигается ли динамит сразу в руке.
     *
     * @param globalDefault значение {@code settings.dynamites.auto-ignite} из config.yml
     */
    public boolean isAutoIgnite(boolean globalDefault) {
        return autoIgniteOverride != null ? autoIgniteOverride : globalDefault;
    }

    /** Копия предмета динамита (PDC-метка типа уже внутри). */
    public @NotNull ItemStack item() {
        ItemStack copy = item.clone();
        if (copy.getType().isAir()) return copy;
        return copy;
    }

    /** Показать игроку, как поджигать этот динамит (для сообщений). */
    public boolean needsManualIgnition(boolean globalDefault) {
        return !isAutoIgnite(globalDefault);
    }

    // ------------------------------------------------------------------
    // Рецепт
    // ------------------------------------------------------------------

    /**
     * Регистрирует Bukkit-рецепт и запоминает ключ, чтобы на {@code /qtnt reload}
     * старые рецепты вычищались и не копились дубликаты.
     *
     * <p>Кастомные ингредиенты (PDC) матчатся {@code CustomRecipeListener}.</p>
     */
    public @Nullable NamespacedKey registerRecipe(@NotNull QweTnts plugin) {
        if (recipe == null) return null;
        try {
            NamespacedKey key = new NamespacedKey(plugin, "dynamite_" + id);
            ShapedRecipe shaped = new ShapedRecipe(key, item());
            shaped.shape(recipe.shape());

            for (Map.Entry<Character, Material> entry : recipe.ingredients().entrySet()) {
                shaped.setIngredient(entry.getKey(), entry.getValue());
            }
            // Кастомные ингредиенты: в рецепте — плейсхолдер TNT,
            // валидацию PDC берёт на себя CustomRecipeListener.
            for (Map.Entry<Character, String> entry : recipe.customIngredients().entrySet()) {
                shaped.setIngredient(entry.getKey(), Material.TNT);
            }

            if (plugin.getServer().addRecipe(shaped)) {
                plugin.registry().addRecipeKey(key);
                if (!recipe.customIngredients().isEmpty()) {
                    plugin.customRecipes().markRequiresCustom(key, recipe.shape(), recipe.customIngredients());
                }
                return key;
            }
            return null;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось зарегистрировать рецепт для " + id, ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Вложенные типы
    // ------------------------------------------------------------------

    /**
     * Правила разрушения блоков.
     *
     * @param maxResistance    потолок взрывоустойчивости (1200 = обсидиан и
     *                         древние обломки; выше — блок не трогаем)
     * @param resistanceScale  множитель формулы {@code (r + 0.3) * scale}
     * @param defaultDropChance шанс выпадения для блоков без явного правила
     * @param blocks           явные правила по материалам (перекрывают порог)
     */
    public record Breaking(double maxResistance,
                           double resistanceScale,
                           int defaultDropChance,
                           @NotNull Map<Material, BreakRule> blocks) {

        public static final Breaking NONE =
                new Breaking(0.0, BlastMath.VANILLA_SCALE, 100, Map.of());

        public @Nullable BreakRule ruleFor(@Nullable Material material) {
            return material == null ? null : blocks.get(material);
        }
    }

    /** Шанс сломать и шанс, что блок выпадет предметом (в процентах). */
    public record BreakRule(int breakChance, int dropChance) {

        public static final BreakRule ALWAYS = new BreakRule(100, 100);

        public boolean keepDrop(@NotNull java.util.random.RandomGenerator random) {
            return BlastMath.roll(dropChance, random);
        }
    }

    public record RaidBlockSettings(boolean enabled, long durationMs) {

        public static final RaidBlockSettings DISABLED = new RaidBlockSettings(false, 0L);
    }

    public record Recipe(@NotNull String[] shape,
                         @NotNull Map<Character, Material> ingredients,
                         @NotNull Map<Character, String> customIngredients) {
    }

    // ------------------------------------------------------------------
    // Сборка предмета
    // ------------------------------------------------------------------

    /** Собирает ItemStack динамита с PDC-меткой и Adventure display/lore. */
    public static @NotNull ItemStack buildItem(@NotNull QweTnts plugin,
                                               @NotNull DynamiteType type,
                                               @NotNull Material material,
                                               @Nullable String displayNameRaw,
                                               @Nullable List<String> loreRaw,
                                               boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        Component name = Colors.formatItem(displayNameRaw);
        if (!name.equals(Component.empty())) {
            meta.displayName(name);
        }

        if (loreRaw != null && !loreRaw.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreRaw.size());
            for (String line : loreRaw) {
                if (line == null) continue;
                lore.add(Colors.formatItem(line));
            }
            meta.lore(lore);
        }

        if (glow) {
            // Свечение без фейкового зачарования (Paper 1.20.5+).
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        meta.getPersistentDataContainer().set(
                plugin.keys().dynamiteKind,
                PersistentDataType.STRING,
                type.id());

        stack.setItemMeta(meta);
        return stack;
    }
}
