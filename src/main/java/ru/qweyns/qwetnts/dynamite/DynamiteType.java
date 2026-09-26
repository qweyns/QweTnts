package ru.qweyns.qwetnts.dynamite;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.component.ComponentType;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.util.Items;
import ru.qweyns.qwetnts.hologram.HologramSettings;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.logging.Level;

/**
 * Полное описание одного динамита: предмет, установка, поджог, взрыв,
 * разрушение блоков, ограничения, эффекты, рецепт.
 *
 * <p>Объект неизменяемый и потокобезопасный: собирается {@link DynamiteLoader}
 * на старте и по {@code /qtnt reload}, дальше только читается.</p>
 *
 * <p>Почти у каждой настройки есть «наследование»: если в файле динамита
 * значение не задано (или задано как {@code -1}/{@code inherit}), берётся
 значение из {@code config.yml}. Так глобальные лимиты остаются глобальными,
 а исключение делается точечно.</p>
 */
public final class DynamiteType {

    private final String id;
    private final String displayName;
    private final boolean enabled;

    private final Placement placement;
    private final Ignition ignition;
    private final Explosion explosion;
    private final Damage damage;
    private final Breaking breaking;
    private final RegionRule regions;
    private final Map<Material, TransformRule> transforms;
    private final TransformChain chain;
    private final RaidBlockSettings raidBlock;
    private final SpawnerMining spawnerMining;
    private final TemporaryBlocks temporary;
    private final Limits limits;
    private final EffectsBundle effects;
    private final ItemSpec item;
    private final @Nullable Recipe recipe;
    private final Messages messages;
    private final @Nullable HologramSettings.Raw hologram;

    private final ItemStack prototype;

    @SuppressWarnings("java:S107")
    public DynamiteType(@NotNull QweTnts plugin,
                        @NotNull String id,
                        @NotNull String displayName,
                        boolean enabled,
                        @NotNull Placement placement,
                        @NotNull Ignition ignition,
                        @NotNull Explosion explosion,
                        @NotNull Damage damage,
                        @NotNull Breaking breaking,
                        @NotNull RegionRule regions,
                        @NotNull Map<Material, TransformRule> transforms,
                        @NotNull TransformChain chain,
                        @NotNull RaidBlockSettings raidBlock,
                        @NotNull SpawnerMining spawnerMining,
                        @NotNull TemporaryBlocks temporary,
                        @NotNull Limits limits,
                        @NotNull EffectsBundle effects,
                        @NotNull ItemSpec item,
                        @Nullable Recipe recipe,
                        @NotNull Messages messages,
                        @Nullable HologramSettings.Raw hologram) {
        this.id = id;
        this.displayName = displayName;
        this.enabled = enabled;
        this.placement = placement;
        this.ignition = ignition;
        this.explosion = explosion;
        this.damage = damage;
        this.breaking = breaking;
        this.regions = regions;
        this.transforms = Collections.unmodifiableMap(new EnumMap<>(transforms));
        this.chain = chain;
        this.raidBlock = raidBlock;
        this.spawnerMining = spawnerMining;
        this.temporary = temporary;
        this.limits = limits;
        this.effects = effects;
        this.item = item;
        this.recipe = recipe;
        this.messages = messages;
        this.hologram = hologram;
        this.prototype = buildItem(plugin, this, item.material(), item.displayName(), item.lore(),
                item.glow(), item.customModelData(), item.itemModel(), item.unbreakable());
    }

    // ------------------------------------------------------------------
    // Базовые
    // ------------------------------------------------------------------

    public @NotNull String id() { return id; }
    public @NotNull String displayName() { return displayName; }
    public boolean enabled() { return enabled; }

    public @NotNull Placement placement() { return placement; }
    public @NotNull Ignition ignition() { return ignition; }
    public @NotNull Explosion explosion() { return explosion; }
    public @NotNull Damage damage() { return damage; }
    public @NotNull Breaking breaking() { return breaking; }
    public @NotNull RegionRule regions() { return regions; }
    public @NotNull Map<Material, TransformRule> transforms() { return transforms; }
    public @NotNull TransformChain chain() { return chain; }

