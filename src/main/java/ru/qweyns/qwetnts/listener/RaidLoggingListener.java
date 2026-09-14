package ru.qweyns.qwetnts.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.qweyns.qweprotectstones.regions.event.RegionDeleteEvent;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.QweTnts;

import java.util.logging.Level;

/**
 * Журнал уничтоженных рейдом приватов: только самое важное, чтобы админ мог
 * разобрать спорную ситуацию.
 *
 * <p>Формат строки — из lang-файла (ключ {@code log.region_destroyed}).</p>
 */
public final class RaidLoggingListener implements Listener {

    private final QweTnts plugin;

    public RaidLoggingListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDelete(@NotNull RegionDeleteEvent event) {
        if (event.getReason() != RegionDeleteEvent.Reason.DESTROYED_BY_RAID) return;

        var region = event.getRegion();
        if (region == null) return;

        String attacker = region.getLastAttackerName();
        if (attacker == null || attacker.isBlank()) {
            attacker = plugin.lang().raw(LangKeys.UNKNOWN_OWNER);
        }

        plugin.getLogger().log(Level.INFO, plugin.lang().raw(LangKeys.LOG_REGION_DESTROYED,
                "%id%", String.valueOf(region.getShortId()),
                "%type%", String.valueOf(region.getTypeId()),
                "%owner%", String.valueOf(region.getOwnerName()),
                "%attacker%", attacker));

        // UUID атакующего в API QPS не публикуется — ограничиваемся именем.
        plugin.stats().recordRegionDestroyed(null);
    }
}
