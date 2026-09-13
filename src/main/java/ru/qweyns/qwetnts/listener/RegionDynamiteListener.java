package ru.qweyns.qwetnts.listener;

import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.qweyns.qweprotectstones.regions.event.RegionDamageEvent;
import org.qweyns.qweprotectstones.regions.event.RegionExplosionTypeEvent;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.stats.ExplosionStats;

/**
 * Интеграция с QPS: классификация взрыва (§3.1 ТЗ), корректировка урона (§3.2)
 * и статистика взрывов для bStats.
 */
public final class RegionDynamiteListener implements Listener {

    private final QweTnts plugin;

    public RegionDynamiteListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClassify(RegionExplosionTypeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        String kind = tnt.getPersistentDataContainer().get(
                plugin.keys().dynamiteKind,
                PersistentDataType.STRING);
        if (kind == null) return;
        DynamiteType type = plugin.registry().byId(kind);
        if (type == null) return;

        event.setExplosionType(type.explosionType());
        event.setDamageRadiusMultiplier(type.radiusMultiplier());
        plugin.stats().recordExplosion(type.explosionType());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegionDamage(RegionDamageEvent event) {
        DynamiteType type = plugin.registry().byExplosionType(event.getExplosionType());
        if (type == null) return;
        if (type.siegeDamage() > 1) {
            event.setDamage(type.siegeDamage());
        }
    }
}
