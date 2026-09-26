package ru.qweyns.qwetnts;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qweyns.qweprotectstones.api.QpsApi;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.blocks.TemporaryBlockManager;
import ru.qweyns.qwetnts.bunker.BunkerService;
import ru.qweyns.qwetnts.cannon.CannonItem;
import ru.qweyns.qwetnts.cannon.CannonListener;
import ru.qweyns.qwetnts.cannon.CannonLoader;
import ru.qweyns.qwetnts.cannon.CannonService;
import ru.qweyns.qwetnts.cannon.CannonSettings;
import ru.qweyns.qwetnts.command.QweTntsCommand;
import ru.qweyns.qwetnts.component.ComponentLoader;
import ru.qweyns.qwetnts.component.ComponentRegistry;
import ru.qweyns.qwetnts.component.ComponentType;
import ru.qweyns.qwetnts.config.DynamiteLoader;
import ru.qweyns.qwetnts.config.Lang;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteRegistry;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.dynamite.PrimingService;
import ru.qweyns.qwetnts.integration.DiscordWebhook;
import ru.qweyns.qwetnts.integration.QpsBridge;
import ru.qweyns.qwetnts.listener.BunkerListener;
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
import ru.qweyns.qwetnts.hologram.FuseHologramManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    /**
     * Пушка — не динамит, а отдельное устройство, поэтому её файл лежит в
     * корне папки плагина, а не в {@code dynamites/}.
     */
    private static final String CANNON_FILE = "cannon.yml";
    /**
     * Бункер замка — один на сервер (как и пушка), поэтому его файл лежит
     * в корне папки плагина.
     */
    private static final String BUNKER_FILE = "bunker.yml";
    private static final List<String> BUILTIN_DYNAMITES = List.of(
            "dynamite_a.yml",
            "dynamite_b.yml",
            "c4.yml",
            "shockwave.yml",
            "stiller.yml",
            "reliable_stiller.yml",
            "ice_wave.yml",
            "dynamite_b2.yml"
    );

    /** Компоненты крафта: один файл на все, лежит в корне папки плагина. */
    private static final String COMPONENTS_FILE = "components.yml";

    private Keys keys;
    private Settings settings;
    private Lang lang;
    private DynamiteRegistry registry;
    private ComponentRegistry components;
    private CannonSettings cannon;
    private CannonItem cannonItem;
    private CannonService cannonService;
    private BunkerService bunker;
    private RaidBlockManager raidBlocks;
    private TemporaryBlockManager temporaryBlocks;
    private PlacedDynamiteManager placedDynamites;
    private PrimingService priming;
    private FuseHologramManager hologramManager;
    private QpsBridge qps;
    private AntiLag antiLag;
    private AlertThrottle alerts;
    private DiscordWebhook discord;
    private ExplosionStats stats;
    private CustomRecipeListener customRecipes;
    private ExecutorService ioPool;
    private QweTntsMetrics metrics;

    private ScheduledTask antiLagCleanupTask;
    private ScheduledTask placedSaveTask;
    private ScheduledTask placedCleanupTask;
    private ScheduledTask bunkerTask;
    private ScheduledTask hologramTask;

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
        components = new ComponentRegistry();
        cannon = CannonSettings.DISABLED;
        cannonItem = new CannonItem(this, cannon);
        cannonService = new CannonService(this);
        bunker = new BunkerService(this);
        raidBlocks = new RaidBlockManager(this);
        temporaryBlocks = new TemporaryBlockManager(this);
        placedDynamites = new PlacedDynamiteManager(this);
        priming = new PrimingService(this);
        hologramManager = new FuseHologramManager(this);
        qps = new QpsBridge(this);
        antiLag = new AntiLag(() -> settings.antiLag());
        alerts = new AlertThrottle(settings.alerts().attackAlertCooldownMillis());
        discord = new DiscordWebhook(this);
        stats = new ExplosionStats();
        customRecipes = new CustomRecipeListener(this);

        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку плагина: " + getDataFolder().getAbsolutePath());
        }

        saveDefaultDynamiteConfigs();
        raidBlocks.load();
        temporaryBlocks.load();
        placedDynamites.load();

        reloadDynamites();
        registerListeners();
        registerCommands();
        startTasks();

        if (settings.bstatsEnabled()) {
            metrics = new QweTntsMetrics(this, stats);
        }

        getLogger().info("Голограммы: " + (settings.holograms().enabled()
                ? "включены (" + hologramManager.availableProviders() + ")"
                : "выключены"));

        getLogger().info("QweTnts включён. Загружено динамитов: " + registry.all().size()
                + ", автоподжог: " + (settings.dynamites().autoIgnite() ? "включён" : "выключен"));
    }

    @Override
    public void onDisable() {
        if (metrics != null) metrics.shutdown();
        if (discord != null) discord.shutdown();

        if (antiLagCleanupTask != null) antiLagCleanupTask.cancel();
        if (placedSaveTask != null) placedSaveTask.cancel();
        if (placedCleanupTask != null) placedCleanupTask.cancel();

        // Голограммы — первыми: сущности TextDisplay должны исчезнуть до
        // того, как плагин перестанет отвечать на события.
        if (hologramTask != null) hologramTask.cancel();
        if (hologramManager != null) hologramManager.removeAll();

        if (registry != null) registry.clear(getServer());

        try {
            if (placedDynamites != null) placedDynamites.save();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Ошибка сохранения установленных динамитов", ex);
        }

        if (bunker != null && bunker.isEnabled()) {
            try {
                bunker.state().save();
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Ошибка сохранения состояния бункера", ex);
            }
        }

        if (temporaryBlocks != null) {
            // НЕ восстанавливаем блоки при выключении: иначе после рестарта
            // лёд остался бы навсегда, а срок его действия ещё не истёк.
            temporaryBlocks.shutdown();
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
        pm.registerEvents(new CannonListener(this), this);
        pm.registerEvents(new BunkerListener(this), this);
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
        cancelTasks();

        raidBlocks.startTasks(ioPool,
                settings.raidBlocks().cleanupIntervalTicks(),
                settings.raidBlocks().autosaveIntervalTicks());

        // Временные блоки (лёд): возврат — на главном потоке, сейв — в I/O.
        temporaryBlocks.startTasks(ioPool,
                settings.temporaryBlocks().cleanupIntervalTicks(),
                settings.temporaryBlocks().autosaveIntervalTicks());

        // Чистка карт анти-лага: делаем реже, чем взрываются динамиты.
        // Только главный поток: внутри есть пересчёт живых зарядов по мирам.
        antiLagCleanupTask = Schedulers.runGlobalTimer(this, task -> {
            antiLag.cleanup();
            alerts.cleanup();
        }, 1200L, 1200L);

        // Автосохранение установленных динамитов.
        placedSaveTask = Schedulers.runAsyncTimer(this, task -> {
            if (placedDynamites.isEmpty()) return;
            placedDynamites.saveAsync(ioPool);
        }, settings.placedDynamites().autosaveIntervalTicks(),
                settings.placedDynamites().autosaveIntervalTicks());

        // Бункер: сторожевой таймер респавна стены. Раз в минуту — чаще
        // незачем: интервал в конфиге указан в минутах.
        bunkerTask = Schedulers.runGlobalTimer(this, task -> {
            if (bunker.isEnabled()) bunker.tick();
        }, 1200L, 1200L);

        // Голограммы над горящими зарядами: пересчёт отсчёта и позиции.
        // Интервал из конфига — это и есть частота смены цифр на табличке.
        long hologramInterval = Math.max(1L, settings.holograms().updateIntervalTicks());
        hologramTask = Schedulers.runGlobalTimer(this, task -> {
            if (hologramManager.activeCount() == 0) return;   // нечего обновлять — выходим сразу
            hologramManager.tick();
        }, hologramInterval, hologramInterval);

        // Чистка «фантомных» записей: блок трогаем — значит, только ГП.
        placedCleanupTask = Schedulers.runGlobalTimer(this, task -> {
            if (placedDynamites.isEmpty()) return;
            placedDynamites.prune(getServer());
        }, settings.placedDynamites().cleanupIntervalTicks(),
                settings.placedDynamites().cleanupIntervalTicks());
    }

    /**
     * Читает {@code components.yml} и регистрирует компоненты крафта.
     *
     * <p>Ошибка одного компонента не роняет загрузку целиком: он
     * пропускается, остальные работают.</p>
     *
     * @return сколько компонентов загружено
     */
    private int loadComponents() {
        File file = new File(getDataFolder(), COMPONENTS_FILE);
        if (!file.isFile()) return 0;

        int loaded = 0;
        for (ComponentType component : ComponentLoader.load(this, file)) {
            try {
                components.register(component);
                component.registerRecipes(this);
                loaded++;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING,
                        "Не удалось зарегистрировать компонент " + component.id(), ex);
            }
        }
        return loaded;
    }

    /**
     * Читает один файл динамита и регистрирует его. Битый файл не роняет
     * загрузку целиком: он пропускается с предупреждением в лог.
     *
     * @return 1, если тип загружен, иначе 0.
     */
    private int loadOne(@NotNull DynamiteLoader loader, @NotNull File file) {
        try {
            var opt = loader.load(file);
            if (opt.isEmpty()) return 0;

            var type = opt.get();
            registry.register(type);
            type.registerRecipe(this);
            return 1;
        } catch (Exception ex) {
            getLogger().log(Level.WARNING,
                    "Не удалось загрузить динамит из " + file.getName(), ex);
            return 0;
        }
    }

    /**
     * Снять все периодические задачи. Вызывается перед (пере)запуском, чтобы
     * после {@code /qtnt reload} не осталось двух наборов задач с разными
     * интервалами.
     */
    private void cancelTasks() {
        if (antiLagCleanupTask != null) {
            antiLagCleanupTask.cancel();
            antiLagCleanupTask = null;
        }
        if (placedSaveTask != null) {
            placedSaveTask.cancel();
            placedSaveTask = null;
        }
        if (placedCleanupTask != null) {
            placedCleanupTask.cancel();
            placedCleanupTask = null;
        }
        if (hologramTask != null) {
            hologramTask.cancel();
            hologramTask = null;
        }
        if (temporaryBlocks != null) temporaryBlocks.cancelTasks();
        if (bunkerTask != null) {
            bunkerTask.cancel();
            bunkerTask = null;
        }
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

        migrateCannonConfig(dir);

        File componentsTarget = new File(getDataFolder(), COMPONENTS_FILE);
        if (!componentsTarget.isFile()) {
            saveResource(COMPONENTS_FILE, false);
        }

        File cannonTarget = new File(getDataFolder(), CANNON_FILE);
        if (!cannonTarget.isFile()) {
            saveResource(CANNON_FILE, false);
        }

        File bunkerTarget = new File(getDataFolder(), BUNKER_FILE);
        if (!bunkerTarget.isFile()) {
            saveResource(BUNKER_FILE, false);
        }
    }

    /**
     * Пушка раньше была динамитом и лежала в {@code dynamites/cannon.yml}.
     * Формат у нового {@code cannon.yml} другой, поэтому старый файл из папки
     * динамитов убираем: иначе он загрузился бы как обычный динамит и создал
     * второй тип {@code cannon}. Сам файл не удаляем, а переименовываем —
     * вдруг там были настройки, которые жалко.
     */
    private void migrateCannonConfig(@NotNull File dynamitesDir) {
        File legacy = new File(dynamitesDir, CANNON_FILE);
        if (!legacy.isFile()) return;

        File archived = new File(dynamitesDir, CANNON_FILE + ".old");
        try {
            if (archived.isFile()) {
                Files.delete(archived.toPath());
            }
            Files.move(legacy.toPath(), archived.toPath(), StandardCopyOption.REPLACE_EXISTING);
            getLogger().warning("dynamites/" + CANNON_FILE + " больше не используется и переименован в "
                    + CANNON_FILE + ".old. Настройки пушки теперь лежат в " + CANNON_FILE
                    + " в корне папки плагина.");
        } catch (IOException ex) {
            getLogger().log(Level.WARNING,
                    "Не удалось убрать устаревший dynamites/" + CANNON_FILE, ex);
        }
    }

    /** (Пере)загружает config.yml, lang и все файлы динамитов. */
    public void reloadDynamites() {
        registry.clear(getServer());
        components.clear(getServer());
        customRecipes.clear();

        reloadConfig();
        settings = Settings.load(this);
        lang.load(settings.language());

        // Настройки голограмм перечитаны — пересобираем провайдеров и
        // убираем старые голограммы: их оформление могло измениться.
        if (hologramManager != null) hologramManager.refresh();

        // Компоненты — ДО динамитов: рецепт Динамита Б2 требует Взрывчатое
        // вещество, и его материал нужен как плейсхолдер в рецепте.
        int componentsLoaded = loadComponents();

        DynamiteLoader loader = new DynamiteLoader(this);

        int loaded = 0;

        File dir = new File(getDataFolder(), DYNAMITES_DIR);
        if (!dir.isDirectory()) {
            getLogger().warning("Папка dynamites/ отсутствует: " + dir.getAbsolutePath());
        } else {
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    // Пушка переехала в корень папки плагина.
                    if (file.getName().equalsIgnoreCase(CANNON_FILE)) continue;
                    loaded += loadOne(loader, file);
                }
            }
        }

        // Тнт-пушка — отдельная механика, а не динамит: читаем её файл сами.
        cannon = CannonLoader.load(this, new File(getDataFolder(), CANNON_FILE));
        cannonItem = new CannonItem(this, cannon);
        if (cannon.enabled()) {
            cannonItem.registerRecipe();
        }

        // Бункер замка: стена пробивается выстрелами пушки, поэтому читаем
        // его настройки сразу после пушки.
        bunker.reload();

        getLogger().info("Загружено динамитов: " + loaded
                + ", компонентов крафта: " + componentsLoaded
                + ", пушка: " + (cannon.enabled() ? "включена" : "выключена")
                + ", бункер: " + (bunker.isEnabled() ? "включён" : "выключен"));

        // config.yml перечитан: интервалы задач и кулдаун алертов могли
        // измениться, поэтому перезапускаем периодику (startTasks сам
        // снимает старые задачи).
        if (alerts != null) {
            alerts.updateCooldown(settings.alerts().attackAlertCooldownMillis());
        }
        startTasks();
    }

    // ------------------------------------------------------------------
    // Аксессоры
    // ------------------------------------------------------------------

    public @NotNull Keys keys() { return keys; }
    public @NotNull Settings settings() { return settings; }
    public @NotNull Lang lang() { return lang; }
    public @NotNull DynamiteRegistry registry() { return registry; }
    public @NotNull ComponentRegistry components() { return components; }
    public @NotNull CannonSettings cannon() { return cannon; }
    public @NotNull CannonItem cannonItem() { return cannonItem; }
    public @NotNull CannonService cannonService() { return cannonService; }
    public @NotNull BunkerService bunker() { return bunker; }
    public @NotNull RaidBlockManager raidBlocks() { return raidBlocks; }
    public @NotNull TemporaryBlockManager temporaryBlocks() { return temporaryBlocks; }
    public @NotNull PlacedDynamiteManager placedDynamites() { return placedDynamites; }
    public @NotNull PrimingService priming() { return priming; }
    public @NotNull FuseHologramManager hologramManager() { return hologramManager; }
    public @NotNull QpsBridge qps() { return qps; }
    public @NotNull AntiLag antiLag() { return antiLag; }
    public @NotNull AlertThrottle alerts() { return alerts; }
    public @NotNull DiscordWebhook discord() { return discord; }
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
