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
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.util.Effects;

/**
 * Установка динамита блоком.
 *
 * <p>Пока {@code ignition.auto} выключен, предмет TNT ставится обычной
 * ванильной установкой — значит, {@link BlockPlaceEvent} могут отменить QPS,
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
        if (!type.placement().placeable()) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), LangKeys.CANNOT_PLACE_HERE);
            return;
        }

        Player player = event.getPlayer();
        Location location = block.getLocation();

        if (!hasPermission(player, type)) {
            plugin.lang().send(player, LangKeys.NO_PERMISSION);
            event.setCancelled(true);
            return;
        }

        World world = block.getWorld();
        boolean bypassWorld = player.hasPermission("qwetnts.bypass.world");

        if (!bypassWorld && !type.isAllowedIn(world, plugin.settings().worldFilter())) {
            plugin.lang().send(player, LangKeys.WORLD_DISABLED, "%world%", world.getName());
            event.setCancelled(true);
            return;
        }
        if (!bypassWorld && inSpawnRadius(location, plugin.settings().spawnRadius())) {
            plugin.lang().send(player, LangKeys.SPAWN_PROTECTED,
                    "%radius%", String.valueOf(plugin.settings().spawnRadius()));
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission("qwetnts.bypass.region")
                && !plugin.qps().canPlace(player, location)) {
            plugin.lang().send(player, LangKeys.REGION_DENIED);
            event.setCancelled(true);
            return;
        }

        // Только проверка: установку может отменить QPS на более позднем
        // приоритете, и тогда занимать слот анти-лага нельзя — release()
        // за несуществующий заряд никогда не придёт.
        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().canActivate(player.getUniqueId(), block.getChunk(),
                        type.cooldownMillis(plugin.settings().antiLag().activationCooldownMillis()),
                        type.maxPerPlayer(plugin.settings().antiLag().maxPrimedPerPlayer()),
                        type.maxPerChunk(plugin.settings().antiLag().maxPrimedPerChunk()));
        if (deny != AntiLag.Deny.NONE) {
            notifyDeny(player, deny);
            event.setCancelled(true);
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

        // Установка точно состоялась (MONITOR + событие не отменено) —
        // теперь можно занимать слот и записывать кулдаун.
        if (!player.hasPermission("qwetnts.bypass.antilag")) {
            plugin.antiLag().commit(player.getUniqueId(), block.getChunk(),
                    type.cooldownMillis(plugin.settings().antiLag().activationCooldownMillis()));
        }

        plugin.placedDynamites().put(block, type.id(), player.getUniqueId(), player.getName());

        Effects.play(plugin, type.effects().place(), block.getLocation().add(0.5, 0.5, 0.5));

        if (type.isAutoIgnite(plugin.settings().dynamites().autoIgnite())) return;

        plugin.lang().sendOr(player, type.messages().placed(), LangKeys.DYNAMITE_PLACED,
                "%name%", type.displayName());
        plugin.lang().send(player, LangKeys.IGNITION_HINT, "%name%", type.displayName());
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

        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        plugin.lang().send(player, LangKeys.DYNAMITE_REMOVED, "%name%", type.displayName());
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
            case COOLDOWN -> plugin.lang().send(player, LangKeys.COOLDOWN,
                    "%seconds%", plugin.lang().duration(
                            plugin.antiLag().cooldownRemaining(player.getUniqueId())));
            case PLAYER_LIMIT -> plugin.lang().send(player, LangKeys.PLAYER_LIMIT);
            case CHUNK_LIMIT -> plugin.lang().send(player, LangKeys.CHUNK_LIMIT);
            case NONE -> { /* разрешено */ }
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        if (!type.requiresPermission()) return true;
        return player.hasPermission(type.permission())
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
