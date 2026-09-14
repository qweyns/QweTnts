package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.BlastMath;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.BreakRule;
import ru.qweyns.qwetnts.dynamite.DynamiteType.Breaking;
import ru.qweyns.qwetnts.dynamite.DynamiteType.TransformRule;
import ru.qweyns.qwetnts.util.Effects;
import ru.qweyns.qwetnts.util.Materials;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Основная логика взрыва кастомного динамита.
 *
 * <h2>Что здесь решается</h2>
 * <ol>
 *   <li><b>Что можно ломать.</b> Vanilla кладёт в {@code blockList} всё, что
 *       разрушил бы обычный TNT — то есть обсидиан туда не попадает вообще.
 *       Поэтому прочные блоки ищем отдельным сканом и решаем судьбу по
 *       правилам {@code breaking} из файла динамита.</li>
 *   <li><b>Приваты QPS.</b> Ядро не трогаем никогда, остальные блоки — только
 *       при флаге {@code EXPLOSION_DAMAGE} (правило §5.2 ТЗ).</li>
 *   <li><b>Деградация древних обломков.</b> {@code ANCIENT_DEBRIS → OBSIDIAN}
 *       ставится отдельной задачей, а не «в лоб» внутри события.</li>
 *   <li><b>Рейд-блоки.</b> Отмечаются только те позиции, где блок реально
 *       исчез (проверяем через тик после взрыва).</li>
 *   <li><b>Анти-лаг.</b> Скан ограничен {@code breaking.scan-radius} и
 *       глобальным потолком, есть жёсткий предел {@code explosion.max-blocks},
 *       а изменения блоков группируются по чанкам (на Folia это ещё и
 *       гарантия, что мы не трогаем чужой регион).</li>
 * </ol>
 */
public final class DynamiteExplodeListener implements Listener {

    /** Что сделать с блоком после события. */
    private enum Action { REMOVE_SILENTLY, TRANSFORM, MARK_RAID }

    private record Pending(@NotNull Block block,
                           @NotNull Action action,
                           @Nullable Material to,
                           long raidDurationMs,
                           int raidRadius) {
    }

    private record ChunkRef(UUID world, int cx, int cz) {
    }

    private final QweTnts plugin;

    public DynamiteExplodeListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Разрушение блоков
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(@NotNull EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        String kind = tnt.getPersistentDataContainer()
                .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
        if (kind == null || kind.isBlank()) return;

        DynamiteType type = plugin.registry().byId(kind);
        if (type == null) return;

        Location center = event.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        Player source = sourceOf(tnt);
        Chunk chunk = center.getChunk();
        plugin.antiLag().release(source != null ? source.getUniqueId() : null, chunk);

        plugin.stats().recordExplosion(type.explosion().type());
        if (type.explosion().power() > plugin.settings().antiLag().largeExplosionThresholdPower()) {
            plugin.getLogger().warning("Очень мощный динамит '" + type.id()
                    + "' (power=" + type.explosion().power() + ") в мире " + world.getName()
                    + " — возможны просадки тиков.");
        }

        Effects.play(plugin, type.effects().explode(), center);

        // Мир и зона спавна.
        if (!type.isAllowedIn(world, plugin.settings().worldFilter())) {
            event.blockList().clear();
            return;
        }
        if (inSpawnRadius(center, plugin.settings().spawnRadius())) {
            event.blockList().clear();
            return;
        }

        // Вода/лава гасят динамит, если это не разрешено явно.
        Material centerMaterial = center.getBlock().getType();
        if (centerMaterial == Material.WATER && !type.explosion().worksInWater()) {
            event.blockList().clear();
            return;
        }
        if (centerMaterial == Material.LAVA && !type.explosion().worksInLava()) {
            event.blockList().clear();
            return;
        }

        // Если vanilla-список не нужен, ломаем только по своим правилам.
        if (!type.breaking().vanillaBlockList()) {
            event.blockList().clear();
        }

        List<Pending> pending = new ArrayList<>();
        processBlockList(event, type, pending);
        scanHardBlocks(center, type, event.blockList(), pending);
        applyBlockBudget(type, event.blockList(), pending);

        if (!pending.isEmpty()) {
            schedulePending(pending);
        }
    }

