package ru.qweyns.qwetnts.antilag;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.config.Lang;
import ru.qweyns.qwetnts.config.Settings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Защита от лаг-машин: сколько зарядов уже в воздухе у игрока и в чанке плюс
 * кулдаун активации.
 *
 * <p>Вызывается только из главного потока при установке/поджоге и взрыве,
 * поэтому {@link ConcurrentHashMap} без дополнительной синхронизации безопасны.</p>
 *
 * <p>Настройки читаются через {@code Supplier}: после {@code /qtnt reload}
 * лимиты подхватываются без пересоздания объекта.</p>
 */
public final class AntiLag {

    /** Причина отказа; {@link #NONE} — можно действовать. */
    public enum Deny {
        NONE,
        COOLDOWN,
        PLAYER_LIMIT,
        CHUNK_LIMIT
    }

    private record ChunkKey(UUID worldId, int cx, int cz) {
        static ChunkKey of(@NotNull Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private final Plugin plugin;
    private final Supplier<Settings.AntiLag> config;

    private final Map<UUID, Integer> perPlayer = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Integer> perChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public AntiLag(@NotNull Plugin plugin, @NotNull Supplier<Settings.AntiLag> config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Попытка «активировать» динамит (установить или поджечь с руки):
     * проверяет кулдаун и лимиты, при успехе увеличивает счётчики.
     *
     * @return {@link Deny#NONE} если можно, иначе причина отказа
     */
    public @NotNull Deny tryActivate(@Nullable UUID playerUuid, @Nullable Chunk chunk) {
        Settings.AntiLag cfg = config.get();
        return tryActivate(playerUuid, chunk,
                cfg.activationCooldownMillis(), cfg.maxPrimedPerPlayer(), cfg.maxPrimedPerChunk());
    }

    /**
     * То же, но с лимитами конкретного динамита: они могут перекрывать
     * глобальные (поля {@code limits.*} в файле динамита).
     */
    public @NotNull Deny tryActivate(@Nullable UUID playerUuid,
                                     @Nullable Chunk chunk,
                                     long cooldownMillis,
                                     int maxPerPlayer,
                                     int maxPerChunk) {
        long now = System.currentTimeMillis();

        if (playerUuid != null && cooldownMillis > 0) {
            Long last = activations.get(playerUuid);
            if (last != null && now - last < cooldownMillis) {
                return Deny.COOLDOWN;
            }
        }
        if (playerUuid != null && maxPerPlayer > 0
                && perPlayer.getOrDefault(playerUuid, 0) >= maxPerPlayer) {
            return Deny.PLAYER_LIMIT;
        }
        if (chunk != null && maxPerChunk > 0
                && perChunk.getOrDefault(ChunkKey.of(chunk), 0) >= maxPerChunk) {
            return Deny.CHUNK_LIMIT;
        }

        if (playerUuid != null) activations.put(playerUuid, now);
        register(playerUuid, chunk);
        return Deny.NONE;
    }

    /**
     * Только учёт already-зажжённого заряда (без кулдауна): поджог уже
     * установленного динамита не должен упираться в кулдаун активации,
     * иначе поджечь связку можно было бы только по одному.
     *
     * @return false, если превышен лимит на игрока/чанк
     */
    public boolean tryRegister(@Nullable UUID playerUuid, @Nullable Chunk chunk) {
        Settings.AntiLag cfg = config.get();
        return tryRegister(playerUuid, chunk, cfg.maxPrimedPerPlayer(), cfg.maxPrimedPerChunk());
    }

    /** То же с лимитами конкретного динамита. */
    public boolean tryRegister(@Nullable UUID playerUuid,
                               @Nullable Chunk chunk,
                               int maxPerPlayer,
                               int maxPerChunk) {
        if (playerUuid != null && maxPerPlayer > 0
                && perPlayer.getOrDefault(playerUuid, 0) >= maxPerPlayer) {
            return false;
        }
        if (chunk != null && maxPerChunk > 0
                && perChunk.getOrDefault(ChunkKey.of(chunk), 0) >= maxPerChunk) {
            return false;
        }

        register(playerUuid, chunk);
        return true;
    }

    /** Увеличить счётчики (вызывается только после успешной проверки лимитов). */
    private void register(@Nullable UUID playerUuid, @Nullable Chunk chunk) {
        if (playerUuid != null) {
            perPlayer.merge(playerUuid, 1, Integer::sum);
        }
        if (chunk != null) {
            perChunk.merge(ChunkKey.of(chunk), 1, Integer::sum);
        }
    }

    /** Снять учёт после взрыва. */
    public void release(@Nullable UUID playerUuid, @Nullable Chunk chunk) {
        if (playerUuid != null) {
            perPlayer.computeIfPresent(playerUuid, (uuid, count) -> count <= 1 ? null : count - 1);
        }
        if (chunk != null) {
            perChunk.computeIfPresent(ChunkKey.of(chunk), (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    /**
     * Отправить игроку сообщение об отказе с рейт-лимитом: при спаме кликами
     * чат не забивается одинаковыми строками.
     */
    public void notify(@Nullable Player player, @NotNull Lang lang, @NotNull String key,
                       String... placeholders) {
        if (player == null) return;

        long now = System.currentTimeMillis();
        long cooldown = config.get().messageCooldownMillis();
        if (cooldown > 0) {
            Long last = lastMessage.get(player.getUniqueId());
            if (last != null && now - last < cooldown) return;
            lastMessage.put(player.getUniqueId(), now);
        }
        lang.send(player, key, placeholders);
    }

    /** Сколько миллисекунд осталось ждать (0 — можно сразу). */
    public long cooldownRemaining(@Nullable UUID playerUuid) {
        if (playerUuid == null) return 0L;
        Long last = activations.get(playerUuid);
        if (last == null) return 0L;
        long cooldown = config.get().activationCooldownMillis();
        return Math.max(0L, last + cooldown - System.currentTimeMillis());
    }

    /** Периодическая чистка: карты не должны расти бесконечно. */
    public void cleanup() {
        long cooldown = Math.max(1L, config.get().activationCooldownMillis());
        long messageCooldown = Math.max(1L, config.get().messageCooldownMillis());
        long now = System.currentTimeMillis();

        activations.entrySet().removeIf(entry -> now - entry.getValue() > cooldown * 4);
        lastMessage.entrySet().removeIf(entry -> now - entry.getValue() > messageCooldown * 4);

        // Счётчики, которые «зависли» (заряд исчез без взрыва), чистим целиком:
        // если взрывов не было, то и perPlayer/perChunk пустые.
        if (perPlayer.isEmpty() && perChunk.isEmpty()) return;

        int primed = countPrimed();
        if (primed == 0) {
            perPlayer.clear();
            perChunk.clear();
        }
    }

    /** Сколько реально горящих зарядов осталось (для самовосстановления счётчиков). */
    private int countPrimed() {
        int total = 0;
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.TNTPrimed tnt : world.getEntitiesByClass(org.bukkit.entity.TNTPrimed.class)) {
                if (tnt.isValid()) total++;
            }
        }
        return total;
    }

    public void reset() {
        perPlayer.clear();
        perChunk.clear();
        activations.clear();
        lastMessage.clear();
    }
}
