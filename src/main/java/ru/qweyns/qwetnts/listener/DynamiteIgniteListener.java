package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Chain;
import ru.qweyns.qwetnts.dynamite.DynamiteType.IgniteCause;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Поджог установленного динамита.
 *
 * <p>Пока {@code ignition.auto} выключен, заряд лежит блоком TNT и ждёт огня.
 * Каким именно огнём его можно поджечь, решает список
 * {@code ignition.causes} в файле динамита: огниво, огненный заряд, огонь,
 * лава, молния, взрыв, редстоун, удар.</p>
 *
 * <p>Ванильный поджог всегда отменяется: блок удаляется и вместо него
 * создаётся {@link TNTPrimed} с PDC-меткой, мощностью и фитилём из конфига —
 * иначе взрыв был бы обычным TNT.</p>
 */
public final class DynamiteIgniteListener implements Listener {

    private final QweTnts plugin;

    public DynamiteIgniteListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Поджог огнём, огнивом, лавой, молнией, распространением огня. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(@NotNull BlockIgniteEvent event) {
        Block block = event.getBlock();
        PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(block);
        if (placed == null) return;

        DynamiteType type = plugin.registry().byId(placed.typeId());
        if (type == null) {
            plugin.placedDynamites().remove(block);
            return;
        }

        if (!type.canBeIgnitedBy(map(event.getCause()))) return;

        Player player = event.getPlayer();
        if (player != null && !hasPermission(player, type)) {
            plugin.lang().send(player, "no_permission");
            return;
        }

        event.setCancelled(true);
        ignite(block, type, player);
    }

    /** Огниво / огненный заряд в руке и удар кулаком по заряду. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(clicked);
        if (placed == null) return;

        DynamiteType type = plugin.registry().byId(placed.typeId());
        if (type == null) {
            plugin.placedDynamites().remove(clicked);
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        IgniteCause cause;
        if (action == Action.LEFT_CLICK_BLOCK) {
            cause = IgniteCause.PUNCH;
        } else if (item != null && item.getType() == Material.FLINT_AND_STEEL) {
            cause = IgniteCause.FLINT_AND_STEEL;
        } else if (item != null && item.getType() == Material.FIRE_CHARGE) {
            cause = IgniteCause.FIRE_CHARGE;
        } else {
            return;
        }

        if (!type.canBeIgnitedBy(cause)) return;
        if (!hasPermission(player, type)) {
            plugin.lang().send(player, "no_permission");
            return;
        }

        event.setCancelled(true);
        ignite(clicked, type, player);
    }

    /**
     * Цепная детонация: любой взрыв поджигает установленные динамиты
     * в радиусе {@code ignition.chain.radius}.
     *
     * <p>Сами блоки из списка разрушения убираются — иначе установленный
     * динамит просто исчез бы, не взорвавшись.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChainDetonation(@NotNull EntityExplodeEvent event) {
        if (plugin.placedDynamites().isEmpty()) return;

        LocationAndBlocks found = collectChainTargets(event);
        if (found == null) return;

        long delay = Math.max(1L, found.delay());
        for (Block block : found.blocks()) {
            PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(block);
            if (placed == null) continue;

            DynamiteType type = plugin.registry().byId(placed.typeId());
            if (type == null) {
                plugin.placedDynamites().remove(block);
                continue;
            }

            UUID placer = placed.placedBy();
            Schedulers.runAtLocation(plugin, block.getLocation(), () -> {
                if (block.getType() != Material.TNT) return; // успели сломать
                igniteByChain(block, type, placer == null ? null : plugin.getServer().getPlayer(placer));
            }, delay);
        }
    }

    private record LocationAndBlocks(@NotNull List<Block> blocks, long delay) {
    }

    private @Nullable LocationAndBlocks collectChainTargets(@NotNull EntityExplodeEvent event) {
        List<Block> toIgnite = new ArrayList<>();
        long delay = 1L;

        for (Block block : event.blockList()) {
            PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(block);
            if (placed == null) continue;

            DynamiteType type = plugin.registry().byId(placed.typeId());
            if (type == null) continue;
            if (!type.canBeIgnitedBy(IgniteCause.EXPLOSION)) continue;
            if (!type.ignition().chain().canBeChained()) continue;

            toIgnite.add(block);
            delay = Math.max(delay, type.ignition().chain().delayTicks());
        }

        if (toIgnite.isEmpty()) return null;

        // Заряды, которые этот взрыв поджёг, не должны быть разрушены им же.
        event.blockList().removeAll(toIgnite);
        return new LocationAndBlocks(toIgnite, delay);
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private void ignite(@NotNull Block block,
                        @NotNull DynamiteType type,
                        @Nullable Player player) {
        long delay = Math.max(0L, type.ignition().delayTicks());
        if (delay > 0L) {
            Schedulers.runAtLocation(plugin, block.getLocation(),
                    () -> igniteNow(block, type, player), delay);
            return;
        }
        igniteNow(block, type, player);
    }

    private void igniteByChain(@NotNull Block block,
                               @NotNull DynamiteType type,
                               @Nullable Player player) {
        igniteNow(block, type, player);
    }

    private void igniteNow(@NotNull Block block,
                           @NotNull DynamiteType type,
                           @Nullable Player player) {
        if (!plugin.placedDynamites().contains(block)) return;

        Chunk chunk = block.getChunk();
        UUID uuid = player != null ? player.getUniqueId() : null;

        // Поджог уже установленного заряда не обязан упираться в кулдаун
        // активации, иначе связку нельзя поджечь быстро, — но лимиты действуют.
        if (!plugin.antiLag().tryRegister(uuid, chunk,
                type.maxPerPlayer(plugin.settings().antiLag().maxPrimedPerPlayer()),
                type.maxPerChunk(plugin.settings().antiLag().maxPrimedPerChunk()))) {
            if (player != null) plugin.lang().send(player, "player_limit");
            return;
        }

        TNTPrimed primed = plugin.priming().ignite(block, type, player);
        if (primed == null) {
            plugin.antiLag().release(uuid, chunk);
            return;
        }

        if (player != null) {
            plugin.lang().sendOr(player, type.messages().ignited(), "dynamite_ignited",
                    "%name%", type.displayName());
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        if (!type.requiresPermission()) return true;
        return player.hasPermission(type.permission())
                || player.hasPermission("qwetnts.use");
    }

    /** Причина поджога от vanilla → наше перечисление из конфига. */
    private static @NotNull IgniteCause map(@NotNull BlockIgniteEvent.IgniteCause cause) {
        return switch (cause) {
            case FLINT_AND_STEEL -> IgniteCause.FLINT_AND_STEEL;
            case FIREBALL -> IgniteCause.FIRE_CHARGE;
            case LAVA -> IgniteCause.LAVA;
            case LIGHTNING -> IgniteCause.LIGHTNING;
            case EXPLOSION, ENDER_CRYSTAL -> IgniteCause.EXPLOSION;
            case SPREAD, ARROW -> IgniteCause.FIRE;
        };
    }
}
