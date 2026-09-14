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
import ru.qweyns.qwetnts.util.Effects;
import ru.qweyns.qwetnts.util.Materials;

/**
 * Использование динамита в руке.
 *
 * <p>Режим поджога задаётся у каждого динамита своим ключом
 * {@code ignition.auto: true/false} в файле {@code dynamites/*.yml}.
 * Если ключ не задан, работает глобальное
 * {@code settings.dynamites.auto-ignite} из config.yml (по умолчанию
 * {@code false} — динамит ставится блоком и ждёт огня).</p>
 *
 * <p>Помимо поджога здесь же: право доступа, фильтр мира, радиус спавна,
 * свои лимиты анти-лага, расход предмета, эффекты и сообщения.</p>
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

        if (!bypassWorld && !type.isAllowedIn(world, plugin.settings().worldFilter())) {
            plugin.lang().send(player, "world_disabled", "%world%",
                    world == null ? "" : world.getName());
            event.setCancelled(true);
            return;
        }
        if (!bypassWorld && inSpawnRadius(player.getLocation(), plugin.settings().spawnRadius())) {
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

        if (type.isAutoIgnite(plugin.settings().dynamites().autoIgnite())) {
            handleAutoIgnite(event, player, type, clicked);
            return;
        }

        if (!type.placement().placeable()) {
            // Динамит нельзя поставить — значит, поджигаем сразу, как раньше.
            handleAutoIgnite(event, player, type, clicked);
            return;
        }

        // TNT ставит vanilla — поймаем в BlockPlaceEvent (там же сработает
        // защита приватов QPS). Остальные материалы ставим сами.
        if (item.getType() == Material.TNT) return;

        handleManualPlace(event, player, type, clicked);
    }

    // ------------------------------------------------------------------
    // Поджог сразу из руки
    // ------------------------------------------------------------------

    private void handleAutoIgnite(@NotNull PlayerInteractEvent event,
                                  @NotNull Player player,
                                  @NotNull DynamiteType type,
                                  @Nullable Block clicked) {
        Location spawn = spawnLocation(player, clicked, event.getBlockFace());
        if (spawn == null || spawn.getWorld() == null) return;

        if (!player.hasPermission("qwetnts.bypass.region")
                && !plugin.qps().canPlace(player, spawn)) {
            plugin.lang().send(player, "region_denied");
            event.setCancelled(true);
            return;
        }

        Chunk chunk = spawn.getChunk();
        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().tryActivate(player.getUniqueId(), chunk,
                        type.cooldownMillis(plugin.settings().antiLag().activationCooldownMillis()),
                        type.maxPerPlayer(plugin.settings().antiLag().maxPrimedPerPlayer()),
                        type.maxPerChunk(plugin.settings().antiLag().maxPrimedPerChunk()));
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

        consumeOne(player, type);
        plugin.stats().recordExplosion(type.explosion().type());
        plugin.lang().sendOr(player, type.messages().ignited(), "dynamite_ignited",
                "%name%", type.displayName());
    }

    // ------------------------------------------------------------------
    // Поставить блоком (поджог отдельно)
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
        if (!player.hasPermission("qwetnts.bypass.region")
                && !plugin.qps().canPlace(player, location)) {
            plugin.lang().send(player, "region_denied");
            event.setCancelled(true);
            return;
        }

        Chunk chunk = target.getChunk();
        AntiLag.Deny deny = player.hasPermission("qwetnts.bypass.antilag")
                ? AntiLag.Deny.NONE
                : plugin.antiLag().tryActivate(player.getUniqueId(), chunk,
                        type.cooldownMillis(plugin.settings().antiLag().activationCooldownMillis()),
                        type.maxPerPlayer(plugin.settings().antiLag().maxPrimedPerPlayer()),
                        type.maxPerChunk(plugin.settings().antiLag().maxPrimedPerChunk()));
        if (deny != AntiLag.Deny.NONE) {
            notifyDeny(player, deny);
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        target.setType(Material.TNT, false);
        plugin.placedDynamites().put(target, type.id(), player.getUniqueId(), player.getName());

        consumeOne(player, type);
        Effects.play(plugin, type.effects().place(), target.getLocation().add(0.5, 0.5, 0.5));

        plugin.lang().sendOr(player, type.messages().placed(), "dynamite_placed",
                "%name%", type.displayName());
        plugin.lang().send(player, "ignition_hint", "%name%", type.displayName());
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private void notifyDeny(@NotNull Player player, @NotNull AntiLag.Deny deny) {
        switch (deny) {
            case COOLDOWN -> plugin.lang().send(player, "cooldown",
                    "%seconds%", plugin.lang().duration(
                            plugin.antiLag().cooldownRemaining(player.getUniqueId())));
            case PLAYER_LIMIT -> plugin.lang().send(player, "player_limit");
            case CHUNK_LIMIT -> plugin.lang().send(player, "chunk_limit");
            case NONE -> { /* разрешено */ }
        }
    }

    /** Расход: глобальный переключатель И свой у динамита; в креативе не тратим. */
    private void consumeOne(@NotNull Player player, @NotNull DynamiteType type) {
        if (!plugin.settings().dynamites().consumeOnUse()) return;
        if (!type.placement().consumeOnUse()) return;
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
        if (!type.requiresPermission()) return true;
        return player.hasPermission(type.permission())
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
            return clicked.getRelative(face).getLocation().add(0.5, 0.0, 0.5);
        }
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) return null;
        return eye.add(eye.getDirection().normalize().multiply(1.2));
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
