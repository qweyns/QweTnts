package ru.qweyns.qwetnts;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qweyns.qweprotectstones.api.QpsApi;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.command.QweTntsCommand;
import ru.qweyns.qwetnts.config.DynamiteLoader;
import ru.qweyns.qwetnts.config.Lang;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteRegistry;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.dynamite.PrimingService;
import ru.qweyns.qwetnts.integration.QpsBridge;
import ru.qweyns.qwetnts.listener.CustomRecipeListener;
import ru.qweyns.qwetnts.listener.DynamiteExplodeListener;
import ru.qweyns.qwetnts.listener.DynamiteIgniteListener;
import ru.qweyns.qwetnts.listener.DynamitePlaceListener;
import ru.qweyns.qwetnts.listener.DynamiteUseListener;
import ru.qweyns.qwetnts.listener.RaidBlockListener;
import ru.qweyns.qwetnts.listener.RaidLoggingListener;
import ru.qweyns.qwetnts.listener.RegionDynamiteListener;
import ru.qweyns.qwetnts.metrics.QweTntsMetrics;
import ru.qweyns.qwetnts.raid.AlertThrottle;
import ru.qweyns.qwetnts.raid.RaidBlockManager;
import ru.qweyns.qwetnts.stats.ExplosionStats;
import ru.qweyns.qwetnts.util.Schedulers;
import ru.qweyns.qwetnts.util.ThreadPools;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Плагин-аддон для QweProtectStones: кастомные динамиты в стиле HolyWorld Lite.
 *
 * <p>Жизненный цикл построен так, чтобы ни одна ошибка конфигурации не
 * приводила к падению сервера: битый файл динамита пропускается, отсутствующий
 * ключ языка не печатается в чат, а сбой интеграции с QPS трактуется как
 * «приватов нет».</p>
 */
public final class QweTnts extends JavaPlugin {

    private static final String DYNAMITES_DIR = "dynamites";
    private static final List<String> BUILTIN_DYNAMITES = List.of(
            "dynamite_a.yml",
            "dynamite_b.yml",
            "c4.yml",
            "shockwave.yml"
    );

    private Keys keys;
    private Settings settings;
    private Lang lang;
    private DynamiteRegistry registry;
    private RaidBlockManager raidBlocks;
    private PlacedDynamiteManager placedDynamites;
    private PrimingService priming;
    private QpsBridge qps;
    private AntiLag antiLag;
    private AlertThrottle alerts;
    private ExplosionStats stats;
    private CustomRecipeListener customRecipes;
    private ExecutorService ioPool;
    private QweTntsMetrics metrics;

    private ScheduledTask antiLagCleanupTask;
    private ScheduledTask placedSaveTask;
    private ScheduledTask placedCleanupTask;

