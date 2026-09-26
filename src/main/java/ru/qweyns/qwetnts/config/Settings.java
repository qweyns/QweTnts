package ru.qweyns.qwetnts.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.hologram.HologramSettings;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки {@code config.yml}.
 *
 * <p>Каждое значение проходит валидацию: отрицательные лимиты, пустые языки
 * и некорректные режимы не приводят к падению — подставляется дефолт,
 * а в лог пишется предупреждение.</p>
 */
public final class Settings {

    private final QweTnts plugin;

    private final String language;
    private final boolean bstatsEnabled;

    private final Dynamites dynamites;
    private final AntiLag antiLag;
    private final RaidBlocks raidBlocks;
    private final PlacedDynamites placedDynamites;
    private final TemporaryBlocks temporaryBlocks;
    private final WorldFilter worldFilter;
    private final int spawnRadius;
    private final Alerts alerts;
    private final HologramSettings holograms;

    private Settings(@NotNull QweTnts plugin) {
        this.plugin = plugin;

        FileConfiguration cfg = plugin.getConfig();
        addDefaults(cfg);
        cfg.options().copyDefaults(true);

        this.language = str(cfg, "settings.language", "ru_RU");
        this.bstatsEnabled = cfg.getBoolean("settings.bstats-enabled", true);

        this.dynamites = Dynamites.from(cfg.getConfigurationSection("settings.dynamites"));
        this.antiLag = AntiLag.from(cfg.getConfigurationSection("settings.anti-lag"));
        this.raidBlocks = RaidBlocks.from(cfg.getConfigurationSection("settings.raid-blocks"));
        this.placedDynamites = PlacedDynamites.from(cfg.getConfigurationSection("settings.placed-dynamites"));
        this.temporaryBlocks = TemporaryBlocks.from(cfg.getConfigurationSection("settings.temporary-blocks"));
        this.worldFilter = WorldFilter.fromSection(cfg.getConfigurationSection("settings.worlds"));
        this.spawnRadius = Math.max(0, cfg.getInt("settings.worlds.spawn-radius", 0));
        this.alerts = Alerts.from(plugin, cfg.getConfigurationSection("settings.alerts"));
        this.holograms = HologramSettings.from(cfg.getConfigurationSection("holograms"),
                HologramSettings.DEFAULT);

        plugin.saveConfig();
    }

    public static @NotNull Settings load(@NotNull QweTnts plugin) {
        return new Settings(plugin);
    }

    // ------------------------------------------------------------------
    // Аксессоры
    // ------------------------------------------------------------------

    public @NotNull String language() { return language; }
    public boolean bstatsEnabled() { return bstatsEnabled; }
    public @NotNull Dynamites dynamites() { return dynamites; }
    public @NotNull AntiLag antiLag() { return antiLag; }
    public @NotNull RaidBlocks raidBlocks() { return raidBlocks; }
    public @NotNull PlacedDynamites placedDynamites() { return placedDynamites; }
    public @NotNull TemporaryBlocks temporaryBlocks() { return temporaryBlocks; }
    public @NotNull WorldFilter worldFilter() { return worldFilter; }
    public int spawnRadius() { return spawnRadius; }
    public @NotNull Alerts alerts() { return alerts; }
    public @NotNull HologramSettings holograms() { return holograms; }

    // ------------------------------------------------------------------
    // Вложенные группы настроек
    // ------------------------------------------------------------------

    /** Алерты: рейд-уведомления игрокам и в Discord. */
    public record Alerts(long attackAlertCooldownMillis, @NotNull Discord discord) {

        private static final Alerts DEFAULT =
                new Alerts(30_000L, Discord.DISABLED);

        static @NotNull Alerts from(@NotNull QweTnts plugin, @Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new Alerts(
                    Math.max(0L, s.getLong("attack-alert-cooldown-millis",
                            DEFAULT.attackAlertCooldownMillis())),
                    Discord.from(plugin, s.getConfigurationSection("discord")));
        }
    }

