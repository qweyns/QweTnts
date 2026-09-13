package ru.qweyns.qwetnts.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.qweyns.qweprotectstones.regions.event.RegionDeleteEvent;
import ru.qweyns.qwetnts.QweTnts;

import java.util.logging.Level;

/** Логирует уничтожение приватов рейдом для расследований и статистики. */
public final class RaidLoggingListener implements Listener {

    private final QweTnts plugin;

    public RaidLoggingListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDelete(RegionDeleteEvent event) {
        if (event.getReason() != RegionDeleteEvent.Reason.DESTROYED_BY_RAID) return;
        var region = event.getRegion();
        plugin.getLogger().log(Level.INFO,
                "[RAID] Приват {0} (тип {1}), владелец {2} уничтожен. Последний атакующий: {3}",
                new Object[]{
                        region.getShortId(),
                        region.getTypeId(),
                        region.getOwnerName(),
                        region.getLastAttackerName().isBlank() ? "?" : region.getLastAttackerName()
                });
        plugin.stats().recordRegionDestroyed(null);
    }
}
