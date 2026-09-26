package ru.qweyns.qwetnts.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Настройки голограммы над горящим динамитом.
 *
 * <p>Устроены так же, как {@code hologram_settings} в QweProtectStones: те же
 * ключи, те же значения, тот же смысл. Отличие в том, что у QPS голограмма
 * постоянная и висит над ядром привата, а у нас — временная, живёт пока
 * горит фитиль, поэтому здесь появились {@code follow-projectile}
 * (ехать за зарядом) и {@code max-active} (предел числа голограмм: сотня
 * горящих зарядов не должна порождать сотню сущностей).</p>
 *
 * <p>Общие настройки лежат в {@code config.yml}, а любой динамит может
 * переопределить их своей секцией {@code hologram:} — см.
 * {@link #merge}.</p>
 */
public record HologramSettings(boolean enabled,
                               @NotNull Provider provider,
                               int maxActive,
                               boolean follow,
                               long updateIntervalTicks,
                               double offset,
                               double displayRange,
                               boolean seeThrough,
                               boolean shadow,
                               float scale,
                               @NotNull String billboard,
                               @NotNull String alignment,
                               @NotNull String background,
                               @NotNull String permission,
                               @NotNull List<String> lines) {

    /** Какой плагин рисует голограмму. */
    public enum Provider {
        /** FancyHolograms, иначе DecentHolograms, иначе встроенные. */
        AUTO,
        FANCY,
        DECENT,
        /** Встроенные голограммы на TextDisplay: сторонние плагины не нужны. */
        NATIVE;

        static @NotNull Provider parse(@Nullable String raw, @NotNull Provider fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }

    public static final HologramSettings DEFAULT = new HologramSettings(
            true,
            Provider.AUTO,
            48,
            true,
            5L,
            1.1,
            24.0,
            false,
            true,
            1.0f,
            "CENTER",
            "CENTER",
            "",
            "",
            List.of("<#FDE68A>⚡ <#F2EFFA>%seconds% с", "<#A79FC0>%name%"));

    /** Значения, которые имеет смысл ограничивать: защита от опечаток в конфиге. */
    private static final int MAX_ACTIVE_LIMIT = 512;
    private static final double MIN_OFFSET = -2.0;
    private static final double MAX_OFFSET = 8.0;

    /**
     * Прочитать готовые настройки из секции: чего там нет — берётся из
     * {@code defaults}. Так читается {@code holograms} в {@code config.yml}.
     */
    public static @NotNull HologramSettings from(@Nullable ConfigurationSection section,
                                                 @NotNull HologramSettings defaults) {
        if (section == null) return defaults;
        return merge(Raw.from(section), defaults);
    }

    /**
     * «Сырые» настройки из файла динамита: только то, что там реально
     * написано. Незаданные ключи — {@code null}, и в {@link #merge} на их
     * место подставятся общие значения из {@code config.yml}.
     *
     * <p>По-другому нельзя: держи в динамите готовые настройки — и они
     * «заморозят» общие значения на момент загрузки, так что правка
     * {@code config.yml} перестанет влиять на те динамиты, где ключ не
     * задан явно.</p>
     */
    public record Raw(@Nullable Boolean enabled,
                      @Nullable Provider provider,
                      @Nullable Integer maxActive,
                      @Nullable Boolean follow,
                      @Nullable Long updateIntervalTicks,
                      @Nullable Double offset,
                      @Nullable Double displayRange,
                      @Nullable Boolean seeThrough,
                      @Nullable Boolean shadow,
                      @Nullable Float scale,
                      @Nullable String billboard,
                      @Nullable String alignment,
                      @Nullable String background,
                      @Nullable String permission,
                      @Nullable List<String> lines) {

        private static final Raw EMPTY = new Raw(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        public static @NotNull Raw from(@Nullable ConfigurationSection section) {
            if (section == null) return EMPTY;

            ConfigurationSection view = section.getConfigurationSection("settings");
            boolean hasView = view != null;

            return new Raw(
                    section.contains("enabled") ? section.getBoolean("enabled") : null,
                    section.contains("provider")
                            ? Provider.parse(section.getString("provider"), Provider.AUTO) : null,
                    section.contains("max-active")
                            ? clampInt(section.getInt("max-active"), 0, MAX_ACTIVE_LIMIT) : null,
                    section.contains("follow-projectile")
                            ? section.getBoolean("follow-projectile") : null,
                    section.contains("update-interval-ticks")
                            ? Math.max(1L, section.getLong("update-interval-ticks")) : null,
                    section.contains("offset")
                            ? clampDouble(section.getDouble("offset"), MIN_OFFSET, MAX_OFFSET) : null,
                    section.contains("display-range")
                            ? Math.max(1.0, section.getDouble("display-range")) : null,
                    hasView && view.contains("see-through") ? view.getBoolean("see-through") : null,
                    hasView && view.contains("shadow") ? view.getBoolean("shadow") : null,
                    hasView && view.contains("scale")
                            ? (float) Math.max(0.1, Math.min(8.0, view.getDouble("scale"))) : null,
                    hasView ? blankToNull(view.getString("billboard")) : null,
                    hasView ? blankToNull(view.getString("text-alignment")) : null,
                    hasView ? view.getString("background") : null,
                    hasView ? view.getString("permission") : null,
                    section.contains("lines") ? readLines(section, List.of()) : null);
        }
    }

    /** Сырые настройки динамита поверх общих из {@code config.yml}. */
    public static @NotNull HologramSettings merge(@Nullable Raw override,
                                                 @NotNull HologramSettings base) {
        if (override == null) return base;
        return new HologramSettings(
                or(override.enabled(), base.enabled()),
                override.provider() != null ? override.provider() : base.provider(),
                or(override.maxActive(), base.maxActive()),
                or(override.follow(), base.follow()),
                or(override.updateIntervalTicks(), base.updateIntervalTicks()),
                or(override.offset(), base.offset()),
                or(override.displayRange(), base.displayRange()),
                or(override.seeThrough(), base.seeThrough()),
                or(override.shadow(), base.shadow()),
                or(override.scale(), base.scale()),
                notBlank(override.billboard(), base.billboard()),
                notBlank(override.alignment(), base.alignment()),
                override.background() != null ? override.background() : base.background(),
                override.permission() != null ? override.permission() : base.permission(),
                override.lines() == null || override.lines().isEmpty() ? base.lines() : override.lines());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static @NotNull String notBlank(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean or(@Nullable Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    private static int or(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static long or(@Nullable Long value, long fallback) {
        return value != null ? value : fallback;
    }

    private static double or(@Nullable Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private static float or(@Nullable Float value, float fallback) {
        return value != null ? value : fallback;
    }

    private static @NotNull List<String> readLines(@NotNull ConfigurationSection section,
                                                   @NotNull List<String> fallback) {
        List<String> raw = section.getStringList("lines");
        if (raw.isEmpty()) return fallback;

        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line != null) lines.add(line);
        }
        return lines.isEmpty() ? fallback : List.copyOf(lines);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
