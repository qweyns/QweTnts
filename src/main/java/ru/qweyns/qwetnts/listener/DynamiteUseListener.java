package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Materials;

/**
 * Использование динамита в руке.
 *
 * <p>Два режима (переключатель {@code settings.dynamites.auto-ignite},
 * по умолчанию <b>выключен</b>):</p>
 * <ul>
 *   <li>{@code auto-ignite: false} — динамит <b>ставится блоком</b> и ждёт
 *       поджига (огниво, огонь, лава, другой взрыв). Именно так на HolyWorld:
 *       заряд не должен загораться сам в руке;</li>
 *   <li>{@code auto-ignite: true} — прежнее поведение: клик — и заряд горит.</li>
 * </ul>
 *
 * <p>Флаг можно переопределить для конкретного динамита ключом
 * {@code auto-ignite} в его файле.</p>
 */
public final class DynamiteUseListener implements Listener {

    private final QweTnts plugin;

    public DynamiteUseListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        DynamiteType type = plugin.registry().byItem(plugin.keys().dynamiteKind, item);
        if (type == null) return; // не наш динамит — пусть им занимается ваниль

        Player player = event.getPlayer();

        if (!hasPermission(player, type)) {
            plugin.lang().send(player, "no_permission");
            event.setCancelled(true);
            return;
        }

        World world = player.getWorld();
        boolean bypassWorld = player.hasPermission("qwetnts.bypass.world");

        if (!bypassWorld && !plugin.settings().worldFilter().isAllowed(world)) {
            plugin.lang().send(player, "world_disabled", "%world%", world.getName());
            event.setCancelled(true);
            return;
        }
        if (!bypassWorld && isInSpawnRadius(player.getLocation(), plugin.settings().spawnRadius())) {
            plugin.lang().send(player, "spawn_protected",
                    "%radius%", String.valueOf(plugin.settings().spawnRadius()));
            event.setCancelled(true);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && Materials.isInteractable(clicked)) {
            return; // сундук/верстак/дверь — не мешаем ванили
        }

        boolean autoIgnite = type.isAutoIgnite(plugin.settings().dynamites().autoIgnite());

        if (autoIgnite) {
            handleAutoIgnite(event, player, type, clicked);
            return;
        }

        // Автоподжог выключен: TNT ставится ванилью ( BlockPlaceEvent перехватим
        // отдельно ), для остальных материалов ставим блок сами.
        if (item.getType() == Material.TNT) return;

        handleManualPlace(event, player, type, clicked);
    }

    // ------------------------------------------------------------------
    // Режим auto-ignite: поджог сразу из руки
    // ------------------------------------------------------------------

    private void handleAutoIgnite(@NotNull PlayerInteractEvent event,
                                  @NotNull Player player,
                                  @NotNull DynamiteType type,
                                  @Nullable Block clicked) {
        Location spawn = spawnLocation(player, clicked, event.getBlockFace());
        if (spawn == null || spawn.getWorld() == null) return;

        if (!player.hasPermission("qwetnts.bypass.region") && !plugin.qps().canPlace(player, spawn)) {
            plugin.lang().send(player, "region_denied");
            event.setCancelled(true);
            return;
        }

        Chunk chunk = spawn.getChunk();
        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().tryActivate(player.getUniqueId(), chunk);
        if (deny != AntiLag.Deny.NONE) {
            notifyDeny(player, deny);
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        TNTPrimed primed = plugin.priming().igniteFromHand(spawn, type, player);
        if (primed == null) {
            // Не создали заряд — возвращаем квоту, иначе лимит «съест» попытку.
            plugin.antiLag().release(player.getUniqueId(), chunk);
            return;
        }

        consumeOne(player);
        plugin.stats().recordExplosion(type.explosionType());
    }

    // ------------------------------------------------------------------
    // Режим «поставить и поджечь»
    // ------------------------------------------------------------------

    private void handleManualPlace(@NotNull PlayerInteractEvent event,
                                   @NotNull Player player,
                                   @NotNull DynamiteType type,
                                   @Nullable Block clicked) {
        if (clicked == null || event.getBlockFace() == null) {
            plugin.lang().send(player, "cannot_place_here");
            event.setCancelled(true);
            return;
        }

        Block target = clicked.getRelative(event.getBlockFace());
        if (!canPlaceInto(target.getType())) {
            plugin.lang().send(player, "cannot_place_here");
            event.setCancelled(true);
            return;
        }

        Location location = target.getLocation();
        if (!player.hasPermission("qwetnts.bypass.region") && !plugin.qps().canPlace(player, location)) {
            plugin.lang().send(player, "region_denied");
            event.setCancelled(true);
            return;
        }

        Chunk chunk = target.getChunk();
        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().tryActivate(player.getUniqueId(), chunk);
        if (deny != AntiLag.Deny.NONE) {
            notifyDeny(player, deny);
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        target.setType(Material.TNT, false);
        plugin.placedDynamites().put(target, type.id(), player.getUniqueId(), player.getName());

        consumeOne(player);
        plugin.lang().send(player, "dynamite_placed", "%name%", type.displayName());
        plugin.lang().send(player, "ignition_hint", "%name%", type.displayName());
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private void notifyDeny(@NotNull Player player, @NotNull AntiLag.Deny deny) {
        switch (deny) {
            case COOLDOWN -> plugin.lang().send(player, "cooldown",
                    "%seconds%", formatSeconds(plugin.antiLag().cooldownRemaining(player.getUniqueId())));
            case PLAYER_LIMIT -> plugin.lang().send(player, "player_limit");
            case CHUNK_LIMIT -> plugin.lang().send(player, "chunk_limit");
            case NONE -> { /* разрешено */ }
        }
    }

    private @NotNull String formatSeconds(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        if (minutes > 0) {
            return plugin.lang().raw("time_minutes_seconds",
                    "%minutes%", String.valueOf(minutes),
                    "%seconds%", String.valueOf(rest));
        }
        return plugin.lang().raw("time_seconds", "%seconds%", String.valueOf(rest));
    }

    private void consumeOne(@NotNull Player player) {
        if (!plugin.settings().dynamites().consumeOnUse()) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        ItemStack main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir()) return;

        if (main.getAmount() > 1) {
            main.setAmount(main.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        return player.hasPermission("qwetnts.type." + type.id())
                || player.hasPermission("qwetnts.use");
    }

    private static boolean canPlaceInto(@Nullable Material material) {
        if (material == null) return false;
        return Materials.isEmpty(material)
                || material == Material.WATER
                || material == Material.LAVA
                || material == Material.FIRE;
    }

    private @Nullable Location spawnLocation(@NotNull Player player,
                                             @Nullable Block clicked,
                                             @Nullable BlockFace face) {
        if (clicked != null && face != null) {
            Location relative = clicked.getRelative(face).getLocation();
            return relative.add(0.5, 0.0, 0.5);
        }
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) return null;
        Vector direction = eye.getDirection().normalize().multiply(1.2);
        return eye.add(direction);
    }

    private boolean isInSpawnRadius(@Nullable Location location, int radius) {
        if (location == null || radius <= 0) return false;
        World world = location.getWorld();
        if (world == null) return false;
        if (world.getEnvironment() != World.Environment.NORMAL) return false;

        Location spawn = world.getSpawnLocation();
        if (spawn.getWorld() == null) return false;

        return Math.abs(location.getBlockX() - spawn.getBlockX()) <= radius
                && Math.abs(location.getBlockZ() - spawn.getBlockZ()) <= radius;
    }
}
