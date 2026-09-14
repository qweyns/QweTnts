package ru.qweyns.qwetnts.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Materials;

/**
 * Запрет установки обсидиана/плачущего обсидиана/древних обломков на месте
 * рейд-блока (анти-феникс из механики HW Lite).
 *
 * <p>Приоритет LOWEST — раньше QPS (у него HIGH), чтобы блок даже не начали
 * ставить. Сообщение и время берутся из lang-файла.</p>
 */
public final class RaidBlockListener implements Listener {

    private final QweTnts plugin;

    public RaidBlockListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!Materials.RAID_BLOCK_FAMILY.contains(block.getType())) return;

        long until = plugin.raidBlocks().expiresAt(block.getLocation());
        if (until <= 0L) return;

        Player player = event.getPlayer();
        if (player.hasPermission("qwetnts.bypass.raidblock")) return;

        event.setCancelled(true);
        plugin.lang().send(player, "raid_block_denied",
                "%time%", plugin.lang().duration(until - System.currentTimeMillis()));
    }
}