    /**
     * Следующая ступень деградации для блока: {@code null}, если материал не
     * участвует в цепочке или это уже последняя ступень.
     */
    public @Nullable Material degrade(@NotNull Material material) {
        return chain.next(material);
    }
    public @NotNull RaidBlockSettings raidBlock() { return raidBlock; }
    public @NotNull SpawnerMining spawnerMining() { return spawnerMining; }
    public @NotNull TemporaryBlocks temporary() { return temporary; }
    public @NotNull Limits limits() { return limits; }
    public @NotNull EffectsBundle effects() { return effects; }
    public @NotNull ItemSpec itemSpec() { return item; }
    public @Nullable Recipe recipe() { return recipe; }
    public @NotNull Messages messages() { return messages; }
    /** Секция {@code hologram} файла динамита ({@code null} — берутся общие настройки). */
    public @Nullable HologramSettings.Raw hologram() { return hologram; }

    /** Копия предмета динамита с PDC-меткой типа. */
    public @NotNull ItemStack item() {
        return prototype.clone();
    }

    // ------------------------------------------------------------------
    // Производные значения (учёт наследования из config.yml)
    // ------------------------------------------------------------------

    /** Поджигается ли сразу из руки: сначала своё значение, потом глобальное. */
    public boolean isAutoIgnite(boolean globalDefault) {
        Boolean own = ignition.autoIgnite();
        return own != null ? own : globalDefault;
    }

    /** Право на использование: своё или стандартное {@code qwetnts.type.<id>}. */
    public @NotNull String permission() {
        String custom = placement.permission();
        return custom == null || custom.isBlank() ? "qwetnts.type." + id : custom.trim();
    }

    public boolean requiresPermission() {
        return placement.permissionRequired();
    }

    /** Разрешён ли такой способ поджога. */
    public boolean canBeIgnitedBy(@NotNull IgniteCause cause) {
        return ignition.causes().contains(cause);
    }

    /** Радиус цепной детонации: своё значение или глобальное из config.yml. */
    public int chainRadius(int globalDefault) {
        int own = ignition.chain().radius();
        return own < 0 ? Math.max(0, globalDefault) : own;
    }

    /** Задержка цепного поджога в тиках: своё значение или глобальное. */
    public long chainDelayTicks(long globalDefault) {
        long own = ignition.chain().delayTicks();
        return own < 0 ? Math.max(1L, globalDefault) : own;
    }

    /** Участвует ли динамит в цепной детонации (как источник и как цель). */
    public boolean chainEnabled() {
        return ignition.chain().enabled() && ignition.chain().canBeChained();
    }

    public long cooldownMillis(long globalDefault) {
        long own = limits.cooldownMillis();
        return own < 0 ? globalDefault : own;
    }

    public int maxPerPlayer(int globalDefault) {
        int own = limits.maxPrimedPerPlayer();
        return own < 0 ? globalDefault : own;
    }

    public int maxPerChunk(int globalDefault) {
        int own = limits.maxPrimedPerChunk();
        return own < 0 ? globalDefault : own;
    }

    /** Радиус досмотра прочных блоков: минимум из своего и глобального потолка. */
    public int scanRadius(int globalCap) {
        int own = breaking.scanRadius();
        return Math.max(1, Math.min(own <= 0 ? globalCap : own, globalCap));
    }

    /** Фитиль с учётом случайного разброса (если он задан). */
    public int rollFuseTicks(@NotNull RandomGenerator random) {
        int spread = Math.max(0, explosion.fuseSpreadTicks());
        if (spread == 0) return explosion.fuseTicks();
        int min = Math.max(1, explosion.fuseTicks() - spread);
        return min + random.nextInt(spread * 2 + 1);
    }

    /** Проверка мира: свои списки или глобальный фильтр. */
    public boolean isAllowedIn(@Nullable World world, @NotNull Settings.WorldFilter global) {
        if (world == null) return false;
        Settings.WorldFilter own = limits.worlds();
        return own == null ? global.isAllowed(world) : own.isAllowed(world);
    }

    // ------------------------------------------------------------------
    // Рецепт
    // ------------------------------------------------------------------

    /**
     * Регистрирует Bukkit-рецепт и запоминает ключ, чтобы на {@code /qtnt reload}
     * старые рецепты вычищались и не копились дубликаты.
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
            // Кастомные ингредиенты в рецепте — плейсхолдер по материалу,
            // а точную метку проверяет CustomRecipeListener:
            //   * tnt:<id>       — плейсхолдер TNT;
            //   * component:<id> — плейсхолдер материала компонента
            //     (у Взрывчатого вещества это GUNPOWDER, а не TNT).
            for (Map.Entry<Character, String> entry : recipe.customIngredients().entrySet()) {
                String requirement = entry.getValue();
                Material placeholder = requirement.startsWith(ComponentType.PREFIX)
                        ? plugin.components().materialOf(
                                ComponentType.stripPrefix(requirement), Material.TNT)
                        : Material.TNT;
                shaped.setIngredient(entry.getKey(), placeholder);
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
                    "Не удалось зарегистрировать рецепт для " + id, ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Вложенные настройки
    // ------------------------------------------------------------------

    /** Установка и расход предмета. */
    public record Placement(boolean placeable,
                            boolean consumeOnUse,
                            @Nullable String permission,
                            boolean permissionRequired) {

        public static final Placement DEFAULT = new Placement(true, true, null, true);
    }

