package ru.qweyns.qwetnts.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.qweyns.qweprotectstones.api.QpsApi;
import org.qweyns.qweprotectstones.regions.Region;
import org.qweyns.qweprotectstones.regions.RegionFlag;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Materials;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Обработка взрыва кастомного динамита (§5.2 ТЗ):
 * <ul>
 *   <li>добавление breakable-блоков в {@code blockList()} с обязательной
 *       проверкой флага {@code EXPLOSION_DAMAGE} и защиты ядер приватов;</li>
 *   <li>поддержка works-in-water/works-in-lava (динамит не гаснет в жидкости);</li>
 *   <li>деградация (древние обломки → обсидиан) через отложенную setType,
 *       НЕ через blockList, не трогая ядра уникальных приватов;</li>
 *   <li>установка рейд-блоков на места разрушенного обсидиана;</li>
 *   <li>снижение урона сущностям по {@code cut-entity-damage};</li>
 *   <li>блокировка взрывов в запрещённых мирах и в радиусе спавна.</li>
 * </ul>
 *
 * <p>Приоритет HIGH — после фильтра LOW у QPS, но перед финальным разрушением.</p>
 */
public final class DynamiteExplodeListener implements Listener {

    private static final double DAMAGE_SEARCH_RADIUS = 8.0;

    private final QweTnts plugin;
    private final Random random = new Random();

    public DynamiteExplodeListener(QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        String kind = tnt.getPersistentDataContainer().get(
                plugin.keys().dynamiteKind, PersistentDataType.STRING);
        if (kind == null) return;

        DynamiteType type = plugin.registry().byId(kind);
        if (type == null) return;

        Location origin = event.getLocation();
        World world = origin.getWorld();
        if (world == null) return;

        if (!plugin.settings().worldFilter().isAllowed(world)
                || isInSpawnRadius(origin, plugin.settings().spawnRadius)) {
            event.blockList().clear();
            return;
        }

        Block centerBlock = origin.getBlock();
        if (Materials.isLiquid(centerBlock.getType())
                && !canExplodeInLiquid(type, centerBlock.getType())) {
            return;
        }

        float power = Math.max(0f, tnt.getYield());
        Set<Block> additions = collectBreakableBlocks(type, origin, power);

        QpsApi qps = plugin.qps();
        List<Block> finalAdditions = new ArrayList<>();
        List<Block> raidMarkCandidates = new ArrayList<>();
        List<BlockTransform> transforms = new ArrayList<>();

        for (Block b : additions) {
            if (!canBreak(qps, b)) continue;
            Material t = b.getType();
            if (type.transforms().containsKey(t)) {
                transforms.add(new BlockTransform(b, type.transforms().get(t)));
                continue;
            }
            finalAdditions.add(b);
            if (Materials.RAID_BLOCK_FAMILY.contains(t)) raidMarkCandidates.add(b);
        }

        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (!canBreak(qps, b)) { it.remove(); continue; }
            Material t = b.getType();
            if (type.transforms().containsKey(t) && !isCore(qps, b)) {
                it.remove();
                transforms.add(new BlockTransform(b, type.transforms().get(t)));
                continue;
            }
            if (Materials.RAID_BLOCK_FAMILY.contains(t)) raidMarkCandidates.add(b);
        }

        Set<Block> existing = new HashSet<>(event.blockList());
        for (Block b : finalAdditions) {
            if (existing.add(b)) event.blockList().add(b);
        }

        Schedulers.runAtLocation(plugin, origin, () -> {
            for (BlockTransform tr : transforms) {
                if (canModifyAfterExplosion(qps, tr.block())) {
                    tr.block().setType(tr.to(), false);
                }
            }
            int raid = 0;
            if (type.raidBlock().enabled()) {
                long dur = type.raidBlock().durationMs();
                for (Block b : raidMarkCandidates) {
                    Material t = b.getType();
                    if (Materials.isEmpty(t) || Materials.isLiquid(t)) {
                        plugin.raidBlocks().mark(b.getLocation(), dur);
                        raid++;
                    }
                }
            }
            if (raid > 0) plugin.stats().recordRaidBlock(raid);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        Location loc = event.getEntity().getLocation();
        TNTPrimed tnt = findNearbyTnt(loc, DAMAGE_SEARCH_RADIUS);
        if (tnt == null) return;

        String kind = tnt.getPersistentDataContainer().get(
                plugin.keys().dynamiteKind, PersistentDataType.STRING);
        if (kind == null) return;

        DynamiteType type = plugin.registry().byId(kind);
        if (type == null) return;

        int cut = type.cutEntityDamage();
        if (cut <= 0) return;
        double mult = Math.max(0.0, 1.0 - cut / 100.0);
        event.setDamage(event.getDamage() * mult);
    }

    private boolean canExplodeInLiquid(DynamiteType type, Material liquid) {
        return (liquid == Material.WATER && type.worksInWater())
                || (liquid == Material.LAVA && type.worksInLava());
    }

    private boolean isInSpawnRadius(Location loc, int radius) {
        if (radius <= 0) return false;
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().getEnvironment().equals(World.Environment.NORMAL)) return false;
        Location spawn = loc.getWorld().getSpawnLocation();
        int dx = Math.abs(loc.getBlockX() - spawn.getBlockX());
        int dz = Math.abs(loc.getBlockZ() - spawn.getBlockZ());
        return dx <= radius && dz <= radius;
    }

    private TNTPrimed findNearbyTnt(Location loc, double radius) {
        World world = loc.getWorld();
        if (world == null) return null;
        double r2 = radius * radius;
        TNTPrimed nearest = null;
        double best = r2;
        for (Entity e : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(e instanceof TNTPrimed tnt)) continue;
            double d = e.getLocation().distanceSquared(loc);
            if (d < best) { best = d; nearest = tnt; }
        }
        return nearest;
    }

    private Set<Block> collectBreakableBlocks(DynamiteType type, Location origin, float power) {
        Set<Block> out = new HashSet<>();
        World world = origin.getWorld();
        if (world == null) return out;
        Set<Material> mats = type.breakableBlocks().keySet();
        if (mats.isEmpty()) return out;

        int r = (int) Math.ceil(power);
        double r2 = power * power;
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double d = x * x + y * y + z * z;
                    if (d > r2) continue;
                    Block b = world.getBlockAt(ox + x, oy + y, oz + z);
                    Material t = b.getType();
                    if (!mats.contains(t)) continue;
                    var rule = type.breakableBlocks().get(t);
                    if (rule.chancePercent() >= 100
                            || random.nextInt(100) < rule.chancePercent()) {
                        out.add(b);
                    }
                }
            }
        }
        return out;
    }

    private boolean canBreak(QpsApi qps, Block b) {
        Location loc = b.getLocation();
        Region region = qps.getRegionAt(loc);
        if (region == null) return true;
        if (region.isCore(loc)) return false;
        return qps.flagAt(loc, RegionFlag.EXPLOSION_DAMAGE);
    }

    private boolean isCore(QpsApi qps, Block b) {
        Region r = qps.getRegionAt(b.getLocation());
        return r != null && r.isCore(b.getLocation());
    }

    private boolean canModifyAfterExplosion(QpsApi qps, Block b) {
        Location loc = b.getLocation();
        Region region = qps.getRegionAt(loc);
        if (region == null) return true;
        if (region.isCore(loc)) return false;
        return qps.flagAt(loc, RegionFlag.EXPLOSION_DAMAGE);
    }

    private record BlockTransform(Block block, Material to) {}
}
