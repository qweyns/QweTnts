package ru.qweyns.qwetnts.cannon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.DynamiteLoader;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Materials;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Читает {@code cannon.yml} из корня папки плагина.
 *
 * <p>Логика та же, что у {@link DynamiteLoader}: любой мусор в конфиге не
 * роняет плагин, а критичная ошибка (нет файла, {@code enabled: false},
 * негодный материал предмета) просто выключает пушку.</p>
 *
 * <p>Формат предмета и рецепта переиспользует парсеры {@link DynamiteLoader},
 * чтобы описание предмета было одинаковым и у динамитов, и у пушки.</p>
 */
public final class CannonLoader {

    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 60.0;
    private static final double MAX_RANGE = 512.0;
    private static final long MAX_DELAY = 100L;

    private CannonLoader() {
    }

    /**
     * Читает файл пушки.
     *
     * @return настройки или {@link CannonSettings#DISABLED}, если пушка
     *         выключена или файл не читается
     */
    public static @NotNull CannonSettings load(@NotNull QweTnts plugin, @Nullable File file) {
        if (file == null || !file.isFile()) {
            plugin.getLogger().warning("Файл cannon.yml не найден — пушка отключена.");
            return CannonSettings.DISABLED;
        }

        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось прочитать cannon.yml", ex);
            return CannonSettings.DISABLED;
        }

        if (!yaml.getBoolean("enabled", true)) {
            plugin.getLogger().info("Тнт-пушка отключена (enabled: false).");
            return CannonSettings.DISABLED;
        }

        DynamiteType.ItemSpec item = DynamiteLoader.parseItem(plugin, "cannon", yaml);
        if (item == null) return CannonSettings.DISABLED;

        boolean craftable = yaml.getBoolean("craftable", false);

        return new CannonSettings(
                true,
                blankToNull(yaml.getString("permission"), "qwetnts.cannon"),
                item,
                craftable ? DynamiteLoader.parseRecipe(plugin, "cannon", yaml) : null,
                craftable,
                loadMenu(plugin, yaml),
                loadLaunch(yaml),
                loadAmmo(plugin, yaml),
                loadWorlds(plugin, yaml),
                loadShotEffect(yaml));
    }

    // ------------------------------------------------------------------
    // Секции
    // ------------------------------------------------------------------

    private static @NotNull CannonSettings.Menu loadMenu(@NotNull QweTnts plugin,
                                                        @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("menu");
        if (section == null) return CannonSettings.Menu.DEFAULT;

        String raw = section.getString("border-material", "RED_STAINED_GLASS_PANE");
        Material border = Materials.parse(raw);
        if (border == null || !border.isItem()) {
            plugin.getLogger().warning("[cannon] неизвестный материал рамки: "
                    + raw + " — беру RED_STAINED_GLASS_PANE.");
            border = Material.RED_STAINED_GLASS_PANE;
        }

        return new CannonSettings.Menu(
                section.getString("title", CannonSettings.Menu.DEFAULT.title()),
                border,
                section.getString("border-name", " "));
    }

    private static @NotNull CannonSettings.Launch loadLaunch(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("launch");
        if (section == null) return CannonSettings.Launch.DEFAULT;

        return new CannonSettings.Launch(
                clamp(section.getDouble("speed-blocks-per-second", 5.0), MIN_SPEED, MAX_SPEED),
                clamp(section.getDouble("max-range-blocks", 20.0), 0.0, MAX_RANGE),
                clamp(section.getDouble("upward", 0.0), -5.0, 5.0),
                section.getBoolean("gravity", true),
                Math.max(0L, Math.min(MAX_DELAY, section.getLong("delay-ticks", 4L))));
    }

    private static @NotNull CannonSettings.Ammunition loadAmmo(@NotNull QweTnts plugin,
                                                               @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("ammunition");
        if (section == null) return CannonSettings.Ammunition.DEFAULT;

        CannonSettings.Ammunition.Mode mode = CannonSettings.Ammunition.Mode.BLACKLIST;
        String raw = section.getString("mode", "BLACKLIST");
        if (raw != null) {
            try {
                mode = CannonSettings.Ammunition.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[cannon] неизвестный режим боеприпасов: "
                        + raw + " — беру BLACKLIST.");
            }
        }

        Set<String> kinds = new LinkedHashSet<>();
        for (String value : section.getStringList("dynamites")) {
            if (value != null && !value.isBlank()) {
                kinds.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new CannonSettings.Ammunition(mode, Set.copyOf(kinds));
    }

    private static @Nullable Settings.WorldFilter loadWorlds(@NotNull QweTnts plugin,
                                                             @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section == null) return null;

        String mode = section.getString("mode");
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("inherit")) return null;

        try {
            return Settings.WorldFilter.fromSection(section);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[cannon] не читается фильтр миров: " + ex.getMessage());
            return null;
        }
    }

    private static @NotNull DynamiteEffect loadShotEffect(@NotNull YamlConfiguration yaml) {
        ConfigurationSection effects = yaml.getConfigurationSection("effects");
        if (effects == null) return DynamiteEffect.NONE;
        return DynamiteLoader.parseEffect(effects.getConfigurationSection("on-shot"));
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static @NotNull String blankToNull(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
