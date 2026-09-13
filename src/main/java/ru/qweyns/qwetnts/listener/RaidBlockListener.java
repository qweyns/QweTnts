package ru.qweyns.qwetnts.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Materials;

import java.util.concurrent.TimeUnit;

/**
 * Запрет установки обсидиана/плачущего/древних обломков на месте рейд-блока.
 * Приоритет LOWEST — раньше QPS (HIGH), чтобы не допустить «феникса».
 */
public final class RaidBlockListener implements Listener {

    private final QweTnts plugin;

    public RaidBlockListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!Materials.RAID_BLOCK_FAMILY.contains(event.getBlock().getType())) return;
        long until = plugin.raidBlocks().expiresAt(event.getBlock().getLocation());
        if (until <= 0) return;

        event.setCancelled(true);
        long secs = Math.max(1, (until - System.currentTimeMillis()) / 1000L);
        plugin.lang().send(event.getPlayer(), "error.raid-block", formatDuration(secs));
    }

    private String formatDuration(long seconds) {
        long m = TimeUnit.SECONDS.toMinutes(seconds);
        long s = seconds - m * 60;
        if (m > 0) return m + " мин " + s + " сек";
        return s + " сек";
    }
}
