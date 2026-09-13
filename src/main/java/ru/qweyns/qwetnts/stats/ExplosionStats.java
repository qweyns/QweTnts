package ru.qweyns.qwetnts.stats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Счётчики взрывов и разрушенных приватов (для bStats и логов). */
public final class ExplosionStats {

    private final Map<String, LongAdder> byType = new ConcurrentHashMap<>();
    private final LongAdder raidBlocksTotal = new LongAdder();
    private final LongAdder destroyedRegions = new LongAdder();
    private final Map<UUID, LongAdder> destroyedByPlayer = new ConcurrentHashMap<>();

    public void recordExplosion(String explosionType) {
        byType.computeIfAbsent(explosionType, k -> new LongAdder()).increment();
    }

    public void recordRaidBlock(int count) {
        raidBlocksTotal.add(count);
    }

    public void recordRegionDestroyed(UUID attackerId) {
        destroyedRegions.increment();
        if (attackerId != null) {
            destroyedByPlayer.computeIfAbsent(attackerId, k -> new LongAdder()).increment();
        }
    }

    public Map<String, LongAdder> byType() {
        return byType;
    }

    public long raidBlocksTotal() {
        return raidBlocksTotal.sum();
    }

    public long destroyedRegions() {
        return destroyedRegions.sum();
    }

    public long countByType(String type) {
        LongAdder a = byType.get(type);
        return a == null ? 0 : a.sum();
    }
}