    /**
     * Discord-вебхук (Фаза 2): короткие алерты о важных событиях рейда.
     *
     * <p>Отправка идёт асинхронно и никогда не блокирует игровой поток.
     * URL проверяется: только HTTPS и только известные хосты Discord —
     * иначе конфиг можно было бы использовать для запросов куда угодно.</p>
     */
    public record Discord(boolean enabled,
                          @NotNull String webhookUrl,
                          @NotNull String username,
                          @NotNull String avatarUrl,
                          @NotNull String mentionRoleId,
                          long timeoutMillis,
                          @NotNull Set<DiscordEvent> events) {

        /** События, которые можно отправлять в Discord. */
        public enum DiscordEvent {
            /** Приват уничтожен рейдом. */
            REGION_DESTROYED,
            /** Приват атакуют: первый удар в серии (с кулдауном алертов). */
            REGION_UNDER_ATTACK,
            /** Пробита стена бункера (механика замка HolyWorld). */
            BUNKER_BREACHED
        }

        public static final Discord DISABLED = new Discord(false, "", "QweTnts", "", "",
                5_000L, Set.of());

        /** Разрешённые хосты вебхуков (защита от подмены URL). */
        private static final Set<String> ALLOWED_HOSTS = Set.of(
                "discord.com", "discordapp.com", "canary.discord.com",
                "ptb.discord.com", "media.discordapp.net");

        static @NotNull Discord from(@NotNull QweTnts plugin, @Nullable ConfigurationSection s) {
            if (s == null || !s.getBoolean("enabled", false)) return DISABLED;

            String rawUrl = s.getString("webhook-url", "");
            String url = rawUrl == null ? "" : rawUrl.trim();
            if (url.isEmpty()) {
                plugin.getLogger().warning("settings.alerts.discord.enabled=true, "
                        + "но webhook-url пуст — Discord-алерты отключены.");
                return DISABLED;
            }
            if (!isAllowedUrl(url)) {
                plugin.getLogger().warning("Некорректный webhook-url для Discord: "
                        + "нужен https и один из хостов " + ALLOWED_HOSTS);
                return DISABLED;
            }

            Set<DiscordEvent> events = new HashSet<>();
            for (String raw : s.getStringList("events")) {
                if (raw == null || raw.isBlank()) continue;
                try {
                    events.add(DiscordEvent.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Неизвестное событие Discord: " + raw);
                }
            }
            if (events.isEmpty()) events.add(DiscordEvent.REGION_DESTROYED);

            return new Discord(
                    true,
                    url,
                    s.getString("username", DISABLED.username()),
                    s.getString("avatar-url", DISABLED.avatarUrl()),
                    s.getString("mention-role-id", DISABLED.mentionRoleId()),
                    Math.max(500L, Math.min(30_000L, s.getLong("timeout-millis",
                            DISABLED.timeoutMillis()))),
                    Collections.unmodifiableSet(events));
        }

        static boolean isAllowedUrl(@NotNull String url) {
            java.net.URI uri;
            try {
                uri = java.net.URI.create(url);
            } catch (IllegalArgumentException ex) {
                return false;
            }
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return ALLOWED_HOSTS.contains(host) || host.endsWith(".discord.com")
                    || host.endsWith(".discordapp.com");
        }

        public boolean wants(@NotNull DiscordEvent event) {
            return enabled && events.contains(event);
        }
    }

    /** Поведение динамитов: как они поджигаются, цепная детонация, расход. */
    public record Dynamites(boolean autoIgnite,
                            boolean consumeOnUse,
                            int chainRadius,
                            long chainDelayTicks,
                            boolean punchIgnites,
                            int damageSourceRadiusBlocks) {

        private static final Dynamites DEFAULT = new Dynamites(false, true, 4, 2L, false, 6);

        static @NotNull Dynamites from(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new Dynamites(
                    s.getBoolean("auto-ignite", DEFAULT.autoIgnite()),
                    s.getBoolean("consume-on-use", DEFAULT.consumeOnUse()),
                    Math.max(0, Math.min(16, s.getInt("chain-radius", DEFAULT.chainRadius()))),
                    Math.max(1L, s.getLong("chain-delay-ticks", DEFAULT.chainDelayTicks())),
                    s.getBoolean("punch-ignites", DEFAULT.punchIgnites()),
                    Math.max(1, Math.min(16, s.getInt("damage-source-radius-blocks",
                            DEFAULT.damageSourceRadiusBlocks()))));
        }
    }

    /** Анти-лаг: лимиты, кулдауны, предохранители от лаг-машин. */
    public record AntiLag(int maxPrimedPerPlayer,
                          int maxPrimedPerChunk,
                          long activationCooldownMillis,
                          long messageCooldownMillis,
                          int largeExplosionThresholdPower,
                          int maxBreakScanRadius,
                          int largeExplosionBlocksPerTick,
                          long largeExplosionTickPeriod,
                          int maxPendingPerExplosion) {

        private static final AntiLag DEFAULT =
                new AntiLag(32, 64, 250L, 1_000L, 16, 8, 512, 1L, 4096);

        static @NotNull AntiLag from(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new AntiLag(
                    Math.max(0, s.getInt("max-primed-per-player", DEFAULT.maxPrimedPerPlayer())),
                    Math.max(0, s.getInt("max-primed-per-chunk", DEFAULT.maxPrimedPerChunk())),
                    Math.max(0L, s.getLong("activation-cooldown-millis", DEFAULT.activationCooldownMillis())),
                    Math.max(0L, s.getLong("message-cooldown-millis", DEFAULT.messageCooldownMillis())),
                    Math.max(4, s.getInt("large-explosion-threshold-power", DEFAULT.largeExplosionThresholdPower())),
                    Math.max(1, Math.min(16, s.getInt("max-break-scan-radius", DEFAULT.maxBreakScanRadius()))),
                    Math.max(32, s.getInt("large-explosion-blocks-per-tick", DEFAULT.largeExplosionBlocksPerTick())),
                    Math.max(1L, s.getLong("large-explosion-tick-period", DEFAULT.largeExplosionTickPeriod())),
                    Math.max(0, s.getInt("max-pending-per-explosion", DEFAULT.maxPendingPerExplosion())));
        }
    }

    /** Рейд-блоки: частота чистки и автосохранения. */
    public record RaidBlocks(long cleanupIntervalTicks, long autosaveIntervalTicks) {

        private static final RaidBlocks DEFAULT = new RaidBlocks(600L, 2400L);

        static @NotNull RaidBlocks from(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new RaidBlocks(
                    Math.max(20L, s.getLong("cleanup-interval-ticks", DEFAULT.cleanupIntervalTicks())),
                    Math.max(20L, s.getLong("autosave-interval-ticks", DEFAULT.autosaveIntervalTicks())));
        }
    }

    /** Временные блоки (лёд после взрыва и т.п.). */
    public record TemporaryBlocks(long cleanupIntervalTicks, long autosaveIntervalTicks) {

        private static final TemporaryBlocks DEFAULT = new TemporaryBlocks(100L, 1200L);

        static @NotNull TemporaryBlocks from(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new TemporaryBlocks(
                    Math.max(10L, s.getLong("cleanup-interval-ticks", DEFAULT.cleanupIntervalTicks())),
                    Math.max(20L, s.getLong("autosave-interval-ticks", DEFAULT.autosaveIntervalTicks())));
        }
    }

    /** Установленные (ещё не подожжённые) динамиты. */
    public record PlacedDynamites(long autosaveIntervalTicks, long cleanupIntervalTicks) {

        private static final PlacedDynamites DEFAULT = new PlacedDynamites(2400L, 1200L);

        static @NotNull PlacedDynamites from(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;
            return new PlacedDynamites(
                    Math.max(20L, s.getLong("autosave-interval-ticks", DEFAULT.autosaveIntervalTicks())),
                    Math.max(20L, s.getLong("cleanup-interval-ticks", DEFAULT.cleanupIntervalTicks())));
        }
    }

    // ------------------------------------------------------------------
    // Фильтр миров
    // ------------------------------------------------------------------

    public enum WorldFilterMode {
        /** Разрешены все миры, кроме перечисленных в blocked-worlds. */
        BLOCKED,
        /** Разрешены только миры из allowed-worlds. */
        ALLOWED
    }

    public record WorldFilter(WorldFilterMode mode, Set<String> allowed, Set<String> blocked) {

        private static final WorldFilter DEFAULT =
                new WorldFilter(WorldFilterMode.BLOCKED, Set.of(), Set.of());

        public boolean isAllowed(@Nullable World world) {
            if (world == null) return false;
            String name = world.getName().toLowerCase(Locale.ROOT);
            return switch (mode) {
                case ALLOWED -> allowed.contains(name);
                case BLOCKED -> !blocked.contains(name);
            };
        }

        public static @NotNull WorldFilter fromSection(@Nullable ConfigurationSection s) {
            if (s == null) return DEFAULT;

            WorldFilterMode mode = DEFAULT.mode();
            String raw = s.getString("mode");
            if (raw != null && !raw.isBlank()) {
                try {
                    mode = WorldFilterMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    mode = WorldFilterMode.BLOCKED;
                }
            }

            // В config.yml ключи полные, в файлах динамитов допустимы
            // короткие синонимы allowed/blocked.
            Set<String> allowed = lower(list(s, "allowed-worlds", "allowed"));
            Set<String> blocked = lower(list(s, "blocked-worlds", "blocked"));
            return new WorldFilter(mode, allowed, blocked);
        }

        private static @NotNull List<String> list(@NotNull ConfigurationSection section,
                                                  @NotNull String primary,
                                                  @NotNull String alias) {
            if (section.isList(primary)) return section.getStringList(primary);
            if (section.isList(alias)) return section.getStringList(alias);
            return List.of();
        }

        private static Set<String> lower(List<String> source) {
            Set<String> out = new HashSet<>();
            if (source == null) return out;
            for (String value : source) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
            return Collections.unmodifiableSet(out);
        }
    }

    // ------------------------------------------------------------------
    // Дефолты конфига
    // ------------------------------------------------------------------

    private void addDefaults(FileConfiguration cfg) {
        cfg.addDefault("settings.language", "ru_RU");
        cfg.addDefault("settings.bstats-enabled", true);

        cfg.addDefault("settings.dynamites.auto-ignite", false);
        cfg.addDefault("settings.dynamites.consume-on-use", true);
        cfg.addDefault("settings.dynamites.chain-radius", 4);
        cfg.addDefault("settings.dynamites.damage-source-radius-blocks", 6);
        cfg.addDefault("settings.dynamites.chain-delay-ticks", 2);
        cfg.addDefault("settings.dynamites.punch-ignites", false);

        cfg.addDefault("settings.anti-lag.max-primed-per-player", 32);
        cfg.addDefault("settings.anti-lag.max-primed-per-chunk", 64);
        cfg.addDefault("settings.anti-lag.activation-cooldown-millis", 250);
        cfg.addDefault("settings.anti-lag.message-cooldown-millis", 1000);
        cfg.addDefault("settings.anti-lag.large-explosion-threshold-power", 16);
        cfg.addDefault("settings.anti-lag.max-break-scan-radius", 8);
        cfg.addDefault("settings.anti-lag.large-explosion-blocks-per-tick", 512);
        cfg.addDefault("settings.anti-lag.large-explosion-tick-period", 1);
        cfg.addDefault("settings.anti-lag.max-pending-per-explosion", 4096);

        cfg.addDefault("settings.raid-blocks.cleanup-interval-ticks", 600);
        cfg.addDefault("settings.raid-blocks.autosave-interval-ticks", 2400);

        cfg.addDefault("settings.temporary-blocks.cleanup-interval-ticks", 100);
        cfg.addDefault("settings.temporary-blocks.autosave-interval-ticks", 1200);

        cfg.addDefault("settings.placed-dynamites.autosave-interval-ticks", 2400);
        cfg.addDefault("settings.placed-dynamites.cleanup-interval-ticks", 1200);

        cfg.addDefault("settings.worlds.mode", "BLOCKED");
        cfg.addDefault("settings.worlds.allowed-worlds", Collections.emptyList());
        cfg.addDefault("settings.worlds.blocked-worlds", Collections.emptyList());
        cfg.addDefault("settings.worlds.spawn-radius", 0);

        cfg.addDefault("settings.alerts.attack-alert-cooldown-millis", 30_000);
        cfg.addDefault("settings.alerts.discord.enabled", false);
        cfg.addDefault("settings.alerts.discord.webhook-url", "");
        cfg.addDefault("settings.alerts.discord.username", "QweTnts");
        cfg.addDefault("settings.alerts.discord.avatar-url", "");
        cfg.addDefault("settings.alerts.discord.mention-role-id", "");
        cfg.addDefault("settings.alerts.discord.timeout-millis", 5000);
        cfg.addDefault("settings.alerts.discord.events",
                List.of(Discord.DiscordEvent.REGION_DESTROYED.name()));

        // Голограммы над горящими зарядами. Дефолты нужны не для красоты:
        // без addDefault секция не попадёт в уже существующий config.yml,
        // и у администратора, обновившего плагин, holograms: просто не
        // появится — настраивать будет нечего.
        HologramSettings def = HologramSettings.DEFAULT;
        cfg.addDefault("holograms.enabled", def.enabled());
        cfg.addDefault("holograms.provider", def.provider().name());
        cfg.addDefault("holograms.max-active", def.maxActive());
        cfg.addDefault("holograms.follow-projectile", def.follow());
        cfg.addDefault("holograms.update-interval-ticks", def.updateIntervalTicks());
        cfg.addDefault("holograms.offset", def.offset());
        cfg.addDefault("holograms.display-range", def.displayRange());
        cfg.addDefault("holograms.lines", List.copyOf(def.lines()));
        cfg.addDefault("holograms.settings.see-through", def.seeThrough());
        cfg.addDefault("holograms.settings.shadow", def.shadow());
        cfg.addDefault("holograms.settings.scale", def.scale());
        cfg.addDefault("holograms.settings.billboard", def.billboard());
        cfg.addDefault("holograms.settings.text-alignment", def.alignment());
        cfg.addDefault("holograms.settings.background", def.background());
        cfg.addDefault("holograms.settings.permission", def.permission());
    }

    private String str(FileConfiguration cfg, String path, String def) {
        String value = cfg.getString(path);
        return value == null || value.isBlank() ? def : value.trim();
    }
}
