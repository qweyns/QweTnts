package ru.qweyns.qwetnts.raid;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Рейт-лимит алертов владельцу/участникам привата, чтобы при массовых взрывах
 * не спамить сообщениями «ваш приват атакован» тиками подряд.
 */
public final class AlertThrottle {

    private final Map<UUID, Long> lastRegionAlert = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public AlertThrottle(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /** @return true если отправить сообщение сейчас разрешено (и помечает таймстамп). */
    public boolean tryAcquire(UUID regionOrOwnerId) {
        long now = System.currentTimeMillis();
        Long prev = lastRegionAlert.putIfAbsent(regionOrOwnerId, now);
        if (prev == null) return true;
        if (now - prev < cooldownMs) return false;
        lastRegionAlert.put(regionOrOwnerId, now);
        return true;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        lastRegionAlert.entrySet().removeIf(e -> now - e.getValue() > cooldownMs * 2);
    }
}
