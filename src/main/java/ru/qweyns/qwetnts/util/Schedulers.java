package ru.qweyns.qwetnts.util;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
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

    private Schedulers() {
    }

    /** Отложенная задача в тиках главного потока мира — безопасно для блоков и сущностей. */
    public static @NotNull ScheduledTask runAtLocation(@NotNull Plugin plugin,
                                                       @NotNull Location loc,
                                                       @NotNull Runnable task,
                                                       long delayTicks) {
        // RegionScheduler#execute возвращает void, поэтому единая точка входа —
        // runDelayed (Consumer<ScheduledTask>), она же даёт отменяемую задачу.
        RegionScheduler rs = plugin.getServer().getRegionScheduler();
        return rs.runDelayed(plugin, loc, t -> task.run(), Math.max(0L, delayTicks));
    }

    /** Периодическая глобальная задача (ГП, но привязана к глобальному региону). */
    public static @NotNull ScheduledTask runGlobalTimer(@NotNull Plugin plugin,
                                                       @NotNull Consumer<ScheduledTask> task,
                                                       long initialDelayTicks,
                                                       long periodTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
    }

    /** Асинхронная периодическая задача (не трогать Bukkit API внутри!). */
    public static @NotNull ScheduledTask runAsyncTimer(@NotNull Plugin plugin,
                                                       @NotNull Consumer<ScheduledTask> task,
                                                       long initialDelayTicks,
                                                       long periodTicks) {
        return plugin.getServer().getAsyncScheduler()
                .runAtFixedRate(plugin, task, initialDelayTicks * 50, periodTicks * 50,
                        TimeUnit.MILLISECONDS);
    }

    /** Одноразовая async-задача (I/O). */
    public static @NotNull ScheduledTask runAsync(@NotNull Plugin plugin,
                                                  @NotNull Runnable task) {
        return plugin.getServer().getAsyncScheduler()
                .runNow(plugin, t -> task.run());
    }
}
