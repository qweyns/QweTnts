package ru.qweyns.qwetnts;

import org.bukkit.plugin.java.JavaPlugin;
import org.qweyns.qweprotectstones.api.QpsApi;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.command.QweTntsCommand;
import ru.qweyns.qwetnts.config.DynamiteLoader;
import ru.qweyns.qwetnts.config.Lang;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteRegistry;
import ru.qweyns.qwetnts.listener.CustomRecipeListener;
import ru.qweyns.qwetnts.listener.DynamiteActivateListener;
import ru.qweyns.qwetnts.listener.DynamiteExplodeListener;
import ru.qweyns.qwetnts.listener.RaidBlockListener;
import ru.qweyns.qwetnts.listener.RegionDynamiteListener;
import ru.qweyns.qwetnts.listener.RaidLoggingListener;
import ru.qweyns.qwetnts.metrics.QweTntsMetrics;
import ru.qweyns.qwetnts.raid.AlertThrottle;
import ru.qweyns.qwetnts.raid.RaidBlockManager;
import ru.qweyns.qwetnts.stats.ExplosionStats;
import ru.qweyns.qwetnts.util.ThreadPools;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Плагин-аддон для QweProtectStones: кастомные динамиты в стиле HolyWorld Lite.
 * Полное ТЗ — {@code docs/DYNAMITE_ADDON_API.md} в репозитории QPS.
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
    private AntiLag antiLag;
    private AlertThrottle alerts;
    private ExplosionStats stats;
    private CustomRecipeListener customRecipes;
    private ExecutorService ioPool;
    private QweTntsMetrics metrics;

    @Override
    public void onEnable() {
        if (!QpsApi.isAvailable()) {
            getLogger().log(Level.SEVERE,
                    "QweProtectStones не найден! Плагин выключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        keys = new Keys(this);

        // Конфиг и язык
        saveDefaultConfig();
        settings = Settings.load(this);
        lang = new Lang(this);
        lang.load(settings.language);

        // I/O пул
        ioPool = ThreadPools.newIoPool(2, "QweTnts-IO");

        registry = new DynamiteRegistry();
        raidBlocks = new RaidBlockManager(this);
        antiLag = new AntiLag(this, settings);
        alerts = new AlertThrottle(settings.alertCooldownMs);
        stats = new ExplosionStats();
        customRecipes = new CustomRecipeListener(this);

        saveDefaultDynamiteConfigs();
        raidBlocks.load();
        raidBlocks.startTasks(ioPool,
                settings.raidBlockCleanupIntervalTicks,
                settings.raidBlockAutoSaveIntervalTicks);

        reloadDynamites();

        registerListeners();
        registerCommands();

        if (settings.bstatsEnabled) {
            metrics = new QweTntsMetrics(this, stats);
        }

        getLogger().info("QweTnts включён. Загружено динамитов: " + registry.all().size());
    }

    @Override
    public void onDisable() {
        if (metrics != null) metrics.shutdown();
        if (registry != null) registry.clear(getServer());
        if (raidBlocks != null && ioPool != null) raidBlocks.shutdown(ioPool);
        if (ioPool != null) ThreadPools.shutdown(ioPool, 5, getLogger());
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new DynamiteActivateListener(this), this);
        pm.registerEvents(new DynamiteExplodeListener(this), this);
        pm.registerEvents(new RegionDynamiteListener(this), this);
        pm.registerEvents(new RaidBlockListener(this), this);
        pm.registerEvents(customRecipes, this);
        pm.registerEvents(new RaidLoggingListener(this), this);
    }

    private void registerCommands() {
        var cmd = getCommand("qwetnts");
        if (cmd != null) {
            var exec = new QweTntsCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        } else {
            getLogger().warning("Команда 'qwetnts' не зарегистрирована в plugin.yml!");
        }
    }

    private void saveDefaultDynamiteConfigs() {
        File dir = new File(getDataFolder(), DYNAMITES_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().warning("Не удалось создать папку dynamites/");
            return;
        }
        for (String builtin : BUILTIN_DYNAMITES) {
            File target = new File(dir, builtin);
            if (!target.exists()) {
                saveResource(DYNAMITES_DIR + "/" + builtin, false);
            }
        }
    }

    /** (Пере)загружает все конфиги динамита и рецепты. */
    public void reloadDynamites() {
        registry.clear(getServer());

        reloadConfig();
        settings = Settings.load(this);
        lang.load(settings.language);

        DynamiteLoader loader = new DynamiteLoader(this);
        File dir = new File(getDataFolder(), DYNAMITES_DIR);
        if (!dir.isDirectory()) {
            getLogger().warning("Папка dynamites/ отсутствует: " + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) return;

        int loaded = 0;
        for (File f : files) {
            try {
                var opt = loader.load(f);
                if (opt.isEmpty()) continue;
                var type = opt.get();
                registry.register(type);
                type.registerRecipe(this);
                loaded++;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING,
                        "Не удалось загрузить динамит из " + f.getName(), ex);
            }
        }
        getLogger().info("Загружено динамитов: " + loaded);
    }

    public Keys keys() { return keys; }
    public Settings settings() { return settings; }
    public Lang lang() { return lang; }
    public DynamiteRegistry registry() { return registry; }
    public RaidBlockManager raidBlocks() { return raidBlocks; }
    public AntiLag antiLag() { return antiLag; }
    public AlertThrottle alerts() { return alerts; }
    public ExplosionStats stats() { return stats; }
    public CustomRecipeListener customRecipes() { return customRecipes; }
    public ExecutorService ioPool() { return ioPool; }
    public QpsApi qps() { return QpsApi.get(); }
}