    /**
     * Разбор списка от vanilla: что оставить, что убрать, что превратить.
     *
     * <p>Блоки из {@code blockList} удаляются из списка, если мы берём
     * судьбу на себя (не выпадает дроп, нужна трансформация) — иначе
     * сервер всё сделает сам.</p>
     */
    private void processBlockList(@NotNull EntityExplodeEvent event,
                                  @NotNull DynamiteType type,
                                  @NotNull List<Pending> pending) {
        Breaking breaking = type.breaking();
        RandomGenerator random = BlastMath.random();

        Iterator<Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            Material material = block.getType();

            if (Materials.isIndestructible(material)) {
                iterator.remove();
                continue;
            }
            if (!plugin.qps().canExplode(block)) {
                iterator.remove();
                continue;
            }

            BreakRule rule = breaking.ruleFor(material);
            if (rule != null) {
                // Явное правило полностью перекрывает расчёт сопротивления:
                // именно так C4 и Разрывная волна ломают обсидиан.
                if (!BlastMath.roll(rule.breakChance(), random)) {
                    iterator.remove();
                    continue;
                }
                if (!rule.keepDrop(random)) {
                    iterator.remove();
                    pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0));
                }
                queueRaid(type, block, material, pending);
                continue;
            }

            double resistance = Materials.blastResistance(block);
            if (!BlastMath.canBreak(resistance, type.explosion().power(),
                    breaking.maxResistance(), breaking.resistanceScale())) {
                iterator.remove();
                continue;
            }

            TransformRule transform = type.transforms().get(material);
            if (transform != null) {
                iterator.remove();
                if (BlastMath.roll(transform.chance(), random)) {
                    pending.add(new Pending(block, Action.TRANSFORM, transform.to(), 0L, 0));
                }
                continue;
            }

            if (!BlastMath.roll(breaking.defaultDropChance(), random)) {
                iterator.remove();
                pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0));
            }

            queueRaid(type, block, material, pending);
        }
    }

    /**
     * Досмотр прочных блоков, которых vanilla не дала в {@code blockList}
     * (обсидиан, плачущий обсидиан, древние обломки, вода, лава).
     */
    private void scanHardBlocks(@NotNull Location center,
                                @NotNull DynamiteType type,
                                @NotNull List<Block> blockList,
                                @NotNull List<Pending> pending) {
        Breaking breaking = type.breaking();
        if (breaking.blocks().isEmpty() && type.transforms().isEmpty()) return;

        int radius = type.scanRadius(plugin.settings().antiLag().maxBreakScanRadius());
        RandomGenerator random = BlastMath.random();

        World world = center.getWorld();
        if (world == null) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int radiusSquared = radius * radius;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;

                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    Material material = block.getType();

                    TransformRule transform = type.transforms().get(material);
                    BreakRule rule = breaking.ruleFor(material);
                    if (rule == null && transform == null) continue; // блок не интересует
                    if (blockList.contains(block)) continue;         // уже решено vanilla
                    if (Materials.isIndestructible(material)) continue;
                    if (!plugin.qps().canExplode(block)) continue;

                    // Трансформация важнее ломания: древние обломки деградируют
                    // в обсидиан, а не исчезают.
                    if (transform != null) {
                        if (BlastMath.roll(transform.chance(), random)) {
                            pending.add(new Pending(block, Action.TRANSFORM, transform.to(), 0L, 0));
                        }
                        continue;
                    }

                    if (!BlastMath.roll(rule.breakChance(), random)) continue;

                    if (rule.keepDrop(random)) {
                        blockList.add(block); // пусть сервер выбьет блок с дропом
                    } else {
                        pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0));
                    }
                    queueRaid(type, block, material, pending);
                }
            }
        }
    }

    private void queueRaid(@NotNull DynamiteType type,
                           @NotNull Block block,
                           @NotNull Material material,
                           @NotNull List<Pending> pending) {
        if (!type.raidBlock().enabled()) return;
        if (!type.raidBlock().materials().contains(material)) return;
        pending.add(new Pending(block, Action.MARK_RAID, null,
                type.raidBlock().durationMs(), type.raidBlock().radius()));
    }

    /** Жёсткий предел числа разрушенных блоков (защита от лаг-машины). */
    private void applyBlockBudget(@NotNull DynamiteType type,
                                  @NotNull List<Block> blockList,
                                  @NotNull List<Pending> pending) {
        int max = type.explosion().maxBlocks();
        if (max <= 0) return;

        if (blockList.size() > max) {
            blockList.subList(max, blockList.size()).clear();
        }

        int budget = max - blockList.size();
        if (budget <= 0) {
            pending.removeIf(item -> item.action() != Action.MARK_RAID);
            return;
        }

        int used = 0;
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            if (item.action() == Action.MARK_RAID) continue;
            if (used >= budget) {
                iterator.remove();
                continue;
            }
            used++;
        }
    }

    /**
     * Применить отложенные действия.
     *
     * <p>Группируем по чанкам: на Folia задание выполняется в потоке региона,
     * которому принадлежит локация, поэтому трогать блоки соседнего региона
     * из одной задачи нельзя.</p>
     */
    private void schedulePending(@NotNull List<Pending> pending) {
        Map<ChunkRef, List<Pending>> byChunk = new HashMap<>();
        for (Pending item : pending) {
            Block block = item.block();
            ChunkRef ref = new ChunkRef(block.getWorld().getUID(),
                    block.getX() >> 4, block.getZ() >> 4);
            byChunk.computeIfAbsent(ref, key -> new ArrayList<>()).add(item);
        }

        for (List<Pending> group : byChunk.values()) {
            if (group.isEmpty()) continue;

            List<Pending> snapshot = List.copyOf(group);
            Location at = snapshot.get(0).block().getLocation();

            Schedulers.runAtLocation(plugin, at, () -> {
                for (Pending item : snapshot) {
                    apply(item);
                }
            }, 1L);
        }
    }

    private void apply(@NotNull Pending item) {
        Block block = item.block();
        try {
            switch (item.action()) {
                case REMOVE_SILENTLY -> {
                    if (!Materials.isEmpty(block.getType())) {
                        block.setType(Material.AIR, false);
                    }
                }
                case TRANSFORM -> {
                    Material to = item.to();
                    if (to != null && !Materials.isIndestructible(block.getType())) {
                        block.setType(to, false);
                    }
                }
                case MARK_RAID -> markRaid(block, item);
            }
        } catch (IllegalStateException ex) {
            // Folia: попытка тронуть чужой регион. Не роняем сервер.
            plugin.getLogger().fine("Отложенное действие пропущено (чужой регион): "
                    + ex.getMessage());
        }
    }

    private void markRaid(@NotNull Block block, @NotNull Pending item) {
        // Отмечаем только то, что реально разрушилось: если блок уцелел
        // (например, его «защитил» другой плагин), рейд-блока нет.
        if (!Materials.isEmpty(block.getType()) && !Materials.isLiquid(block.getType())) return;

        plugin.raidBlocks().mark(block.getLocation(), item.raidDurationMs());

        int radius = item.raidRadius();
        if (radius > 0) {
            markRaidAround(block, radius, item.raidDurationMs());
        }
    }

    private void markRaidAround(@NotNull Block center, int radius, long durationMs) {
        Location location = center.getLocation();
        int radiusSquared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    if (x == 0 && y == 0 && z == 0) continue;
                    plugin.raidBlocks().mark(location.clone().add(x, y, z), durationMs);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Урон по сущностям
    // ------------------------------------------------------------------

    /**
     * Урон: у каждого динамита свои срезания для игроков и мобов плюс
     * общий множитель («Разрывная волна» почти не калечит, зато ломает блоки).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;

        Entity victim = event.getEntity();
        if (victim instanceof TNTPrimed) return;

        DynamiteType type = findTypeNear(victim.getLocation());
        if (type == null) return;

        int cut = victim instanceof Player
                ? type.damage().cutPlayer()
                : type.damage().cutEntity();
        double multiplier = type.damage().multiplier();

        double damage = event.getDamage() * multiplier;
        if (cut > 0) {
            damage *= 1.0 - (cut / 100.0);
        }

        if (damage <= 0.0) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(damage);
    }

    /** Тип ближайшего горящего динамита (для срезания урона). */
    private @Nullable DynamiteType findTypeNear(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) return null;

        double radius = 6.0;
        DynamiteType found = null;
        int bestCut = -1;

        for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof TNTPrimed tnt)) continue;

            String kind = tnt.getPersistentDataContainer()
                    .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
            if (kind == null) continue;

            DynamiteType type = plugin.registry().byId(kind);
            if (type == null) continue;

            int cut = Math.max(type.damage().cutPlayer(), type.damage().cutEntity());
            if (cut > bestCut) {
                bestCut = cut;
                found = type;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private @Nullable Player sourceOf(@NotNull TNTPrimed tnt) {
        Entity source = tnt.getSource();
        return source instanceof Player player ? player : null;
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
