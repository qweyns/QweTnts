package ru.qweyns.qwetnts.bunker;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Io;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Состояние стены бункера: текущая стадия и таймеры.
 *
 * <p>Хранится в {@code bunker-state.yml} и переживает перезапуск сервера:
 * если стена уже пробита, она не должна «починиться» сама по рестарту,
 * иначе гриндивший её час игрок потерял бы всё.</p>
 *
 * <p>Все поля {@code volatile}: чтение идёт из потока взрыва, запись — из
 * команды администратора и из периодической задачи респавна.</p>
 */
public final class BunkerState {

    private final QweTnts plugin;
    private final File file;

    /** Индекс текущей стадии в {@code wall.stages}; последний индекс — AIR. */
    private volatile int stageIndex;
    private volatile boolean breached;
    private volatile long lastShotAtMillis;
    /** 0 — респавн не запланирован. */
    private volatile long nextRespawnAtMillis;

    public BunkerState(@NotNull QweTnts plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bunker-state.yml");
    }

    public int stageIndex() { return stageIndex; }

    public void setStage(int index) { this.stageIndex = Math.max(0, index); }

    public boolean breached() { return breached; }

    public void setBreached(boolean value) { this.breached = value; }

    public long lastShotAtMillis() { return lastShotAtMillis; }

    public void markShot(long now) { this.lastShotAtMillis = now; }

    public long nextRespawnAtMillis() { return nextRespawnAtMillis; }

    public void setNextRespawnAtMillis(long epochMillis) {
        this.nextRespawnAtMillis = Math.max(0L, epochMillis);
    }

    /** Сколько миллисекунд до восстановления (0 — не запланировано). */
    public long millisUntilRespawn() {
        if (nextRespawnAtMillis <= 0L) return 0L;
        return Math.max(0L, nextRespawnAtMillis - System.currentTimeMillis());
    }

    public void load() {
        if (!file.isFile()) return;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось прочитать " + file.getName() + ": " + ex.getMessage(), ex);
            return;
        }

        stageIndex = Math.max(0, yaml.getInt("stage", 0));
        breached = yaml.getBoolean("breached", false);
        lastShotAtMillis = yaml.getLong("last-shot-at", 0L);
        nextRespawnAtMillis = yaml.getLong("next-respawn-at", 0L);
    }

    /** Синхронная запись (для выключения плагина). */
    public void save() {
        Io.writeAtomic(file.toPath(), dump(), plugin.getLogger());
    }

    /** Асинхронная запись на I/O-пуле (основной путь). */
    public void saveAsync(@NotNull ExecutorService io) {
        String text = dump();
        io.execute(() -> Io.writeAtomic(file.toPath(), text, plugin.getLogger()));
    }

    private @NotNull String dump() {
        return "# Состояние стены бункера. Править руками не нужно:\n"
                + "# stage — индекс стадии из bunker.yml (последний — AIR, то есть пробита),\n"
                + "# next-respawn-at — время восстановления (epoch millis, 0 — не запланировано).\n"
                + "stage: " + stageIndex + "\n"
                + "breached: " + breached + "\n"
                + "last-shot-at: " + lastShotAtMillis + "\n"
                + "next-respawn-at: " + nextRespawnAtMillis + "\n";
    }
}
