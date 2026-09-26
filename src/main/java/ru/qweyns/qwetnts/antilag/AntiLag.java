package ru.qweyns.qwetnts.antilag;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
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
 *
 * <h2>Самовосстановление счётчиков</h2>
 * <p>Заряд может исчезнуть без взрыва: его снёс другой взрыв, мир выгрузили,
 * сервер перезапустили. Тогда {@link #release(UUID, Chunk)} не вызывается, и
 * счётчик навсегда блокировал бы игрока. Раньше для лечения сканировались все
 * сущности всех миров ({@code World#getEntitiesByClass}) — на Folia это
 * обращение к чужим регионам, да и стоит полный проход по сущностям.</p>
 *
 * <p>Теперь у каждого счётчика есть время последнего изменения: если он не
 * менялся дольше {@link #STALE_MILLIS}, значит заряды давно должны были
 * взорваться, и счётчик считается «зависшим». Это детерминированно, не трогает
 * мир и стоит O(числа игроков + чанков).</p>
 */
public final class AntiLag {

    /**
     * Через сколько миллисекунд неизменный счётчик считается зависшим.
     * С запасом больше любого фитиля из конфига.
     */
    private static final long STALE_MILLIS = 5 * 60 * 1000L;

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

    /** Счётчик с временем последнего изменения (для самовосстановления). */
    private static final class Counter {
        private int value;
        private long lastChangedAt;

        Counter(long now) {
            this.value = 0;
            this.lastChangedAt = now;
        }

        synchronized boolean fits(int max) {
            return max <= 0 || value < max;
        }

        synchronized void increment(long now) {
            value++;
            lastChangedAt = now;
        }

        /** @return true, если счётчик обнулился и запись можно удалить. */
        synchronized boolean decrement(long now) {
            if (value > 0) value--;
            lastChangedAt = now;
            return value <= 0;
        }

        synchronized boolean isStale(long now, long staleMillis) {
            return value > 0 && now - lastChangedAt > staleMillis;
        }
    }

    private final Supplier<Settings.AntiLag> config;

    private final Map<UUID, Counter> perPlayer = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Counter> perChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public AntiLag(@NotNull Supplier<Settings.AntiLag> config) {
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
        Deny deny = canActivate(playerUuid, chunk, cooldownMillis, maxPerPlayer, maxPerChunk);
        if (deny != Deny.NONE) return deny;

        commit(playerUuid, chunk, cooldownMillis);
        return Deny.NONE;
    }

    /**
     * <b>Первая фаза</b> двухфазной активации: только проверка, без учёта.
     *
     * <p>Нужна там, где между решением и самим действием стоит чужой код.
     * Классический пример — установка блока TNT: мы проверяем на приоритете
     * HIGH, а QPS вправе отменить установку позже. Если занять слот заранее,
     * он останется занятым навсегда — заряда-то нет, и {@link #release}
     * никогда не придёт.</p>
     */
    public @NotNull Deny canActivate(@Nullable UUID playerUuid,
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
        if (playerUuid != null && !fits(perPlayer, playerUuid, maxPerPlayer, now)) {
            return Deny.PLAYER_LIMIT;
        }
        if (chunk != null && !fits(perChunk, ChunkKey.of(chunk), maxPerChunk, now)) {
            return Deny.CHUNK_LIMIT;
        }
        return Deny.NONE;
    }

    /**
     * <b>Вторая фаза:</b> записать кулдаун и занять слот. Вызывается только
     * когда действие действительно состоялось.
     */
    public void commit(@Nullable UUID playerUuid, @Nullable Chunk chunk, long cooldownMillis) {
        long now = System.currentTimeMillis();
        if (playerUuid != null && cooldownMillis > 0) {
            activations.put(playerUuid, now);
        }
        register(playerUuid, chunk, now);
    }

    /**
     * Только учёт уже зажжённого заряда (без кулдауна): поджог уже
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
        long now = System.currentTimeMillis();

        if (playerUuid != null && !fits(perPlayer, playerUuid, maxPerPlayer, now)) return false;
        if (chunk != null && !fits(perChunk, ChunkKey.of(chunk), maxPerChunk, now)) return false;

        register(playerUuid, chunk, now);
        return true;
    }

    private static <K> boolean fits(@NotNull Map<K, Counter> map, K key, int max, long now) {
        if (max <= 0) return true;
        Counter counter = map.get(key);
        return counter == null || counter.fits(max);
    }

    /** Увеличить счётчики (вызывается только после успешной проверки лимитов). */
    private void register(@Nullable UUID playerUuid, @Nullable Chunk chunk, long now) {
        if (playerUuid != null) {
            perPlayer.computeIfAbsent(playerUuid, k -> new Counter(now)).increment(now);
        }
        if (chunk != null) {
            perChunk.computeIfAbsent(ChunkKey.of(chunk), k -> new Counter(now)).increment(now);
        }
    }

    /** Снять учёт после взрыва. */
    public void release(@Nullable UUID playerUuid, @Nullable Chunk chunk) {
        long now = System.currentTimeMillis();
        releasePlayer(playerUuid, now);
        if (chunk != null) {
            releaseChunk(ChunkKey.of(chunk), now);
        }
    }

    /**
     * Снять учёт по координатам чанка, а не по объекту {@link Chunk}.
     *
     * <p>Заряд почти всегда взрывается не там, где появился: снаряд пушки
     * улетает на десятки блоков, обычный динамит смещается взрывом соседа.
     * Если снимать квоту по чанку детонации, счётчик исходного чанка
     * залипает, а счётчик чужого уменьшается ошибочно — то есть лимит
     * {@code max-primed-per-chunk} можно обойти. Разрешать же объект
     * {@code Chunk} по координатам нельзя: чанк может быть выгружен, а на
     * Folia обращение к чужому региону из потока взрыва и вовсе недопустимо.
     *
     * @param worldId мир, в котором заряд появился
     * @param cx      координата X чанка рождения
     * @param cz      координата Z чанка рождения
     */
    public void release(@Nullable UUID playerUuid, @Nullable UUID worldId, int cx, int cz) {
        long now = System.currentTimeMillis();
        releasePlayer(playerUuid, now);
        if (worldId != null) {
            releaseChunk(new ChunkKey(worldId, cx, cz), now);
        }
    }

    private void releasePlayer(@Nullable UUID playerUuid, long now) {
        if (playerUuid == null) return;
        Counter counter = perPlayer.get(playerUuid);
        if (counter != null && counter.decrement(now)) {
            perPlayer.remove(playerUuid);
        }
    }

    private void releaseChunk(@NotNull ChunkKey key, long now) {
        Counter counter = perChunk.get(key);
        if (counter != null && counter.decrement(now)) {
            perChunk.remove(key);
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

        // Хранить запись дольше cooldown × 2 нет смысла: после истечения
        // кулдауна она всё равно ни на что не влияет. Раньше здесь стояло
        // × 4, и карты жили вдвое дольше необходимого.
        activations.entrySet().removeIf(entry -> now - entry.getValue() > cooldown * 2);
        lastMessage.entrySet().removeIf(entry -> now - entry.getValue() > messageCooldown * 2);

        // «Зависшие» счётчики: заряд исчез без взрыва и release() не пришёл.
        perPlayer.values().removeIf(counter -> counter.isStale(now, STALE_MILLIS));
        perChunk.values().removeIf(counter -> counter.isStale(now, STALE_MILLIS));
    }
}
