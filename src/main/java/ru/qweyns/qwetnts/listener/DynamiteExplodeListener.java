package ru.qweyns.qwetnts.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.LangKeys;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private enum Action { REMOVE_SILENTLY, TRANSFORM, MARK_RAID, SPAWNER_DROP }

    private record Pending(@NotNull Block block,
                           @NotNull Action action,
                           @Nullable Material to,
                           long raidDurationMs,
                           int raidRadius,
                           int chance,
                           int xp,
                           boolean keepSpawnerType) {
    }

    private record ChunkRef(UUID world, int cx, int cz) {
    }

    /**
     * Координаты блока внутри одного мира.
     *
     * <p>Нужен, чтобы не искать блок в {@code blockList} перебором: при
     * радиусе скана 8 это до 5 тысяч позиций, и {@code List#contains}
     * превращал один взрыв в десятки миллионов сравнений.</p>
     */
    private record Pos(int x, int y, int z) {
        static @NotNull Pos of(@NotNull Block block) {
            return new Pos(block.getX(), block.getY(), block.getZ());
        }
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
        releaseQuota(tnt, world, center, source);

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

        // Механика Динамита Б2 из HolyWorld: на заприваченной территории
        // заряд не срабатывает вовсе — гасим событие и говорим об этом.
        if (type.regions().onlyOutside() && plugin.qps().insideRegion(center)) {
            event.blockList().clear();
            if (source != null) {
                plugin.lang().send(source, LangKeys.DYNAMITE_ONLY_OUTSIDE,
                        "%name%", type.displayName());
            }
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

        // Считаем только те взрывы, которые дошли до блоков: погашенные
        // миром, спавном или приватом в статистику не попадают.
        plugin.stats().recordExplosion(type.explosion().type());

        // Неразрушающий динамит (Стиллер, Ледяная волна) вообще не трогает
        // блоки: vanilla-список гасим сразу, а правила ломания не применяем.
        boolean destructive = type.breaking().destructive();

        // Если vanilla-список не нужен, ломаем только по своим правилам.
        if (!destructive || !type.breaking().vanillaBlockList()) {
            event.blockList().clear();
        }

        List<Pending> pending = new ArrayList<>();
        if (destructive && type.breaking().shape() == Breaking.Shape.CUBE) {
            // Динамит Б2: ровный куб, vanilla-список не используется.
            event.blockList().clear();
            processCube(event, type, center, pending);
        } else if (destructive) {
            processBlockList(event, type, pending);
        }

        // Спавнеры ищем досмотром только когда список vanilla нам не помощник:
        // иначе они уже разобраны в processBlockList, и второе попадание дало
        // бы двойной дроп. Для неразрушающего динамита это единственный путь.
        boolean scanSpawners = type.spawnerMining().enabled()
                && (!destructive || !type.breaking().vanillaBlockList());

        scanHardBlocks(center, type, event.blockList(), pending, scanSpawners);
        capPending(pending);
        applyBlockBudget(type, event.blockList(), pending);

        long pendingTicks = 0L;
        if (!pending.isEmpty()) {
            pendingTicks = schedulePending(pending);
        }

        if (type.temporary().enabled()) {
            // Лёд ставим ПОСЛЕ того, как отработают все отложенные правки:
            // при крупном взрыве они растягиваются по тикам, и иначе лёд
            // накрыло бы трансформацией (или наоборот — лёд встал бы на
            // месте обсидиана, который ещё не деградировал).
            Schedulers.runAtLocation(plugin, center,
                    () -> applyTemporaryBlocks(center, type), pendingTicks + 2L);
        }
    }

    /**
     * Снимает квоту анти-лага в том чанке, где заряд был создан.
     *
     * <p>Квоту занимали по месту установки/поджога, а взрыв происходит там,
     * куда заряд улетел: снаряд пушки уносит его на десятки блоков, обычный
     * динамит смещается соседним взрывом. Если снимать квоту по чанку
     * детонации, счётчик исходного чанка залипает до самовосстановления, а
     * счётчик чужого уменьшается ошибочно — лимит {@code max-primed-per-chunk}
     * перестаёт работать. Метку чанка рождения пишет
     * {@code PrimingService.spawn}.</p>
     */
    private void releaseQuota(@NotNull TNTPrimed tnt,
                              @NotNull World world,
                              @NotNull Location center,
                              @Nullable Player source) {
        UUID playerUuid = source != null ? source.getUniqueId() : null;

        var data = tnt.getPersistentDataContainer();
        Integer cx = data.get(plugin.keys().originChunkX, PersistentDataType.INTEGER);
        Integer cz = data.get(plugin.keys().originChunkZ, PersistentDataType.INTEGER);

        if (cx != null && cz != null) {
            plugin.antiLag().release(playerUuid, world.getUID(), cx, cz);
        } else {
            // Заряд создан до появления меток (или не нами) — снимаем там,
            // где он взорвался. Лучше так, чем потерять слот навсегда.
            plugin.antiLag().release(playerUuid, center.getChunk());
        }
    }

    /**
     * Жёсткий предел числа отложенных действий за один взрыв.
     *
     * <p>{@code max-blocks} обрезает только список блоков от vanilla; метки
     * рейд-блоков и трансформации, найденные досмотром прочных блоков, в него
     * не попадают — и при большом {@code scan-radius} их могут быть тысячи.
     * Здесь предел общий для всех действий, включая райд-метки.</p>
     */
    private void capPending(@NotNull List<Pending> pending) {
        int max = plugin.settings().antiLag().maxPendingPerExplosion();
        if (max <= 0 || pending.size() <= max) return;

        // Отбрасываем хвост списка: предел жёсткий, и без него крупный
        // взрыв с большим scan-radius породил бы тысячи отложенных задач.
        // Часть самых дальних меток при этом теряется — это плата за предел.
        pending.subList(max, pending.size()).clear();
    }

    /**
     * Фаза 2: временные блоки (лёд) вокруг эпицентра.
     *
     * <p>Ставим только там, где сейчас стоит материал из списка
     * {@code temporary-blocks.replace}, и только если приват разрешает
     * взрывное воздействие. Исходный материал запоминается ДО подмены,
     * чтобы по таймеру вернуть его на место.</p>
     *
     * <p>Обход идёт по чанкам: на Folia трогать блоки чужого региона из
     * задачи другого региона нельзя, поэтому каждому чанку — своя задача
     * в «родном» регионе.</p>
     */
    private void applyTemporaryBlocks(@NotNull Location center, @NotNull DynamiteType type) {
        DynamiteType.TemporaryBlocks temp = type.temporary();
        if (!temp.enabled()) return;

        World world = center.getWorld();
        if (world == null) return;

        int radius = temp.radius();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int minY = Math.max(world.getMinHeight(), cy - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + radius);

        int index = 0;
        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                int fromX = Math.max(chunkX << 4, cx - radius);
                int toX = Math.min((chunkX << 4) + 15, cx + radius);
                int fromZ = Math.max(chunkZ << 4, cz - radius);
                int toZ = Math.min((chunkZ << 4) + 15, cz + radius);
                if (fromX > toX || fromZ > toZ) continue;

                Location at = new Location(world,
                        (fromX + toX) / 2.0, cy, (fromZ + toZ) / 2.0);
                Schedulers.runAtLocation(plugin, at,
                        () -> applyTemporaryChunk(world, cx, cy, cz, minY, maxY,
                                fromX, toX, fromZ, toZ, temp),
                        1L + index);
                index++;
            }
        }
    }

    /** Один чанк временных блоков (выполняется в потоке своего региона). */
    private void applyTemporaryChunk(@NotNull World world,
                                     int cx, int cy, int cz,
                                     int minY, int maxY,
                                     int fromX, int toX,
                                     int fromZ, int toZ,
                                     @NotNull DynamiteType.TemporaryBlocks temp) {
        int radiusSquared = temp.radius() * temp.radius();
        RandomGenerator random = BlastMath.random();
        int placed = 0;

        for (int x = fromX; x <= toX; x++) {
            int dx = x - cx;
            for (int z = fromZ; z <= toZ; z++) {
                int dz = z - cz;
                int flat = dx * dx + dz * dz;
                if (flat > radiusSquared) continue;

                for (int y = minY; y <= maxY; y++) {
                    int dy = y - cy;
                    if (flat + dy * dy > radiusSquared) continue;

                    Block block = world.getBlockAt(x, y, z);
                    Material current = block.getType();
                    if (current == temp.material()) continue;
                    if (!temp.replaceable().contains(current)) continue;
                    if (!plugin.qps().canExplode(block)) continue;
                    if (plugin.bunker().isWall(block)) continue; // лёд внутри стены — дырка в механике
                    if (!BlastMath.roll(temp.chance(), random)) continue;

                    // ВАЖНО: сначала запоминаем, что было ДО подмены.
                    plugin.temporaryBlocks().put(block, temp.durationMs());
                    block.setType(temp.material(), false);
                    placed++;
                }
            }
        }

        if (placed > 0) {
            plugin.stats().recordTemporaryBlocks(placed);
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
        RandomGenerator random = BlastMath.random();

        // Судьбу каждого блока решаем сами, а список пересобираем заново:
        // так один и тот же разбор годится и для vanilla-списка, и для куба
        // Динамита Б2, где список собираем мы, а не сервер.
        List<Block> breakable = new ArrayList<>(event.blockList().size());
        for (Block block : event.blockList()) {
            classifyBlock(block, type, random, pending, breakable);
        }

        event.blockList().clear();
        event.blockList().addAll(breakable);
    }

    /**
     * Ровный куб вместо сферы — механика Динамита Б2 из HolyWorld Lite
     * (25×25×25).
     *
     * <p>Vanilla-список здесь не годится: он рассчитан на сферический взрыв
     * с учётом сопротивления материала. Поэтому обходим куб сами, а блоки,
     * которые нужно выбить с дропом, складываем в {@code blockList} —
     * сервер сделает это сам после нашего обработчика.</p>
     *
     * <p><b>Цена:</b> {@code cube-size³} обращений к блоку (25³ = 15 625).
     * Это дорого, но это сознательный выбор администратора: предел размера
     * куба ограничен, а {@code max-blocks} обрежет результат.</p>
     */
    private void processCube(@NotNull EntityExplodeEvent event,
                             @NotNull DynamiteType type,
                             @NotNull Location center,
                             @NotNull List<Pending> pending) {
        World world = center.getWorld();
        if (world == null) return;

        int size = type.breaking().cubeSize();
        int half = size / 2;
        RandomGenerator random = BlastMath.random();

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int minY = Math.max(world.getMinHeight(), cy - half);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + half);

        List<Block> breakable = new ArrayList<>(Math.min(size * size * size, 4096));

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (Materials.isEmpty(block.getType())) continue; // воздух не считаем
                    classifyBlock(block, type, random, pending, breakable);
                }
            }
        }

        event.blockList().addAll(breakable);
    }

    /**
     * Решает судьбу одного блока по правилам динамита.
     *
     * <p>Отдельный метод, а не цикл внутри {@link #processBlockList}, ровно
     * потому, что разбор одинаков для двух разных источников блоков:
     * vanilla-списка и куба.</p>
     *
     * @param breakable сюда складываются блоки, которые надо выбить
     *                  (сервер сделает это сам и выбросит дроп)
     */
    private void classifyBlock(@NotNull Block block,
                               @NotNull DynamiteType type,
                               @NotNull RandomGenerator random,
                               @NotNull List<Pending> pending,
                               @NotNull List<Block> breakable) {
        Material material = block.getType();

        if (Materials.isIndestructible(material)) return;
        if (!plugin.qps().canExplode(block)) return;
        // Стена бункера не подчиняется правилам динамита: её состояние
        // меняет только механика бункера, и только от выстрела пушки.
        if (plugin.bunker().isWall(block)) return;

        // Спавнер: vanilla при взрыве просто удаляет его без дропа,
        // поэтому судьбу решаем сами.
        if (material == Material.SPAWNER && type.spawnerMining().enabled()) {
            pending.add(new Pending(block, Action.SPAWNER_DROP, null, 0L, 0,
                    type.spawnerMining().chance(), type.spawnerMining().xp(),
                    type.spawnerMining().keepEntityType()));
            return;
        }

        // Цепочка деградации проверяется ДО правил ломания: иначе блок
        // из цепочки исчезал бы по breaking.blocks, и ни одной промежуточной
        // ступени игрок бы не увидел.
        Material nextStage = type.degrade(material);
        if (nextStage != null && BlastMath.roll(type.chain().chance(), random)) {
            pending.add(new Pending(block, Action.TRANSFORM, nextStage, 0L, 0, 0, 0, false));
            return;
        }

        Breaking breaking = type.breaking();

        BreakRule rule = breaking.ruleFor(material);
        if (rule != null) {
            // Явное правило полностью перекрывает расчёт сопротивления:
            // именно так C4 и Разрывная волна ломают обсидиан.
            if (!BlastMath.roll(rule.breakChance(), random)) return;
            if (rule.keepDrop(random)) {
                breakable.add(block);   // пусть сервер выбьет блок с дропом
            } else {
                pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0, 0, 0, false));
            }
            queueRaid(type, block, material, pending);
            return;
        }

        double resistance = Materials.blastResistance(block);
        if (!BlastMath.canBreak(resistance, type.explosion().power(),
                breaking.maxResistance(), breaking.resistanceScale())) {
            return;
        }

        TransformRule transform = type.transforms().get(material);
        if (transform != null) {
            // Трансформация важнее дропа: древние обломки деградируют,
            // а не исчезают.
            if (BlastMath.roll(transform.chance(), random)) {
                pending.add(new Pending(block, Action.TRANSFORM, transform.to(), 0L, 0, 0, 0, false));
            }
            return;
        }

        if (BlastMath.roll(breaking.defaultDropChance(), random)) {
            breakable.add(block);
        } else {
            pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0, 0, 0, false));
        }
        queueRaid(type, block, material, pending);
    }

    /**
     * Досмотр прочных блоков, которых vanilla не дала в {@code blockList}
     * (обсидиан, плачущий обсидиан, древние обломки, вода, лава).
     */
    private void scanHardBlocks(@NotNull Location center,
                                @NotNull DynamiteType type,
                                @NotNull List<Block> blockList,
                                @NotNull List<Pending> pending,
                                boolean scanSpawners) {
        Breaking breaking = type.breaking();
        if (breaking.blocks().isEmpty() && type.transforms().isEmpty() && !scanSpawners) return;

        int radius = type.scanRadius(plugin.settings().antiLag().maxBreakScanRadius());
        RandomGenerator random = BlastMath.random();

        World world = center.getWorld();
        if (world == null) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        int radiusSquared = radius * radius;

        // Снимок уже решённых vanilla позиций: поиск за O(1) вместо перебора.
        Set<Pos> seen = new HashSet<>(Math.max(16, blockList.size() * 2));
        for (Block existing : blockList) {
            seen.add(Pos.of(existing));
        }

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;

                    int bx = cx + x;
                    int by = cy + y;
                    int bz = cz + z;

                    Block block = world.getBlockAt(bx, by, bz);
                    Material material = block.getType();

                    TransformRule transform = type.transforms().get(material);
                    BreakRule rule = breaking.ruleFor(material);
                    boolean spawner = scanSpawners && material == Material.SPAWNER;
                    boolean inChain = type.chain().degrades(material);
                    if (rule == null && transform == null && !spawner && !inChain) {
                        continue; // не интересует
                    }
                    if (seen.contains(new Pos(bx, by, bz))) continue; // уже решено vanilla
                    if (Materials.isIndestructible(material)) continue;
                    if (!plugin.qps().canExplode(block)) continue;
                    if (plugin.bunker().isWall(block)) continue; // стена бункера: см. classifyBlock

                    // Цепочка деградации: как и в classifyBlock, раньше правил
                    // ломания — иначе ступени не наступали бы никогда.
                    if (inChain && BlastMath.roll(type.chain().chance(), random)) {
                        pending.add(new Pending(block, Action.TRANSFORM,
                                type.degrade(material), 0L, 0, 0, 0, false));
                        continue;
                    }

                    // Спавнер: vanilla при взрыве просто удаляет его без дропа,
                    // поэтому судьбу решаем сами — как и в processBlockList.
                    if (spawner && rule == null && transform == null) {
                        pending.add(new Pending(block, Action.SPAWNER_DROP, null, 0L, 0,
                                type.spawnerMining().chance(), type.spawnerMining().xp(),
                                type.spawnerMining().keepEntityType()));
                        continue;
                    }

                    // Трансформация важнее ломания: древние обломки деградируют
                    // в обсидиан, а не исчезают.
                    if (transform != null) {
                        if (BlastMath.roll(transform.chance(), random)) {
                            pending.add(new Pending(block, Action.TRANSFORM, transform.to(), 0L, 0, 0, 0, false));
                        }
                        continue;
                    }

                    if (!BlastMath.roll(rule.breakChance(), random)) continue;

                    if (rule.keepDrop(random)) {
                        blockList.add(block);             // пусть сервер выбьет блок с дропом
                        seen.add(new Pos(bx, by, bz));    // и не дублируем решение
                    } else {
                        pending.add(new Pending(block, Action.REMOVE_SILENTLY, null, 0L, 0, 0, 0, false));
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
                type.raidBlock().durationMs(), type.raidBlock().radius(), 0, 0, false));
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
     *
     * @return через сколько тиков завершится последняя порция (0 — действий
     *         не было). Вызывающему коду это нужно, чтобы не планировать
     *         свои правки поверх ещё не применённых.
     */
    private long schedulePending(@NotNull List<Pending> pending) {
        Map<ChunkRef, List<Pending>> byChunk = new HashMap<>();
        for (Pending item : pending) {
            Block block = item.block();
            ChunkRef ref = new ChunkRef(block.getWorld().getUID(),
                    block.getX() >> 4, block.getZ() >> 4);
            byChunk.computeIfAbsent(ref, key -> new ArrayList<>()).add(item);
        }

        int perTick = plugin.settings().antiLag().largeExplosionBlocksPerTick();
        long period = plugin.settings().antiLag().largeExplosionTickPeriod();

        long lastDelay = 0L;

        for (List<Pending> group : byChunk.values()) {
            if (group.isEmpty()) continue;

            // Крупный взрыв растягиваем по тикам: за один тик обрабатываем
            // не больше large-explosion-blocks-per-tick позиций.
            int batchIndex = 0;
            for (int from = 0; from < group.size(); from += perTick) {
                int to = Math.min(group.size(), from + perTick);
                List<Pending> batch = List.copyOf(group.subList(from, to));
                long delay = 1L + batchIndex * period;

                Schedulers.runAtLocation(plugin, batch.get(0).block().getLocation(), () -> {
                    for (Pending item : batch) {
                        apply(item);
                    }
                }, delay);

                batchIndex++;
                lastDelay = Math.max(lastDelay, delay);
            }
        }

        return lastDelay;
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
                case SPAWNER_DROP -> mineSpawner(block, item);
            }
        } catch (IllegalStateException ex) {
            // Folia: попытка тронуть чужой регион. Не роняем сервер.
            plugin.getLogger().fine("Отложенное действие пропущено (чужой регион): "
                    + ex.getMessage());
        }
    }

    /**
     * Спавнер-майнинг: выбить спавнер предметом с шансом и выдать опыт.
     * Тип mob'а сохраняется, если это не отключено настройкой.
     */
    private void mineSpawner(@NotNull Block block, @NotNull Pending item) {
        World world = block.getWorld();
        if (world == null) return;
        if (block.getType() != Material.SPAWNER) return;
        if (!plugin.qps().canExplode(block)) return;

        ItemStack drop = new ItemStack(Material.SPAWNER);

        // Тип mob'а: по умолчанию переносим в предмет, иначе спавнер
        // при установке будет пустым (зомби).
        if (item.keepSpawnerType()) {
            try {
                if (block.getState() instanceof CreatureSpawner spawner) {
                    BlockStateMeta meta = (BlockStateMeta) drop.getItemMeta();
                    CreatureSpawner stored = (CreatureSpawner) meta.getBlockState();
                    stored.setSpawnedType(spawner.getSpawnedType());
                    meta.setBlockState(stored);
                    drop.setItemMeta(meta);
                }
            } catch (Exception ex) {
                plugin.getLogger().fine("Не удалось прочитать тип спавнера: " + ex.getMessage());
            }
        }

        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(Material.AIR, false);

        // chance <= 0 — «не выбивать»: спавнер просто исчезает, как в vanilla.
        if (item.chance() > 0 && BlastMath.roll(item.chance(), BlastMath.random())) {
            world.dropItemNaturally(at, drop);
            plugin.stats().recordSpawnerMined();
        }

        if (item.xp() > 0) {
            try {
                ExperienceOrb orb = world.spawn(at, ExperienceOrb.class);
                orb.setExperience(item.xp());
            } catch (Exception ex) {
                plugin.getLogger().fine("Не удалось выдать опыт за спавнер: " + ex.getMessage());
            }
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

        // Если ни один тип не меняет урон, нечего и искать: это экономит
        // проход по сущностям на каждый урон от любого взрыва на сервере.
        if (!plugin.registry().modifiesDamage()) return;

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

        // Радиус берём из настроек: это компромисс между точностью и ценой
        // перебора сущностей на каждое событие урона от взрыва.
        double radius = plugin.settings().dynamites().damageSourceRadiusBlocks();
        DynamiteType found = null;
        int bestCut = -1;

        try {
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
        } catch (IllegalStateException ex) {
            // Folia: сущность из чужого региона. Просто не меняем урон.
            return null;
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

        // Честный радиус (раньше сравнивались модули разностей по осям,
        // то есть зона была квадратом со стороной 2r, а не кругом).
        double dx = location.getBlockX() - spawn.getBlockX();
        double dz = location.getBlockZ() - spawn.getBlockZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
