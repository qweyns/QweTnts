package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteType;

/**
 * Активация динамита: правый клик предметом-динамитом в основной руке
 * → спавн TNTPrimed с PDC-меткой, расход предмета, настройка фитиля/мощности.
 * Атрибуция атакующего идёт через {@link TNTPrimed#getSource()} → QPS сам
 * засчитает урон владельцу.
 */
public final class DynamiteActivateListener implements Listener {

    private final QweTnts plugin;

    public DynamiteActivateListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack hand = event.getItem();
        DynamiteType type = plugin.registry().byItem(plugin.keys().dynamiteKind, hand);
        if (type == null) return;

        Player player = event.getPlayer();
        if (!hasUsePermission(player, type)) {
            plugin.lang().send(player, "error.no-permission");
            event.setCancelled(true);
            return;
        }

        World world = player.getWorld();
        if (!plugin.settings().worldFilter().isAllowed(world)) {
            plugin.lang().send(player, "error.world-disabled", world.getName());
            event.setCancelled(true);
            return;
        }
        if (isInSpawnRadius(player.getLocation(), plugin.settings().spawnRadius)) {
            plugin.lang().send(player, "error.spawn-protected", plugin.settings().spawnRadius);
            event.setCancelled(true);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (isInteractable(clicked)) return;
        }

        Location spawnLoc = spawnLocation(player, clicked, event.getBlockFace());
        World sw = spawnLoc.getWorld();
        if (sw == null) return;

        // Антилаг-лимиты
        Chunk chunk = spawnLoc.getChunk();
        String errKey = plugin.antiLag().tryAcquire(player.getUniqueId(), chunk);
        if (errKey != null) {
            plugin.lang().send(player, errKey);
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        if (!player.getGameMode().isCreative() && hand != null) {
            hand.setAmount(hand.getAmount() - 1);
        }

        TNTPrimed tnt = (TNTPrimed) sw.spawnEntity(spawnLoc, EntityType.TNT);
        tnt.setFuseTicks(type.fuseTicks());
        tnt.setYield(type.power());
        tnt.setIsIncendiary(false);
        tnt.setSource(player);
        tnt.getPersistentDataContainer().set(
                plugin.keys().dynamiteKind,
                PersistentDataType.STRING,
                type.id());

        sw.playSound(spawnLoc, Sound.ENTITY_TNT_PRIMED,
                SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    /**
     * При взрыве кастомной ТНТ декрементим счётчики антилага
     * (иначе бы они росли вечно). Деспавн без взрыва (например /kill)
     * не учитываем — это редкий админский кейс, счётчики всё равно обнуляются при релоаде.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        release(event.getEntity());
    }

    private void release(org.bukkit.entity.Entity e) {
        if (!(e instanceof TNTPrimed tnt)) return;
        if (!tnt.getPersistentDataContainer().has(plugin.keys().dynamiteKind,
                PersistentDataType.STRING)) return;
        if (tnt.getSource() instanceof Player p) {
            plugin.antiLag().release(p.getUniqueId(), e.getLocation().getChunk());
        } else {
            plugin.antiLag().release(null, e.getLocation().getChunk());
        }
    }

    private boolean hasUsePermission(Player p, DynamiteType type) {
        if (p.hasPermission("qwetnts.type." + type.id())) return true;
        return p.hasPermission("qwetnts.use");
    }

    private boolean isInSpawnRadius(Location loc, int radius) {
        if (radius <= 0) return false;
        if (!loc.getWorld().getEnvironment().equals(World.Environment.NORMAL)) return false;
        Location spawn = loc.getWorld().getSpawnLocation();
        if (spawn.getWorld() == null) return false;
        int dx = Math.abs(loc.getBlockX() - spawn.getBlockX());
        int dz = Math.abs(loc.getBlockZ() - spawn.getBlockZ());
        return dx <= radius && dz <= radius;
    }

    private Location spawnLocation(Player player, Block clicked, BlockFace face) {
        if (clicked != null && face != null) {
            Location rel = clicked.getRelative(face).getLocation();
            return rel.add(0.5, 0.0, 0.5);
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().clone().normalize().multiply(1.2);
        return eye.add(dir);
    }

    private boolean isInteractable(Block b) {
        return b.getType().isInteractable();
    }
}
