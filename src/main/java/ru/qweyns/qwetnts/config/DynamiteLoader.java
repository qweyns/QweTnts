package ru.qweyns.qwetnts.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.component.ComponentType;
import ru.qweyns.qwetnts.dynamite.BlastMath;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.BreakRule;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Breaking;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Chain;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Damage;
import ru.qweyns.qwetnts.dynamite.DynamiteType.EffectsBundle;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Explosion;
import ru.qweyns.qwetnts.dynamite.DynamiteType.IgniteCause;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Ignition;
import ru.qweyns.qwetnts.dynamite.DynamiteType.ItemSpec;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Limits;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Messages;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Placement;
import ru.qweyns.qwetnts.dynamite.DynamiteType.RaidBlockSettings;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Recipe;
import ru.qweyns.qwetnts.dynamite.DynamiteType.TransformChain;
import ru.qweyns.qwetnts.dynamite.DynamiteType.TransformRule;
import ru.qweyns.qwetnts.util.Materials;
import ru.qweyns.qwetnts.hologram.HologramSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * Читает один YAML-файл и собирает {@link DynamiteType}.
 *
 * <p>Обычные динамиты лежат в {@code dynamites/}, а TNT-пушка — отдельным
 * файлом {@code cannon.yml} в корне папки плагина: она не динамит, а
 * устройство, которое стреляет динамитами. Формат у обоих один и тот же,
 * поэтому загрузчик общий; отличается только место на диске.</p>
 *
 * <p>Любой мусор в конфиге не роняет плагин: неизвестный материал пропускается
 * с предупреждением, а критичные ошибки (нет материала предмета, нулевая
 * мощность) приводят к пропуску только этого динамита.</p>
 *
 * <p>Поддерживаются две схемы:</p>
 * <ul>
 *   <li><b>новая</b> — всё по секциям: {@code placement}, {@code ignition},
 *       {@code explosion}, {@code damage}, {@code breaking}, {@code transforms},
 *       {@code raid-block}, {@code limits}, {@code effects}, {@code item},
 *       {@code messages};</li>
 *   <li><b>старая плоская</b> (версия 1.0): {@code explosion-type},
 *       {@code fuse-seconds}, {@code power}, {@code breakable-blocks},
 *       {@code transformable-blocks}, {@code cut-entity-damage} и т.д.</li>
 * </ul>
 * Сначала ищется ключ в новой секции, потом — плоский ключ для совместимости.
 */
public final class DynamiteLoader {

    private static final float MAX_SAFE_POWER = 32.0f;
    /** Сторона куба по умолчанию для формы CUBE: 25×25×25, как Б2 на HW. */
    private static final int DEFAULT_CUBE_SIZE = 25;

    /** Предел стороны куба: 33³ ≈ 36 тысяч блоков за взрыв — уже предел. */
    private static final int MAX_CUBE_SIZE = 33;

    /** Потолок сопротивления по умолчанию: граница семейства обсидиана. */
    private static final double DEFAULT_MAX_RESISTANCE = 1200.0;

    /** Служебные ключи секции {@code transforms} (не материалы). */
    /** Префикс ингредиента-динамита в рецепте: {@code tnt:dynamite_a}. */
    private static final String TNT_PREFIX = "tnt:";

    private static final String CHAIN_KEY = "chain";
    private static final String CHAIN_CHANCE_KEY = "chain-chance";

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

