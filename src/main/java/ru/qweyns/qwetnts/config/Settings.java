package ru.qweyns.qwetnts.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import ru.qweyns.qwetnts.QweTnts;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Читает и валидирует общие настройки из {@code config.yml}:
 * языковые файлы, антилаг-лимиты, ограничения по мирам, кулдауны, автосохранение.
 */
public final class Settings {

    public final String language;
    public final int raidBlockCleanupIntervalTicks;
    public final long raidBlockAutoSaveIntervalTicks;

    // Антилаг (§7 ТЗ)
    public final int maxPrimedPerPlayer;
    public final int maxPrimedPerChunk;
    public final long activationCooldownMs;
    public final int largeExplosionThreshold;      // power > этого — ставим в очередь
    public final int largeExplosionBlocksPerTick;
    public final long largeExplosionTickPeriod;

    // Ограничения по мирам и спавну
    public final WorldFilter worldFilter;
    public final int spawnRadius;

    // Рейт-лимит алертов владельцам приватов
    public final long alertCooldownMs;

    // bStats
    public final boolean bstatsEnabled;

    private Settings(QweTnts plugin) {
        FileConfiguration cfg = plugin.getConfig();
        addDefaults(cfg);
        cfg.options().copyDefaults(true);
        plugin.saveConfig();

        language = cfg.getString("settings.language", "ru");
        raidBlockCleanupIntervalTicks = Math.max(60, cfg.getInt(
                "settings.raid-block-cleanup-interval-ticks", 600));
        raidBlockAutoSaveIntervalTicks = Math.max(20 * 30, cfg.getInt(
                "settings.raid-block-autosave-interval-ticks", 20 * 60 * 2));

        maxPrimedPerPlayer = Math.max(0, cfg.getInt(
                "settings.anti-lag.max-primed-per-player", 32));
        maxPrimedPerChunk = Math.max(0, cfg.getInt(
                "settings.anti-lag.max-primed-per-chunk", 64));
        activationCooldownMs = Math.max(0, cfg.getLong(
                "settings.anti-lag.activation-cooldown-millis", 250));
        largeExplosionThreshold = Math.max(8, cfg.getInt(
                "settings.anti-lag.large-explosion-threshold-power", 16));
        largeExplosionBlocksPerTick = Math.max(32, cfg.getInt(
                "settings.anti-lag.large-explosion-blocks-per-tick", 512));
        largeExplosionTickPeriod = Math.max(1, cfg.getLong(
                "settings.anti-lag.large-explosion-tick-period", 1));

        worldFilter = WorldFilter.fromSection(cfg.getConfigurationSection("settings.worlds"));
        spawnRadius = Math.max(0, cfg.getInt("settings.worlds.spawn-radius", 0));

        alertCooldownMs = Math.max(0, cfg.getLong(
                "settings.alerts.attack-alert-cooldown-millis", 30_000));

        bstatsEnabled = cfg.getBoolean("settings.bstats-enabled", true);
    }

    public static Settings load(QweTnts plugin) {
        return new Settings(plugin);
    }

    private void addDefaults(FileConfiguration cfg) {
        cfg.addDefault("settings.language", "ru");
        cfg.addDefault("settings.raid-block-cleanup-interval-ticks", 600);
        cfg.addDefault("settings.raid-block-autosave-interval-ticks", 2400);

        cfg.addDefault("settings.anti-lag.max-primed-per-player", 32);
        cfg.addDefault("settings.anti-lag.max-primed-per-chunk", 64);
        cfg.addDefault("settings.anti-lag.activation-cooldown-millis", 250);
        cfg.addDefault("settings.anti-lag.large-explosion-threshold-power", 16);
        cfg.addDefault("settings.anti-lag.large-explosion-blocks-per-tick", 512);
        cfg.addDefault("settings.anti-lag.large-explosion-tick-period", 1);

        cfg.addDefault("settings.worlds.mode", "BLOCKED");
        cfg.addDefault("settings.worlds.allowed-worlds", Collections.emptyList());
        cfg.addDefault("settings.worlds.blocked-worlds", Collections.emptyList());
        cfg.addDefault("settings.worlds.spawn-radius", 0);

        cfg.addDefault("settings.alerts.attack-alert-cooldown-millis", 30_000);

        cfg.addDefault("settings.bstats-enabled", true);
    }

    public enum WorldFilterMode {
        /** Все миры разрешены, кроме перечисленных в blocked-worlds. */
        BLOCKED,
        /** Только миры из allowed-worlds разрешены. */
        ALLOWED
    }

    public record WorldFilter(WorldFilterMode mode,
                              Set<String> allowed,
                              Set<String> blocked) {
        public boolean isAllowed(@NotNull World world) {
            String name = world.getName().toLowerCase(Locale.ROOT);
            return switch (mode) {
                case ALLOWED -> allowed.contains(name);
                case BLOCKED -> !blocked.contains(name);
            };
        }

        static WorldFilter fromSection(ConfigurationSection s) {
            WorldFilterMode mode = WorldFilterMode.BLOCKED;
            Set<String> allowed = new HashSet<>();
            Set<String> blocked = new HashSet<>();
            if (s != null) {
                try {
                    mode = WorldFilterMode.valueOf(
                            s.getString("mode", "BLOCKED").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {}
                for (String w : s.getStringList("allowed-worlds")) {
                    allowed.add(w.toLowerCase(Locale.ROOT));
                }
                for (String w : s.getStringList("blocked-worlds")) {
                    blocked.add(w.toLowerCase(Locale.ROOT));
                }
            }
            return new WorldFilter(mode,
                    Collections.unmodifiableSet(allowed),
                    Collections.unmodifiableSet(blocked));
        }
    }
}