    @Override
    public void onEnable() {
        if (!QpsApi.isAvailable()) {
            getLogger().log(Level.SEVERE,
                    "QweProtectStones не найден! Плагин выключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        keys = new Keys(this);

        saveDefaultConfig();
        settings = Settings.load(this);
        lang = new Lang(this);
        lang.load(settings.language());

        ioPool = ThreadPools.newIoPool(2, "QweTnts-IO");

        registry = new DynamiteRegistry();
        raidBlocks = new RaidBlockManager(this);
        placedDynamites = new PlacedDynamiteManager(this);
        priming = new PrimingService(this);
        qps = new QpsBridge(this);
        antiLag = new AntiLag(this, () -> settings.antiLag());
        alerts = new AlertThrottle(settings.alertCooldownMs());
        stats = new ExplosionStats();
        customRecipes = new CustomRecipeListener(this);

        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку плагина: " + getDataFolder().getAbsolutePath());
        }

        saveDefaultDynamiteConfigs();
        raidBlocks.load();
        placedDynamites.load();

        reloadDynamites();
        registerListeners();
        registerCommands();
        startTasks();

        if (settings.bstatsEnabled()) {
            metrics = new QweTntsMetrics(this, stats);
        }

        getLogger().info("QweTnts включён. Загружено динамитов: " + registry.all().size()
                + ", автоподжог: " + (settings.dynamites().autoIgnite() ? "включён" : "выключен"));
    }

    @Override
    public void onDisable() {
        if (metrics != null) metrics.shutdown();

        if (antiLagCleanupTask != null) antiLagCleanupTask.cancel();
        if (placedSaveTask != null) placedSaveTask.cancel();
        if (placedCleanupTask != null) placedCleanupTask.cancel();

        if (registry != null) registry.clear(getServer());

        try {
            if (placedDynamites != null) placedDynamites.save();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Ошибка сохранения установленных динамитов", ex);
        }

        if (raidBlocks != null) {
            raidBlocks.cancelTasks();
            if (ioPool != null) {
                raidBlocks.shutdown(ioPool);
            }
        }
        if (ioPool != null) ThreadPools.shutdown(ioPool, 5, getLogger());
    }

    // ------------------------------------------------------------------
    // Регистрация
    // ------------------------------------------------------------------

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new DynamiteUseListener(this), this);
        pm.registerEvents(new DynamitePlaceListener(this), this);
        pm.registerEvents(new DynamiteIgniteListener(this), this);
        pm.registerEvents(new DynamiteExplodeListener(this), this);
        pm.registerEvents(new RegionDynamiteListener(this), this);
        pm.registerEvents(new RaidBlockListener(this), this);
        pm.registerEvents(customRecipes, this);
        pm.registerEvents(new RaidLoggingListener(this), this);
    }

    private void registerCommands() {
        var cmd = getCommand("qwetnts");
        if (cmd == null) {
            getLogger().warning("Команда 'qwetnts' не зарегистрирована в plugin.yml!");
            return;
        }
        QweTntsCommand executor = new QweTntsCommand(this);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    private void startTasks() {
        raidBlocks.startTasks(ioPool,
                settings.raidBlocks().cleanupIntervalTicks(),
                settings.raidBlocks().autosaveIntervalTicks());

        // Чистка карт анти-лага: делаем реже, чем взрываются динамиты.
        antiLagCleanupTask = Schedulers.runAsyncTimer(this, task -> {
            antiLag.cleanup();
            alerts.cleanup();
        }, 1200L, 1200L);

        // Автосохранение установленных динамитов.
        placedSaveTask = Schedulers.runAsyncTimer(this, task -> {
            if (placedDynamites.isEmpty()) return;
            placedDynamites.saveAsync(ioPool);
        }, settings.placedDynamites().autosaveIntervalTicks(),
                settings.placedDynamites().autosaveIntervalTicks());

        // Чистка «фантомных» записей: блок трогаем — значит, только ГП.
        placedCleanupTask = Schedulers.runGlobalTimer(this, task -> {
            if (placedDynamites.isEmpty()) return;
            placedDynamites.prune(getServer());
        }, settings.placedDynamites().cleanupIntervalTicks(),
                settings.placedDynamites().cleanupIntervalTicks());
    }

    private void saveDefaultDynamiteConfigs() {
        File dir = new File(getDataFolder(), DYNAMITES_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            getLogger().warning("Не удалось создать папку dynamites/");
            return;
        }
        for (String builtin : BUILTIN_DYNAMITES) {
            File target = new File(dir, builtin);
            if (!target.isFile()) {
                saveResource(DYNAMITES_DIR + "/" + builtin, false);
            }
        }
    }

    /** (Пере)загружает config.yml, lang и все файлы динамитов. */
    public void reloadDynamites() {
        registry.clear(getServer());
        customRecipes.clear();

        reloadConfig();
        settings = Settings.load(this);
        lang.load(settings.language());

        DynamiteLoader loader = new DynamiteLoader(this);
        File dir = new File(getDataFolder(), DYNAMITES_DIR);
        if (!dir.isDirectory()) {
            getLogger().warning("Папка dynamites/ отсутствует: " + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) return;

        int loaded = 0;
        for (File file : files) {
            try {
                var opt = loader.load(file);
                if (opt.isEmpty()) continue;

                var type = opt.get();
                registry.register(type);
                type.registerRecipe(this);
                loaded++;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING,
                        "Не удалось загрузить динамит из " + file.getName(), ex);
            }
        }

        getLogger().info("Загружено динамитов: " + loaded);
    }

    // ------------------------------------------------------------------
    // Аксессоры
    // ------------------------------------------------------------------

    public @NotNull Keys keys() { return keys; }
    public @NotNull Settings settings() { return settings; }
    public @NotNull Lang lang() { return lang; }
    public @NotNull DynamiteRegistry registry() { return registry; }
    public @NotNull RaidBlockManager raidBlocks() { return raidBlocks; }
    public @NotNull PlacedDynamiteManager placedDynamites() { return placedDynamites; }
    public @NotNull PrimingService priming() { return priming; }
    public @NotNull QpsBridge qps() { return qps; }
    public @NotNull AntiLag antiLag() { return antiLag; }
    public @NotNull AlertThrottle alerts() { return alerts; }
    public @NotNull ExplosionStats stats() { return stats; }
    public @NotNull CustomRecipeListener customRecipes() { return customRecipes; }
    public @NotNull ExecutorService ioPool() { return ioPool; }

    /** Версия QPS для логов; {@code null}, если плагин недоступен. */
    public @Nullable String qpsVersion() {
        if (!QpsApi.isAvailable()) return null;
        var plugin = getServer().getPluginManager().getPlugin("QweProtectStones");
        return plugin == null ? null : plugin.getPluginMeta().getVersion();
    }
}