        boolean enabled = yaml.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Динамит " + id + " отключён (enabled: false) — пропускаю.");
            return Optional.empty();
        }

        String displayName = firstNonBlank(yaml.getString("display_name"), id);

        ItemSpec itemSpec = parseItem(plugin, id, yaml);
        if (itemSpec == null) return Optional.empty();

        Explosion explosion = loadExplosion(id, yaml);
        if (explosion == null) return Optional.empty();

        boolean craftable = yaml.getBoolean("craftable", true);
        Recipe recipe = craftable ? parseRecipe(plugin, id, yaml) : null;

        Map<Material, TransformRule> transforms = loadTransforms(id, yaml);
        TransformChain chain = loadChain(id, yaml);
        warnOnChainConflicts(id, transforms, chain);

        return Optional.of(new DynamiteType(
                plugin,
                id,
                displayName,
                true,
                loadPlacement(yaml),
                loadIgnition(id, yaml),
                explosion,
                loadDamage(yaml),
                loadBreaking(id, yaml),
                loadRegionRule(yaml),
                transforms,
                chain,
                loadRaidBlock(id, yaml),
                loadSpawnerMining(yaml),
                loadTemporaryBlocks(id, yaml),
                loadLimits(id, yaml),
                loadEffects(yaml),
                itemSpec,
                recipe,
                loadMessages(yaml),
                loadHologram(yaml)));
    }

    /**
     * Секция {@code hologram} файла динамита — только то, что отличается от
     * общих настроек в {@code config.yml}. Нет секции — {@code null}, и тогда
     * работают общие.
     */
    private @Nullable HologramSettings.Raw loadHologram(@NotNull YamlConfiguration yaml) {
        if (!yaml.isConfigurationSection("hologram")) return null;
        return HologramSettings.Raw.from(yaml.getConfigurationSection("hologram"));
    }

    // ------------------------------------------------------------------
    // Установка и права
    // ------------------------------------------------------------------

    private @NotNull Placement loadPlacement(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("placement");
        return new Placement(
                bool(section, yaml, "placeable", "placeable", true),
                bool(section, yaml, "consume-on-use", "consume-on-use", true),
                blankToNull(str(section, yaml, "permission", "permission", "")),
                bool(section, yaml, "permission-required", "permission-required", true));
    }

    // ------------------------------------------------------------------
    // Поджог
    // ------------------------------------------------------------------

    private @NotNull Ignition loadIgnition(@NotNull String id, @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("ignition");

        Boolean auto = null;
        if (section != null && section.contains("auto")) {
            auto = section.getBoolean("auto");
        } else if (yaml.contains("auto-ignite")) {
            auto = yaml.getBoolean("auto-ignite");
        }

        Set<IgniteCause> causes = EnumSet.noneOf(IgniteCause.class);
        List<String> raw = section != null ? section.getStringList("causes") : List.of();
        if (raw.isEmpty()) {
            causes = EnumSet.allOf(IgniteCause.class);
        } else {
            for (String value : raw) {
                if (value == null || value.isBlank()) continue;
                try {
                    causes.add(IgniteCause.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("[" + id + "] неизвестный способ поджога: " + value);
                }
            }
            if (causes.isEmpty()) causes = EnumSet.allOf(IgniteCause.class);
        }

        Chain chain = Chain.DEFAULT;
        if (section != null && section.isConfigurationSection("chain")) {
            ConfigurationSection chainSection = section.getConfigurationSection("chain");
            if (chainSection != null) {
                chain = new Chain(
                        chainSection.getBoolean("enabled", true),
                        chainSection.getBoolean("can-be-chained", true),
                        // -1 — «как в config.yml»: иначе глобальные настройки
                        // цепочки были бы недостижимы.
                        chainSection.contains("radius")
                                ? Math.max(0, chainSection.getInt("radius", 4)) : -1,
                        chainSection.contains("delay-ticks")
                                ? Math.max(1L, chainSection.getLong("delay-ticks", 2L)) : -1L);
            }
        } else {
            // Плоский (устаревший) формат: те же правила наследования.
            boolean flatEnabled = bool(null, yaml, "chain-detonation", "chain-detonation", true);
            chain = new Chain(
                    flatEnabled,
                    true,
                    yaml.contains("chain-radius")
                            ? Math.max(0, integer(null, yaml, "chain-radius", "chain-radius", 4)) : -1,
                    yaml.contains("chain-delay-ticks")
                            ? Math.max(1L, longValue(null, yaml, "chain-delay-ticks",
                                    "chain-delay-ticks", 2L)) : -1L);
        }

        return new Ignition(auto, causes,
                Math.max(0L, longValue(section, yaml, "delay-ticks", "ignite-delay-ticks", 0L)),
                new Chain(chain.enabled(), chain.canBeChained(), chain.radius(), chain.delayTicks()));
    }

    // ------------------------------------------------------------------
    // Взрыв
    // ------------------------------------------------------------------

    private @Nullable Explosion loadExplosion(@NotNull String id, @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("explosion");

        float power = (float) dbl(section, yaml, "power", "power", 4.0);
        if (power <= 0f) {
            plugin.getLogger().warning("[" + id + "] power должен быть больше 0 — пропускаю динамит.");
            return null;
        }
        if (power > MAX_SAFE_POWER) {
            plugin.getLogger().warning("[" + id + "] power=" + power
                    + " — это может вызывать лаги. Рекомендуется power <= " + MAX_SAFE_POWER + ".");
        }

        int fuseSeconds = integer(section, yaml, "fuse-seconds", "fuse-seconds", 4);
        int spreadTicks = integer(section, yaml, "fuse-spread-ticks", "fuse-spread-ticks", 0);

        String type = firstNonBlank(
                str(section, yaml, "type", "explosion-type", id.toUpperCase(Locale.ROOT)),
                id.toUpperCase(Locale.ROOT));

        return new Explosion(
                type,
                Math.max(0.01, dbl(section, yaml, "radius-multiplier", "radius-multiplier", 1.0)),
                power,
                bool(section, yaml, "fire", "fire", false),
                Math.max(1, fuseSeconds) * 20,
                Math.max(0, spreadTicks),
                bool(section, yaml, "works-in-water", "works-in-water", false),
                bool(section, yaml, "works-in-lava", "works-in-lava", false),
                Math.max(0, integer(section, yaml, "siege-damage", "siege-damage", 1)),
                Math.max(0, integer(section, yaml, "max-blocks", "max-blocks", 0)));
    }

    private @NotNull Damage loadDamage(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("damage");
        int cutEntity = BlastMath.clampPercent(
                integer(section, yaml, "cut-entity", "cut-entity-damage", 0));
        int cutPlayer = BlastMath.clampPercent(
                integer(section, yaml, "cut-player", "cut-player-damage", cutEntity));
        double multiplier = Math.max(0.0, dbl(section, yaml, "multiplier", "damage-multiplier", 1.0));
        return new Damage(cutEntity, cutPlayer, multiplier);
    }

    // ------------------------------------------------------------------
    // Разрушение блоков
    // ------------------------------------------------------------------

    private @NotNull Breaking loadBreaking(@NotNull String id, @NotNull YamlConfiguration yaml) {
        ConfigurationSection breaking = yaml.getConfigurationSection("breaking");
        ConfigurationSection blocks = null;

        if (breaking != null) {
            blocks = breaking.getConfigurationSection("blocks");
        } else {
            ConfigurationSection legacy = yaml.getConfigurationSection("breakable-blocks");
            if (legacy != null) {
                blocks = legacy;
                plugin.getLogger().warning("[" + id + "] ключ breakable-blocks устарел: "
                        + "перенесите правила в breaking.blocks (старый формат пока читается).");
            }
        }

        ConfigurationSection params = breaking != null
                ? breaking
                : yaml.getConfigurationSection("breakable-blocks");

        if (breaking == null && params == null) {
            return new Breaking(true, Breaking.Shape.SPHERE, DEFAULT_CUBE_SIZE,
                    true, DEFAULT_MAX_RESISTANCE, BlastMath.VANILLA_SCALE,
                    100, 0, Map.of());
        }

        double scale = params.getDouble("resistance-scale", BlastMath.VANILLA_SCALE);
        if (scale <= 0.0) scale = BlastMath.VANILLA_SCALE;

        Map<Material, BreakRule> rules = new EnumMap<>(Material.class);
        if (blocks != null) {
            for (String key : blocks.getKeys(false)) {
                Material material = Materials.parse(key);
                if (material == null) {
                    plugin.getLogger().warning("[" + id + "] неизвестный материал в правилах: " + key);
                    continue;
                }
                int breakChance = percent(blocks, key, "break-chance",
                        percent(blocks, key, "chance", 100));
                int dropChance = percent(blocks, key, "drop-chance", 100);
                rules.put(material, new BreakRule(breakChance, dropChance));
            }
        }

        return new Breaking(
                params.getBoolean("destructive", true),
                parseShape(id, params),
                Math.max(1, Math.min(MAX_CUBE_SIZE, params.getInt("cube-size", DEFAULT_CUBE_SIZE))),
                params.getBoolean("vanilla-blocklist", true),
                Math.max(0.0, params.getDouble("max-resistance", DEFAULT_MAX_RESISTANCE)),
                scale,
                BlastMath.clampPercent(params.getInt("default-drop-chance", 100)),
                Math.max(0, params.getInt("scan-radius", 0)),
                rules.isEmpty() ? Map.of() : rules);
    }

    /** Читает процент из вложенной секции: {@code MAT: {break-chance: 50}}. */
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

    private @NotNull Map<Material, TransformRule> loadTransforms(@NotNull String id,
                                                                 @NotNull YamlConfiguration yaml) {
        Map<Material, TransformRule> map = new EnumMap<>(Material.class);

        ConfigurationSection section = yaml.getConfigurationSection("transforms");
        if (section == null) {
            section = yaml.getConfigurationSection("transformable-blocks");
        }
        if (section == null) return map;

        for (String key : section.getKeys(false)) {
            // chain и chain-chance — служебные ключи самой секции, а не материалы.
            if (CHAIN_KEY.equalsIgnoreCase(key) || CHAIN_CHANCE_KEY.equalsIgnoreCase(key)) {
                continue;
            }

            Material from = Materials.parse(key);
            if (from == null) {
                plugin.getLogger().warning("[" + id + "] неизвестный материал в transforms: " + key);
                continue;
            }

            String toRaw;
            int chance = 100;
            Object raw = section.get(key);
            if (raw instanceof ConfigurationSection cs) {
                toRaw = cs.getString("to");
                chance = BlastMath.clampPercent(cs.getInt("chance", 100));
            } else {
                toRaw = section.getString(key);
            }

            Material to = Materials.parse(toRaw);
            if (to == null) {
                plugin.getLogger().warning("[" + id + "] неверный материал назначения для "
                        + key + ": " + toRaw);
                continue;
            }
            map.put(from, new TransformRule(to, chance));
        }
        return map;
    }

    /**
     * Предупредить, если материал описан дважды — и в цепочке, и отдельным
     * правилом. Побеждает цепочка, но администратор должен об этом знать:
     * иначе правило «не работает» без всяких сообщений.
     */
    private void warnOnChainConflicts(@NotNull String id,
                                      @NotNull Map<Material, TransformRule> transforms,
                                      @NotNull TransformChain chain) {
        if (!chain.isEnabled()) return;

        for (Material material : transforms.keySet()) {
            if (chain.degrades(material)) {
                plugin.getLogger().warning("[" + id + "] материал " + material
                        + " задан и в transforms.chain, и отдельным правилом: "
                        + "работает цепочка, отдельное правило пропускается");
            }
        }
    }

    /**
     * Цепочка деградации из {@code transforms.chain}.
     *
     * <p>Проверяем <b>до</b> правил ломания: иначе блок из цепочки просто
     * исчезал бы по {@code breaking.blocks} и деградация никогда бы не
     * наступала — так и было у Разрывной волны, где древние обломки числились
     * и в {@code transforms}, и в {@code breaking.blocks}.</p>
     */
    private @NotNull TransformChain loadChain(@NotNull String id, @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("transforms");
        if (section == null) return TransformChain.NONE;

        List<String> raw = section.getStringList(CHAIN_KEY);
        if (raw.isEmpty()) return TransformChain.NONE;

        List<Material> stages = new ArrayList<>(raw.size());
        for (String value : raw) {
            Material material = Materials.parse(value);
            if (material == null) {
                plugin.getLogger().warning("[" + id + "] неизвестный материал в transforms.chain: " + value);
                continue;
            }
            stages.add(material);
        }
        if (stages.size() < 2) {
            plugin.getLogger().warning("[" + id + "] transforms.chain: нужны минимум две ступени, "
                    + "цепочка отключена");
            return TransformChain.NONE;
        }

        int chance = BlastMath.clampPercent(section.getInt(CHAIN_CHANCE_KEY, 100));
        return new TransformChain(List.copyOf(stages), chance);
    }

    // ------------------------------------------------------------------
    // Рейд-блок
    // ------------------------------------------------------------------

    private @NotNull RaidBlockSettings loadRaidBlock(@NotNull String id,
                                                     @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("raid-block");
        if (section == null) return RaidBlockSettings.DISABLED;

        boolean enabled = section.getBoolean("enabled", false);
        long seconds = Math.max(0L, section.getLong("duration-seconds", 300L));
        if (!enabled || seconds <= 0L) return RaidBlockSettings.DISABLED;

        Set<Material> materials = section.isList("materials")
                ? Materials.parseAll(section.getStringList("materials"))
                : Materials.RAID_BLOCK_FAMILY;
        if (materials.isEmpty()) {
            plugin.getLogger().warning("[" + id + "] raid-block.materials пуст — "
                    + "использую семейство обсидиана.");
            materials = Materials.RAID_BLOCK_FAMILY;
        }

        return new RaidBlockSettings(true, seconds * 1000L, materials,
                Math.max(0, section.getInt("radius", 0)));
    }

    private @NotNull DynamiteType.Breaking.Shape parseShape(@NotNull String id,
                                                           @NotNull ConfigurationSection params) {
        String raw = params.getString("shape");
        if (raw == null || raw.isBlank()) return DynamiteType.Breaking.Shape.SPHERE;
        try {
            return DynamiteType.Breaking.Shape.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[" + id + "] неизвестная форма разрушения: " + raw
                    + " — использую SPHERE");
            return DynamiteType.Breaking.Shape.SPHERE;
        }
    }

    /**
     * Правила по отношению к приватам: {@code regions.only-outside} —
     * заряд не срабатывает внутри любого привата (механика Динамита Б2).
     */
    private @NotNull DynamiteType.RegionRule loadRegionRule(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("regions");
        if (section == null) return DynamiteType.RegionRule.NONE;
        return new DynamiteType.RegionRule(section.getBoolean("only-outside", false));
    }

    // ------------------------------------------------------------------
    // Фаза 2: спавнер-майнинг и временные блоки
    // ------------------------------------------------------------------

    private @NotNull DynamiteType.SpawnerMining loadSpawnerMining(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("spawner-mining");
        if (section == null || !section.getBoolean("enabled", false)) {
            return DynamiteType.SpawnerMining.DISABLED;
        }
        return new DynamiteType.SpawnerMining(
                true,
                BlastMath.clampPercent(section.getInt("chance", 25)),
                Math.max(0, section.getInt("xp", 0)),
                section.getBoolean("keep-entity-type", true));
    }

    private @NotNull DynamiteType.TemporaryBlocks loadTemporaryBlocks(@NotNull String id,
                                                                      @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("temporary-blocks");
        if (section == null || !section.getBoolean("enabled", false)) {
            return DynamiteType.TemporaryBlocks.DISABLED;
        }

        Material material = Materials.parse(section.getString("material", "ICE"));
        if (material == null || !material.isBlock()) {
            plugin.getLogger().warning("[" + id + "] неверный материал временных блоков: "
                    + section.getString("material"));
            return DynamiteType.TemporaryBlocks.DISABLED;
        }

        long seconds = Math.max(1L, section.getLong("duration-seconds", 30L));
        int radius = Math.max(1, Math.min(8, section.getInt("radius", 3)));

        Set<Material> replaceable = section.isList("replace")
                ? Materials.parseAll(section.getStringList("replace"))
                : Set.of(Material.AIR, Material.WATER, Material.LAVA, Material.CAVE_AIR);
        if (replaceable.isEmpty()) {
            replaceable = Set.of(Material.AIR, Material.WATER, Material.LAVA, Material.CAVE_AIR);
        }

        return new DynamiteType.TemporaryBlocks(
                true,
                material,
                radius,
                BlastMath.clampPercent(section.getInt("chance", 100)),
                seconds * 1000L,
                replaceable);
    }

    // ------------------------------------------------------------------
    // Ограничения
    // ------------------------------------------------------------------

    private @NotNull Limits loadLimits(@NotNull String id, @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("limits");
        if (section == null) return Limits.INHERIT;

        Settings.WorldFilter worlds = null;
        if (section.isConfigurationSection("worlds")) {
            ConfigurationSection worldsSection = section.getConfigurationSection("worlds");
            if (worldsSection != null) {
                String mode = worldsSection.getString("mode");
                if (mode != null && !mode.isBlank()
                        && !mode.equalsIgnoreCase("inherit")) {
                    worlds = Settings.WorldFilter.fromSection(worldsSection);
                }
            }
        }

        long cooldown = section.contains("cooldown-millis")
                ? section.getLong("cooldown-millis")
                : -1L;
        int perPlayer = section.contains("max-primed-per-player")
                ? section.getInt("max-primed-per-player")
                : -1;
        int perChunk = section.contains("max-primed-per-chunk")
                ? section.getInt("max-primed-per-chunk")
                : -1;

        return new Limits(cooldown, perPlayer, perChunk, worlds);
    }

    // ------------------------------------------------------------------
    // Эффекты
    // ------------------------------------------------------------------

    private @NotNull EffectsBundle loadEffects(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("effects");
        if (section == null) return EffectsBundle.NONE;

        return new EffectsBundle(
                parseEffect(section.getConfigurationSection("on-place")),
                parseEffect(section.getConfigurationSection("on-ignite")),
                parseEffect(section.getConfigurationSection("on-explode")));
    }

    /** Разбор одного эффекта (звук + частицы). Общий для динамитов и пушки. */
    public static @NotNull DynamiteEffect parseEffect(@Nullable ConfigurationSection section) {
        if (section == null) return DynamiteEffect.NONE;
        return DynamiteEffect.of(
                section.getString("sound"),
                section.getString("category", "BLOCKS"),
                (float) section.getDouble("volume", 1.0),
                (float) section.getDouble("pitch", 1.0),
                section.getString("particle"),
                section.getInt("particle-count", 0),
                section.getDouble("spread", 0.5));
    }

    // ------------------------------------------------------------------
    // Предмет, сообщения, рецепт
    // ------------------------------------------------------------------

    /**
     * Разбор секции {@code item}. Общий для динамитов и пушки: у пушки тот же
     * формат описания предмета, только файл лежит в корне папки плагина.
     */
    public static @Nullable ItemSpec parseItem(@NotNull QweTnts plugin,
                                               @NotNull String id,
                                               @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("item");

        String rawMaterial = str(section, yaml, "material", "item-material", "TNT");
        Material material = Materials.parse(rawMaterial);
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning("[" + id + "] неверный материал предмета: "
                    + rawMaterial + " — пропускаю динамит.");
            return null;
        }

        String name = firstNonBlank(
                section == null ? null : section.getString("display_name"),
                "<#F2EFFA>Динамит " + id);

        List<String> lore = sanitizeList(section == null ? null : section.getStringList("lore"));

        return new ItemSpec(
                material,
                name,
                lore,
                section != null && section.getBoolean("glow", false),
                section == null ? 0 : section.getInt("custom-model-data", 0),
                blankToNull(section == null ? null : section.getString("item-model")),
                section != null && section.getBoolean("unbreakable", false));
    }

    private @NotNull Messages loadMessages(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        if (section == null) return Messages.NONE;
        return new Messages(
                blankToNull(section.getString("placed")),
                blankToNull(section.getString("ignited")));
    }

    /** Разбор секции {@code recipe}. Общий для динамитов и пушки. */
    public static @Nullable Recipe parseRecipe(@NotNull QweTnts plugin,
                                              @NotNull String id,
                                              @NotNull YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("recipe");
        if (root == null) return null;

        List<String> shape = new ArrayList<>();
        if (root.isList("shape")) {
            for (String row : root.getStringList("shape")) {
                if (row != null && !row.isBlank()) shape.add(row);
            }
        } else {
            String raw = root.getString("shape");
            if (raw != null && !raw.isBlank()) {
                for (String row : raw.split(":")) {
                    if (!row.isBlank()) shape.add(row);
                }
            }
        }
        if (shape.isEmpty()) return null;

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        Map<Character, String> custom = new LinkedHashMap<>();

        ConfigurationSection ingSec = root.getConfigurationSection("ingredients");
        if (ingSec != null) {
            parseIngredients(plugin, ingSec, ingredients, custom);
        }
        if (ingredients.isEmpty() && custom.isEmpty()) {
            parseIngredients(plugin, root, ingredients, custom); // плоский формат
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

    private static void parseIngredients(@NotNull QweTnts plugin,
                                         @NotNull ConfigurationSection section,
                                         @NotNull Map<Character, Material> ingredients,
                                         @NotNull Map<Character, String> custom) {
        for (String key : section.getKeys(false)) {
            if ("shape".equalsIgnoreCase(key) || key.length() != 1) continue;
            char ch = key.charAt(0);
            Object raw = section.get(key);

            if (raw instanceof String value) {
                Material material = Materials.parse(value);
                String lower = value.toLowerCase(Locale.ROOT);
                if (material != null) {
                    ingredients.put(ch, material);
                } else if (lower.startsWith(TNT_PREFIX)) {
                    custom.put(ch, value.substring(TNT_PREFIX.length()));
                } else if (lower.startsWith(ComponentType.PREFIX)) {
                    custom.put(ch, value);
                }
            } else if (raw instanceof ConfigurationSection cs) {
                String kind = cs.getString("custom-type");
                String componentId = cs.getString("component");
                Material material = Materials.parse(cs.getString("material"));
                if (kind != null && !kind.isBlank()) {
                    custom.put(ch, kind.trim());
                } else if (componentId != null && !componentId.isBlank()) {
                    custom.put(ch, ComponentType.PREFIX + componentId.trim());
                } else if (material != null) {
                    ingredients.put(ch, material);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Чтение «новый ключ в секции → плоский ключ → дефолт»
    // ------------------------------------------------------------------

    private static String str(@Nullable ConfigurationSection section,
                       @NotNull YamlConfiguration root,
                       String path,
                       String legacyPath,
                       String def) {
        if (section != null && section.contains(path)) {
            String value = section.getString(path);
            return value == null ? def : value;
        }
        String legacy = root.getString(legacyPath);
        return legacy == null ? def : legacy;
    }

    private static boolean bool(@Nullable ConfigurationSection section,
                         @NotNull YamlConfiguration root,
                         String path,
                         String legacyPath,
                         boolean def) {
        if (section != null && section.contains(path)) return section.getBoolean(path, def);
        return root.getBoolean(legacyPath, def);
    }

    private static int integer(@Nullable ConfigurationSection section,
                        @NotNull YamlConfiguration root,
                        String path,
                        String legacyPath,
                        int def) {
        if (section != null && section.contains(path)) return section.getInt(path, def);
        return root.getInt(legacyPath, def);
    }

    private static long longValue(@Nullable ConfigurationSection section,
                           @NotNull YamlConfiguration root,
                           String path,
                           String legacyPath,
                           long def) {
        if (section != null && section.contains(path)) return section.getLong(path, def);
        return root.getLong(legacyPath, def);
    }

    private static double dbl(@Nullable ConfigurationSection section,
                       @NotNull YamlConfiguration root,
                       String path,
                       String legacyPath,
                       double def) {
        if (section != null && section.contains(path)) return section.getDouble(path, def);
        return root.getDouble(legacyPath, def);
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

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
