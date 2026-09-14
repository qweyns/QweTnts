package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Поджог установленного динамита.
 *
 * <p>Пока {@code auto-ignite} выключен, заряд лежит блоком TNT и ждёт огня.
 * Поджечь его можно:</p>
 * <ul>
 *   <li>огнивом или огненным зарядом (как обычный TNT);</li>
 *   <li>огнём, лавой, молнией, распространившимся огнём;</li>
 *   <li>другим взрывом — получается цепная детонация
 *       ({@code chain-radius}, {@code chain-delay-ticks});</li>
 *   <li>ударом, если включено {@code punch-ignites}.</li>
 * </ul>
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

        Player player = event.getPlayer();
        event.setCancelled(true);
        ignite(block, type, player);
    }

    /** Огниво/огненный заряд в руке (на случай, если BlockIgniteEvent не пришёл). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlintAndSteel(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        ItemStack item = event.getItem();
        if (item == null) return;

        boolean igniter = item.getType() == Material.FLINT_AND_STEEL
                || item.getType() == Material.FIRE_CHARGE;

        if (action == Action.RIGHT_CLICK_BLOCK) {
            if (!igniter) return;
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
            if (!hasPermission(player, type)) {
                plugin.lang().send(player, "no_permission");
                event.setCancelled(true);
                return;
            }

            event.setCancelled(true);
            ignite(clicked, type, player);
            return;
        }

        if (action == Action.LEFT_CLICK_BLOCK && plugin.settings().dynamites().punchIgnites()) {
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
            if (!hasPermission(player, type)) {
                plugin.lang().send(player, "no_permission");
                return;
            }

            event.setCancelled(true);
            ignite(clicked, type, player);
        }
    }

    /**
     * Цепная детонация: любой взрыв (наш, ванильный TNT, крипер) поджигает
     * установленные динамиты в радиусе {@code chain-radius}.
     *
     * <p>Сами блоки из списка разрушения убираются — иначе установленный
     * динамит просто исчез бы, не взорвавшись.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChainDetonation(@NotNull EntityExplodeEvent event) {
        if (plugin.placedDynamites().isEmpty()) return;

        List<Block> toIgnite = new ArrayList<>();
        for (Block block : event.blockList()) {
            if (plugin.placedDynamites().contains(block)) {
                toIgnite.add(block);
            }
        }
        if (toIgnite.isEmpty()) return;

        event.blockList().removeAll(toIgnite);

        long delay = plugin.settings().dynamites().chainDelayTicks();
        for (Block block : toIgnite) {
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
                ignite(block, type, placer == null ? null : plugin.getServer().getPlayer(placer));
            }, delay);
        }
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private void ignite(@NotNull Block block,
                        @NotNull DynamiteType type,
                        @Nullable Player player) {
        Chunk chunk = block.getChunk();
        UUID uuid = player != null ? player.getUniqueId() : null;

        // Поджог уже установленного заряда не обязан упираться в кулдаун
        // активации, иначе связку нельзя поджечь быстро, — но лимиты действуют.
        if (!plugin.antiLag().tryRegister(uuid, chunk)) {
            if (player != null) {
                plugin.lang().send(player, "player_limit");
            }
            return;
        }

        TNTPrimed primed = plugin.priming().ignite(block, type, player);
        if (primed == null) {
            plugin.antiLag().release(uuid, chunk);
            return;
        }

        if (player != null) {
            plugin.lang().send(player, "dynamite_ignited", "%name%", type.displayName());
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        return player.hasPermission("qwetnts.type." + type.id())
                || player.hasPermission("qwetnts.use");
    }
}