    /** Поджог. */
    public record Ignition(@Nullable Boolean autoIgnite,
                           @NotNull Set<IgniteCause> causes,
                           long delayTicks,
                           @NotNull Chain chain) {

        public static final Ignition DEFAULT = new Ignition(null,
                EnumSet.allOf(IgniteCause.class), 0L, Chain.DEFAULT);
    }

    /** Способы поджога — можно разрешить только часть. */
    public enum IgniteCause {
        FLINT_AND_STEEL,
        FIRE_CHARGE,
        FIRE,
        LAVA,
        EXPLOSION,
        LIGHTNING,
        PUNCH
    }

    /**
     * Цепная детонация.
     *
     * <p>{@code radius} и {@code delayTicks} равны {@code -1}, если в файле
     * динамита они не заданы: тогда работают глобальные
     * {@code settings.dynamites.chain-radius} и
     * {@code settings.dynamites.chain-delay-ticks}. Раньше в конструкторе
     * стояли готовые 4 и 2, и глобальные настройки из {@code config.yml}
     * оказывались мёртвыми: правка «цепляться шире» не влияла ни на один
     * динамит, где ключ не переопределён явно.</p>
     */
    public record Chain(boolean enabled,
                        boolean canBeChained,
                        int radius,
                        long delayTicks) {

        public static final Chain DEFAULT = new Chain(true, true, -1, -1L);
    }

    /** Параметры взрыва. */
    public record Explosion(@NotNull String type,
                            double radiusMultiplier,
                            float power,
                            boolean fire,
                            int fuseTicks,
                            int fuseSpreadTicks,
                            boolean worksInWater,
                            boolean worksInLava,
                            int siegeDamage,
                            int maxBlocks) {
    }

    /** Урон живым сущностям. */
    public record Damage(int cutEntity, int cutPlayer, double multiplier) {

        public static final Damage NONE = new Damage(0, 0, 1.0);
    }

    /** Разрушение блоков. */
    /**
     * Правила разрушения.
     *
     * @param destructive {@code false} — динамит вообще не ломает блоки
     *        (рельеф остаётся целиком), но всё остальное работает: урон
     *        сущностям, урон осаде, эффекты, добыча спавнеров, временные
     *        блоки. Именно так на HolyWorld устроены Стиллер, Надёжный
     *        стиллер и Ледяная волна.
     */
    public record Breaking(boolean destructive,
                           @NotNull Shape shape,
                           int cubeSize,
                           boolean vanillaBlockList,
                           double maxResistance,
                           double resistanceScale,
                           int defaultDropChance,
                           int scanRadius,
                           @NotNull Map<Material, BreakRule> blocks) {

        /** Форма области разрушения. */
        public enum Shape {
            /** Сфера — как в vanilla (по сопротивлению материала). */
            SPHERE,
            /** Ровный куб {@code cubeSize³} — механика Динамита Б2. */
            CUBE
        }

        public @Nullable BreakRule ruleFor(@Nullable Material material) {
            return material == null ? null : blocks.get(material);
        }
    }

    /**
     * Правила по отношению к приватам QPS.
     *
     * @param onlyOutside {@code true} — заряд не срабатывает внутри любого
     *        привата (механика Динамита Б2 из HolyWorld Lite)
     */
    public record RegionRule(boolean onlyOutside) {

        public static final RegionRule NONE = new RegionRule(false);
    }

    /** Шанс сломать и шанс, что блок выпадет предметом (в процентах). */
    public record BreakRule(int breakChance, int dropChance) {

        public boolean keepDrop(@NotNull RandomGenerator random) {
            return BlastMath.roll(dropChance, random);
        }
    }

    /** Деградация: блок превращается в другой, а не исчезает. */
    public record TransformRule(@NotNull Material to, int chance) {
    }

