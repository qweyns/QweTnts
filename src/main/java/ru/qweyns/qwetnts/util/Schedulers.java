package ru.qweyns.qwetnts.util;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Обёртка над шедулерами Paper, совместимая с Folia.
 *
 * <p>В Paper 1.21+ и Folia глобальный {@code BukkitScheduler} не считается
 * безопасным: для работы с блоками/сущностями используется
 * {@link RegionScheduler}, для I/O и периодических фоновых задач —
 * {@code AsyncScheduler}.</p>
 */
public final class Schedulers {

    /**
     * Мы на Folia?
     *
     * <p>Проверка по наличию класса ядра Folia, как это принято в плагинах:
     * отдельного API для определения нет. Нужно затем же, зачем в
     * QweProtectStones: мосты DecentHolograms и FancyHolograms на Folia не
     * работают, и там надо сразу брать встроенные голограммы.</p>
     */
    private static final boolean FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean hasClass(@NotNull String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    private Schedulers() {
    }

    /**
     * Выполнить задачу в регионе локации прямо сейчас — безопасно для блоков и сущностей.
     *
     * <p><b>Почему не {@code runDelayed} с нулевой задержкой:</b> на Folia
     * {@code RegionScheduler#runDelayed} бросает
     * {@code IllegalArgumentException: Delay ticks may not be <= 0}. Для
     * «выполнить сразу» у планировщика регионов есть отдельный метод
     * {@code execute}. Из-за этой разницы первая версия обёртки падала на
     * каждом поджоге динамита: задачу кидали в {@code runDelayed(…, 0)},
     * Folia её отвергала, и весь поджог отменялся.</p>
     */
    public static void runAtLocation(@NotNull Plugin plugin,
                                     @NotNull Location loc,
                                     @NotNull Runnable task) {
        plugin.getServer().getRegionScheduler().execute(plugin, loc, task);
    }

    /**
     * Отложенная задача в тиках главного потока мира — безопасно для блоков и сущностей.
     *
     * <p>Задержка меньше одного тика округляется до одного: нуля планировщик
     * регионов не принимает (см. {@link #runAtLocation(Plugin, Location, Runnable)}).</p>
     */
    public static @NotNull ScheduledTask runAtLocation(@NotNull Plugin plugin,
                                                       @NotNull Location loc,
                                                       @NotNull Runnable task,
                                                       long delayTicks) {
        RegionScheduler rs = plugin.getServer().getRegionScheduler();
        return rs.runDelayed(plugin, loc, t -> task.run(), Math.max(1L, delayTicks));
    }

    /**
     * Задача в «родном» регионе сущности.
     *
     * <p>На Folia сущность принадлежит региону, и трогать её (менять текст,
     * перемещать, удалять) можно только оттуда. Обычный {@code runGlobal}
     * здесь бросил бы {@link IllegalStateException}.</p>
     */
    public static void runAtEntity(@NotNull Plugin plugin,
                                   @NotNull Entity entity,
                                   @NotNull Runnable task) {
        entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
    }

    /**
     * Разовое выполнение на глобальном потоке.
     *
     * <p>Нужно там, где из обработчика события (поток региона на Folia)
     * требуется обратиться к игроку: слать сообщение напрямую из чужого
     * потока нельзя.</p>
     */
    public static void runGlobal(@NotNull Plugin plugin, @NotNull Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    /**
     * Периодическая глобальная задача (ГП, но привязана к глобальному региону).
     *
     * <p>Период и первая задержка — не меньше одного тика: с нулём
     * планировщик не регистрирует таймер, а «0» в конфиге — частая описка,
     * которая не должна ронять запуск плагина.</p>
     */
    public static @NotNull ScheduledTask runGlobalTimer(@NotNull Plugin plugin,
                                                       @NotNull Consumer<ScheduledTask> task,
                                                       long initialDelayTicks,
                                                       long periodTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task,
                        Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    /** Асинхронная периодическая задача (не трогать Bukkit API внутри!). */
    public static @NotNull ScheduledTask runAsyncTimer(@NotNull Plugin plugin,
                                                       @NotNull Consumer<ScheduledTask> task,
                                                       long initialDelayTicks,
                                                       long periodTicks) {
        long delayMs = Math.max(1L, initialDelayTicks) * 50L;
        long periodMs = Math.max(1L, periodTicks) * 50L;
        return plugin.getServer().getAsyncScheduler()
                .runAtFixedRate(plugin, task, delayMs, periodMs, TimeUnit.MILLISECONDS);
    }
}
