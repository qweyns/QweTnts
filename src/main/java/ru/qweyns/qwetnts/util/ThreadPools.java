package ru.qweyns.qwetnts.util;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Именованный пул потоков для I/O (сохранение файлов, парсинг конфигов). */
public final class ThreadPools {

    private ThreadPools() {
    }

    public static @NotNull ExecutorService newIoPool(int threads, @NotNull String name) {
        return Executors.newFixedThreadPool(Math.max(1, threads),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((th, ex) ->
                            Logger.getLogger(name).log(Level.SEVERE,
                                    "Необработанное исключение в пуле " + name, ex));
                    return t;
                });
    }

    /** Корректное завершение с таймаутом. */
    public static void shutdown(@NotNull ExecutorService pool, long timeoutSec,
                                @NotNull Logger log) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