    /**
     * Цепочка деградации: та же механика, что у стены бункера, только для
     * обычных блоков мира — древние обломки → плачущий обсидиан → обсидиан
     * → дыра.
     *
     * <p>Каждый взрыв снимает одну ступень. Зачем это нужно: прочный блок
     * (обсидиановое семейство) иначе исчезал бы целиком за один заряд, и
     * «пролом» в стене привата стоил бы ровно одного C4.</p>
     *
     * @param stages ступени по порядку, от самой прочной к самой слабой
     * @param chance шанс снять одну ступень за взрыв, в процентах
     */
    public record TransformChain(@NotNull List<Material> stages, int chance) {

        public static final TransformChain NONE = new TransformChain(List.of(), 100);

        /** Цепочка имеет смысл, только если ступеней хотя бы две. */
        public boolean isEnabled() {
            return stages.size() >= 2 && chance > 0;
        }

        /** Этот материал участвует в цепочке (и не последняя её ступень)? */
        public boolean degrades(@Nullable Material material) {
            return next(material) != null;
        }

        /** Следующая ступень для материала; {@code null} — материал вне цепочки. */
        public @Nullable Material next(@Nullable Material material) {
            if (material == null || !isEnabled()) return null;

            int index = stages.indexOf(material);
            if (index < 0 || index + 1 >= stages.size()) return null;
            return stages.get(index + 1);
        }
    }

    /** Рейд-блок (анти-феникс). */
    public record RaidBlockSettings(boolean enabled,
                                    long durationMs,
                                    @NotNull Set<Material> materials,
                                    int radius) {

        public static final RaidBlockSettings DISABLED =
                new RaidBlockSettings(false, 0L, Set.of(), 0);
    }

    /**
     * Добыча спавнеров (Фаза 2): спавнер выбивается предметом с шансом,
     * иначе при взрыве он просто исчезает.
     */
    public record SpawnerMining(boolean enabled,
                                int chance,
                                int xp,
                                boolean keepEntityType) {

        public static final SpawnerMining DISABLED = new SpawnerMining(false, 0, 0, true);
    }

    /**
     * Временные блоки после взрыва (Фаза 2): например, лёд, который тает
     * через N секунд. В блоке запоминается исходный материал, чтобы вернуть
     * всё как было.
     */
    public record TemporaryBlocks(boolean enabled,
                                  @NotNull Material material,
                                  int radius,
                                  int chance,
                                  long durationMs,
                                  @NotNull Set<Material> replaceable) {

        public static final TemporaryBlocks DISABLED =
                new TemporaryBlocks(false, Material.AIR, 0, 0, 0L, Set.of());
    }

    /** Ограничения; {@code -1} — взять значение из config.yml. */
    public record Limits(long cooldownMillis,
                         int maxPrimedPerPlayer,
                         int maxPrimedPerChunk,
                         @Nullable Settings.WorldFilter worlds) {

        public static final Limits INHERIT = new Limits(-1L, -1, -1, null);
    }

    /** Звуки и частицы на три события. */
    public record EffectsBundle(@NotNull DynamiteEffect place,
                                @NotNull DynamiteEffect ignite,
                                @NotNull DynamiteEffect explode) {

        public static final EffectsBundle NONE =
                new EffectsBundle(DynamiteEffect.NONE, DynamiteEffect.NONE, DynamiteEffect.NONE);
    }

    /** Предмет. */
    public record ItemSpec(@NotNull Material material,
                           @NotNull String displayName,
                           @NotNull List<String> lore,
                           boolean glow,
                           int customModelData,
                           @Nullable String itemModel,
                           boolean unbreakable) {
    }

    public record Recipe(@NotNull String[] shape,
                         @NotNull Map<Character, Material> ingredients,
                         @NotNull Map<Character, String> customIngredients) {
    }

    /** Переопределения сообщений (пустая строка — брать из lang). */
    public record Messages(@Nullable String placed, @Nullable String ignited) {

        public static final Messages NONE = new Messages(null, null);
    }

    // ------------------------------------------------------------------
    // Сборка предмета
    // ------------------------------------------------------------------

    /** Собирает ItemStack динамита: имя, лор, PDC-метка, модель, прочность. */
    @SuppressWarnings("java:S107")
    private static @NotNull ItemStack buildItem(@NotNull QweTnts plugin,
                                               @NotNull DynamiteType type,
                                               @NotNull Material material,
                                               @Nullable String displayNameRaw,
                                               @Nullable List<String> loreRaw,
                                               boolean glow,
                                               int customModelData,
                                               @Nullable String itemModel,
                                               boolean unbreakable) {
        return Items.build(plugin, plugin.keys().dynamiteKind, type.id(), material,
                displayNameRaw, loreRaw, glow, customModelData, itemModel, unbreakable);
    }
}
