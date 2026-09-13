package ru.qweyns.qwetnts.antilag;

import org.bukkit.Chunk;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.Settings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Счётчики одновременно горящих TNTPrimed на игрока/чанк и кулдаун активации.
 *
 * <p>Все методы вызываются из главного потока (при спавне и взрыве ТНТ),
 * поэтому ConcurrentHashMap без дополнительной синхронизации безопасны.</p>
 */
public final class AntiLag {

    private final QweTnts plugin;
    private final Settings s;

    /** Игрок -> кол-во зажжённых ТНТ, спавненных им. */
    private final Map<UUID, Integer> perPlayer = new ConcurrentHashMap<>();
    /** Ключ чанка (packed) -> кол-во зажжённых ТНТ. */
    private final Map<Long, Integer> perChunk = new ConcurrentHashMap<>();

    /** Игрок -> millis последней активации. */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public AntiLag(QweTnts plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
    }

    /**
     * Проверяет, можно ли сейчас зажечь очередную ТНТ от указанного игрока.
     * При успехе увеличивает счётчики.
     *
     * @return null если можно спавнить; иначе — ключ i18n-сообщения об ошибке.
     */
    public @Nullable String tryAcquire(UUID playerUuid, Chunk chunk) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(playerUuid);
        if (last != null && now - last < s.activationCooldownMs) {
            return "antilag.cooldown";
        }
        if (s.maxPrimedPerPlayer > 0) {
            int c = perPlayer.getOrDefault(playerUuid, 0);
            if (c >= s.maxPrimedPerPlayer) {
                return "antilag.player-limit";
            }
        }
        if (s.maxPrimedPerChunk > 0) {
            long key = chunkKey(chunk);
            int c = perChunk.getOrDefault(key, 0);
            if (c >= s.maxPrimedPerChunk) {
                return "antilag.chunk-limit";
            }
        }
        cooldowns.put(playerUuid, now);
        perPlayer.merge(playerUuid, 1, Integer::sum);
        perChunk.merge(chunkKey(chunk), 1, Integer::sum);
        return null;
    }

    /** Уведомить антилаг, что ТНТ от данного игрока взорвалась — декремент. */
    public void release(@Nullable UUID playerUuid, Chunk chunk) {
        if (playerUuid != null) {
            perPlayer.computeIfPresent(playerUuid, (u, v) -> v <= 1 ? null : v - 1);
        }
        long key = chunkKey(chunk);
        Integer c = perChunk.get(key);
        if (c == null) return;
        if (c <= 1) perChunk.remove(key);
        else perChunk.put(key, c - 1);
    }

    private static long chunkKey(Chunk c) {
        // Упаковка: старшие 32 бита — hash uid мира, следующие 16 бит — cx & 0xFFFF,
        // младшие 16 бит — cz & 0xFFFF. Для счётчиков этого хватает.
        int cx = c.getX();
        int cz = c.getZ();
        int wh = c.getWorld().getUID().hashCode();
        return ((long) wh << 32)
                | (((long) (cx & 0xFFFF)) << 16)
                | (cz & 0xFFFF);
    }
}
