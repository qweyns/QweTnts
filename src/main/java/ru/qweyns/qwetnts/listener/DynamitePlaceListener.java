package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;

/**
 * Установка динамита блоком.
 *
 * <p>Пока {@code auto-ignite} выключен, предмет TNT ставится обычной ванильной
 * установкой — значит, событие {@link BlockPlaceEvent} могут отменить QPS,
 * WorldGuard и прочие плагины. Мы это уважаем: свои проверки делаем на
 * приоритете HIGH, а запись в реестр — только на MONITOR, когда событие
 * точно не отменено.</p>
 */
public final class DynamitePlaceListener implements Listener {

    private final QweTnts plugin;

    public DynamitePlaceListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Наши проверки — до того, как установка станет фактом. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(@NotNull BlockPlaceEvent event) {
        DynamiteType type = typeOf(event.getItemInHand());
        if (type == null) return;

        Block block = event.getBlock();
        if (block.getType() != Material.TNT) return;

        Player player = event.getPlayer();
        Location location = block.getLocation();

        if (!hasPermission(player, type)) {
            plugin.lang().send(player, "no_permission");
            event.setCancelled(true);
            return;
        }

        World world = block.getWorld();
        boolean bypassWorld = player.hasPermission("qwetnts.bypass.world");

        if (!bypassWorld && !plugin.settings().worldFilter().isAllowed(world)) {
            plugin.lang().send(player, "world_disabled", "%world%", world.getName());
            event.setCancelled(true);
            return;
        }
        if (!bypassWorld && inSpawnRadius(location, plugin.settings().spawnRadius())) {
            plugin.lang().send(player, "spawn_protected",
                    "%radius%", String.valueOf(plugin.settings().spawnRadius()));
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission("qwetnts.bypass.region") && !plugin.qps().canPlace(player, location)) {
            plugin.lang().send(player, "region_denied");
            event.setCancelled(true);
            return;
        }

        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().tryActivate(player.getUniqueId(), block.getChunk());
        if (deny != AntiLag.Deny.NONE) {
            notifyDeny(player, deny);
            event.setCancelled(true);
            return;
        }
    }

    /** Запись установленного динамита — только если событие не отменили. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceRecord(@NotNull BlockPlaceEvent event) {
        DynamiteType type = typeOf(event.getItemInHand());
        if (type == null) return;

        Block block = event.getBlock();
        if (block.getType() != Material.TNT) return;

        Player player = event.getPlayer();
        plugin.placedDynamites().put(block, type.id(), player.getUniqueId(), player.getName());

        if (type.needsManualIgnition(plugin.settings().dynamites().autoIgnite())) {
            plugin.lang().send(player, "dynamite_placed", "%name%", type.displayName());
            plugin.lang().send(player, "ignition_hint", "%name%", type.displayName());
        }
    }

    /** Сломал установленный динамит — возвращаем предмет с PDC-меткой. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(block);
        if (placed == null) return;

        plugin.placedDynamites().remove(block);
        event.setDropItems(false);

        DynamiteType type = plugin.registry().byId(placed.typeId());
        Player player = event.getPlayer();

        if (type == null) {
            plugin.getLogger().warning("Установленный динамит " + placed.typeId()
                    + " больше не существует в конфиге — блок сломан без возврата.");
            return;
        }

        ItemStack drop = type.item();
        if (drop.getType().isAir()) return;

        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().dropItemNaturally(dropAt, drop);
        plugin.lang().send(player, "dynamite_removed", "%name%", type.displayName());
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private @Nullable DynamiteType typeOf(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return plugin.registry().byItem(plugin.keys().dynamiteKind, item);
    }

    private void notifyDeny(@NotNull Player player, @NotNull AntiLag.Deny deny) {
        switch (deny) {
            case COOLDOWN -> plugin.lang().send(player, "cooldown",
                    "%seconds%", String.valueOf(
                            Math.max(1L, plugin.antiLag().cooldownRemaining(player.getUniqueId()) / 1000L)));
            case PLAYER_LIMIT -> plugin.lang().send(player, "player_limit");
            case CHUNK_LIMIT -> plugin.lang().send(player, "chunk_limit");
            case NONE -> { /* разрешено */ }
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        return player.hasPermission("qwetnts.type." + type.id())
                || player.hasPermission("qwetnts.use");
    }

    private boolean inSpawnRadius(@Nullable Location location, int radius) {
        if (location == null || radius <= 0) return false;
        World world = location.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) return false;

        Location spawn = world.getSpawnLocation();
        if (spawn.getWorld() == null) return false;

        return Math.abs(location.getBlockX() - spawn.getBlockX()) <= radius
                && Math.abs(location.getBlockZ() - spawn.getBlockZ()) <= radius;
    }
}
