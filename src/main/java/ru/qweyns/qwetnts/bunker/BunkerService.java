package ru.qweyns.qwetnts.bunker;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.cannon.CannonSettings;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.BlastMath;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Effects;
import ru.qweyns.qwetnts.util.Materials;
import ru.qweyns.qwetnts.util.Schedulers;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Механика бункера: стена, которую пробивают выстрелами из Тнт-пушки.
 *
 * <h2>Правила (по HolyWorld Lite)</h2>
 * <ol>
 *   <li>Считаются <b>только</b> выстрелы из Тнт-пушки: снаряд помечен
 *       PDC {@code qwetnts:cannon-shot}. Подорвать стену «в упор», подойдя
 *       к ней с C4, нельзя — блоки стены вычищаются из любых списков
 *       разрушения.</li>
 *   <li>За выстрел — <b>один</b> ролл, а не ролл на каждый задетый блок:
 *       иначе залп из десяти зарядов крутил бы шанс десять раз.</li>
 *   <li>При успехе стена деградирует <b>целиком</b> на одну стадию:
 *       древние обломки → плачущий обсидиан → обсидиан → дыра.</li>
 *   <li>Награды за пробитую стену нет: ивент — это состязание, а не
 *       раздача лута (решение администрации).</li>
 * </ol>
 *
 * <h2>Потоки (Folia)</h2>
 * <p>Взрыв приходит в потоке региона, поэтому смену блоков стены
 * планируем по чанкам в их «родных» регионах, а сообщения игрокам —
 * на глобальном регионе.</p>
 */
public final class BunkerService {

    /** Что подставлять вместо имени, если стрелявшего не удалось опознать. */
    private static final String DASH = "\u2014";

    private final QweTnts plugin;
    private final BunkerState state;

    private volatile BunkerSettings settings = BunkerSettings.DISABLED;

    public BunkerService(@NotNull QweTnts plugin) {
        this.plugin = plugin;
        this.state = new BunkerState(plugin);
    }

    public @NotNull BunkerSettings settings() { return settings; }

    public @NotNull BunkerState state() { return state; }

    public boolean isEnabled() { return settings.enabled(); }

    /** Перечитать {@code bunker.yml} и состояние стены. */
    public void reload() {
        settings = BunkerLoader.load(plugin, new File(plugin.getDataFolder(), "bunker.yml"));
        if (!settings.enabled()) return;

        state.load();

        // Первый запуск: состояние пустое — считаем стену целой и, если
        // респавн включён, ставим сторожевой таймер.
        if (state.stageIndex() > settings.lastStageIndex()) {
            state.setStage(0);
            state.setBreached(false);
        }
    }

    // ------------------------------------------------------------------
    // Защита стены
    // ------------------------------------------------------------------

    /** Нужно ли вообще вычищать блоки: бункер включён и это его мир. */
    public boolean protectsIn(@Nullable World world) {
        return settings.isActiveIn(world) && settings.wall().protect();
    }

    /** Этот блок — часть стены бункера? */
    public boolean isWall(@Nullable Block block) {
        if (block == null) return false;
        return isWallIn(block.getWorld(), block);
    }

    /**
     * То же, что {@link #isWall(Block)}, но мир уже проверен вызывающим.
     *
     * <p>Нужно для обхода {@code blockList}: он насчитывает тысячи блоков
     * (у куба Динамита Б2 — до 15 тысяч), и сравнивать имя мира на каждом
     * было бы лишней работой.</p>
     */
    public boolean isWallIn(@NotNull World world, @Nullable Block block) {
        BunkerSettings cfg = settings;
        if (!cfg.enabled() || block == null) return false;
        if (!world.getName().equals(cfg.world())) return false;

        BunkerSettings.Wall wall = cfg.wall();
        return wall.contains(block.getX(), block.getY(), block.getZ())
                && wall.isStageMaterial(block.getType());
    }

    // ------------------------------------------------------------------
    // Выстрел по стене
    // ------------------------------------------------------------------

    /**
     * Регистрирует выстрел из Тнт-пушки по стене.
     *
     * @param projectile снаряд (уже в момент взрыва)
     * @param center     эпицентр взрыва
     * @param type       тип динамита; {@code null} — ванильный TNT
     * @param shooter    имя стрелявшего; {@code null} — выстрел не от игрока
     */
    public void onShot(@NotNull TNTPrimed projectile,
                       @NotNull Location center,
                       @Nullable DynamiteType type,
                       @Nullable String shooter) {
        BunkerSettings cfg = settings;
        if (!cfg.enabled()) return;

        World world = center.getWorld();
        if (world == null || !cfg.isActiveIn(world)) return;
        if (state.breached() || state.stageIndex() >= cfg.lastStageIndex()) return;

        BunkerSettings.Wall wall = cfg.wall();
        if (!wall.isDefined()) return;
        if (!wall.near(center.getBlockX(), center.getBlockY(), center.getBlockZ(),
                wall.hitMargin())) {
            return;
        }
        if (!isCannonShot(projectile)) return;

        long now = System.currentTimeMillis();
        if (cfg.cooldownMillis() > 0L && now - state.lastShotAtMillis() < cfg.cooldownMillis()) {
            return;
        }
        state.markShot(now);

        String key = type != null ? type.id() : CannonSettings.VANILLA_TNT;
        if (!BlastMath.rollPercent(cfg.chanceFor(key), BlastMath.random())) {
            return;
        }

        degrade(world, cfg, shooter);
    }

    /** Снаряд выпущен именно пушкой, а не просто подожжён рукой. */
    private boolean isCannonShot(@NotNull TNTPrimed projectile) {
        Byte marker = projectile.getPersistentDataContainer()
                .get(plugin.keys().cannonShot, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    // ------------------------------------------------------------------
    // Деградация и пробитие
    // ------------------------------------------------------------------

    private void degrade(@NotNull World world,
                         @NotNull BunkerSettings cfg,
                         @Nullable String shooter) {
        int current = state.stageIndex();
        int next = current + 1;
        int last = cfg.lastStageIndex();
        if (current >= last) return;

        Material from = cfg.wall().stageMaterial(current);
        Material to = cfg.wall().stageMaterial(next);
        if (from == null || to == null) return;

        boolean breach = next >= last;

        state.setStage(next);
        if (breach) {
            state.setBreached(true);
            state.setNextRespawnAtMillis(cfg.respawn().enabled()
                    ? System.currentTimeMillis()
                            + TimeUnit.MINUTES.toMillis(cfg.respawn().intervalMinutes())
                    : 0L);
        }

        replaceWall(world, cfg, from, to, breach);

        Location center = cfg.wall().center(world);
        DynamiteEffect effect = breach ? cfg.effects().onBreach() : cfg.effects().onDegrade();

        Schedulers.runAtLocation(plugin, center,
                () -> Effects.play(plugin, effect, center), 2L);

        if (cfg.announce()) {
            String material = to == Material.AIR ? DASH : Materials.prettyName(to);
            int shownStage = Math.min(next + 1, last);
            String[] placeholders = {
                    "%stage%", String.valueOf(shownStage),
                    "%of%", String.valueOf(last),
                    "%material%", material,
                    "%player%", shooter == null || shooter.isBlank() ? DASH : shooter
            };
            Schedulers.runGlobal(plugin, () -> {
                for (Player player : world.getPlayers()) {
                    plugin.lang().send(player, breach
                            ? LangKeys.BUNKER_BREACHED
                            : LangKeys.BUNKER_DEGRADED, placeholders);
                }
            });
        }

        if (breach) {
            notifyDiscord(cfg, shooter);
            payReward(cfg, world, shooter);
        }

        state.saveAsync(plugin.ioPool());
    }

    /**
     * Выдать награду: выполнить команды от имени консоли.
     *
     * <p>Команды исполняются на глобальном регионе — взрыв приходит в потоке
     * региона, а {@code dispatchCommand} трогает и игроков, и мир.</p>
     *
     * <p>Сам аддон ничего не спавнит: шалкер за стеной ставит тот плагин,
     * который на сервере отвечает за ивенты. Ошибка чужой команды — не наш
     * сбой, поэтому она только пишется в лог.</p>
     */
    private void payReward(@NotNull BunkerSettings cfg,
                           @NotNull World world,
                           @Nullable String shooter) {
        BunkerSettings.Reward reward = cfg.reward();
        if (reward.isEmpty()) return;

        Location center = cfg.wall().center(world);
        String player = shooter == null || shooter.isBlank() ? DASH : shooter;
        String worldName = world.getName();
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        Schedulers.runGlobal(plugin, () -> {
            for (String command : reward.commands()) {
                String resolved = reward.apply(command, player, worldName, x, y, z);
                try {
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), resolved);
                } catch (RuntimeException ex) {
                    // Чужая команда упала — сервер не должен узнать об этом
                    // иначе, чем строкой в логе.
                    plugin.getLogger().log(Level.WARNING,
                            "Команда награды бункера не выполнилась: " + resolved, ex);
                }
            }
        });
    }

    private void notifyDiscord(@NotNull BunkerSettings cfg, @Nullable String shooter) {
        String[] placeholders = {
                "%stage%", String.valueOf(state.stageIndex()),
                "%of%", String.valueOf(cfg.lastStageIndex()),
                "%player%", shooter == null || shooter.isBlank() ? DASH : shooter
        };

        if (plugin.discord() != null
                && plugin.settings().alerts().discord()
                        .wants(Settings.Discord.DiscordEvent.BUNKER_BREACHED)) {
            plugin.discord().send(Settings.Discord.DiscordEvent.BUNKER_BREACHED,
                    plugin.lang().raw(LangKeys.DISCORD_BUNKER_BREACHED, placeholders));
        }
    }

    // ------------------------------------------------------------------
    // Восстановление
    // ------------------------------------------------------------------

    /** Периодическая проверка: не пора ли вернуть стену (из {@code startTasks}). */
    public void tick() {
        BunkerSettings cfg = settings;
        if (!cfg.enabled() || !cfg.respawn().enabled()) return;

        long due = state.nextRespawnAtMillis();
        if (due <= 0L || System.currentTimeMillis() < due) return;

        restore();
    }

    /** Вернуть стену в исходное состояние (команда или таймер респавна). */
    public void restore() {
        BunkerSettings cfg = settings;
        if (!cfg.enabled()) return;

        World world = plugin.getServer().getWorld(cfg.world());
        if (world == null) return; // мир не загружен — попробуем на следующем тике

        int last = cfg.lastStageIndex();
        int stage = 0;
        if (cfg.respawn().randomStage() && last > 1) {
            // Как на HolyWorld: при появлении ивента стадия случайна —
            // от самой прочной (0) до последней «живой» (last-1).
            stage = BlastMath.random().nextInt(last);
        }

        Material material = cfg.wall().stageMaterial(stage);
        if (material == null) return;

        state.setStage(stage);
        state.setBreached(false);
        state.setNextRespawnAtMillis(0L);

        fillWall(world, cfg, material);

        if (cfg.announce()) {
            String[] placeholders = {"%material%", material.name()};
            Schedulers.runGlobal(plugin, () -> {
                for (Player player : world.getPlayers()) {
                    plugin.lang().send(player, LangKeys.BUNKER_RESTORED, placeholders);
                }
            });
        }

        state.saveAsync(plugin.ioPool());
    }

    /**
     * Выставить стадию вручную: {@code /qtnt bunker stage <n>}.
     *
     * <p>Меняем и состояние, и блоки — иначе статус в команде разойдётся с
     * тем, что игроки видят в мире. Стадия {@code last} означает пробитую
     * стену и запускает таймер респавна на общих основаниях.</p>
     */
    public void setStage(int index) {
        BunkerSettings cfg = settings;
        if (!cfg.enabled()) return;

        int last = cfg.lastStageIndex();
        int stage = Math.min(Math.max(0, index), last);
        Material material = cfg.wall().stageMaterial(stage);
        if (material == null) return;

        boolean breach = stage >= last;
        state.setStage(stage);
        state.setBreached(breach);
        state.setNextRespawnAtMillis(breach && cfg.respawn().enabled()
                ? System.currentTimeMillis()
                        + TimeUnit.MINUTES.toMillis(cfg.respawn().intervalMinutes())
                : 0L);

        World world = plugin.getServer().getWorld(cfg.world());
        if (world != null) {
            fillWall(world, cfg, material);
        }
        state.saveAsync(plugin.ioPool());
    }

    // ------------------------------------------------------------------
    // Работа с блоками
    // ------------------------------------------------------------------

    /**
     * Заменяет материал текущей стадии на материал следующей.
     *
     * <p>Обход по чанкам: на Folia трогать блоки чужого региона из задачи
     * одного региона нельзя.</p>
     */
    private void replaceWall(@NotNull World world,
                             @NotNull BunkerSettings cfg,
                             @NotNull Material from,
                             @NotNull Material to,
                             boolean breach) {
        BunkerSettings.Wall wall = cfg.wall();
        int index = 0;

        for (int chunkX = wall.minX() >> 4; chunkX <= wall.maxX() >> 4; chunkX++) {
            for (int chunkZ = wall.minZ() >> 4; chunkZ <= wall.maxZ() >> 4; chunkZ++) {
                int fromX = Math.max(chunkX << 4, wall.minX());
                int toX = Math.min((chunkX << 4) + 15, wall.maxX());
                int fromZ = Math.max(chunkZ << 4, wall.minZ());
                int toZ = Math.min((chunkZ << 4) + 15, wall.maxZ());
                if (fromX > toX || fromZ > toZ) continue;

                int fx = fromX;
                int tx = toX;
                int fz = fromZ;
                int tz = toZ;
                Location at = new Location(world,
                        (fx + tx) / 2.0,
                        wall.centerY(),
                        (fz + tz) / 2.0);

                Schedulers.runAtLocation(plugin, at,
                        () -> applyStageChunk(world, fx, tx, wall.minY(), wall.maxY(), fz, tz,
                                from, to, breach, cfg),
                        1L + index);
                index++;
            }
        }
    }

    private void applyStageChunk(@NotNull World world,
                                 int fromX, int toX,
                                 int minY, int maxY,
                                 int fromZ, int toZ,
                                 @NotNull Material from,
                                 @NotNull Material to,
                                 boolean breach,
                                 @NotNull BunkerSettings cfg) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material current = block.getType();
                    if (current != from && !(breach && cfg.wall().isStageMaterial(current))) {
                        continue;
                    }
                    block.setType(to, false);
                }
            }
        }
    }

    /** Заполняет весь прямоугольник стены материалом (после пробития там дыра). */
    private void fillWall(@NotNull World world,
                          @NotNull BunkerSettings cfg,
                          @NotNull Material material) {
        BunkerSettings.Wall wall = cfg.wall();
        int index = 0;

        for (int chunkX = wall.minX() >> 4; chunkX <= wall.maxX() >> 4; chunkX++) {
            for (int chunkZ = wall.minZ() >> 4; chunkZ <= wall.maxZ() >> 4; chunkZ++) {
                int fromX = Math.max(chunkX << 4, wall.minX());
                int toX = Math.min((chunkX << 4) + 15, wall.maxX());
                int fromZ = Math.max(chunkZ << 4, wall.minZ());
                int toZ = Math.min((chunkZ << 4) + 15, wall.maxZ());
                if (fromX > toX || fromZ > toZ) continue;

                int fx = fromX;
                int tx = toX;
                int fz = fromZ;
                int tz = toZ;
                Location at = new Location(world,
                        (fx + tx) / 2.0, wall.centerY(), (fz + tz) / 2.0);

                Schedulers.runAtLocation(plugin, at,
                        () -> applyFillChunk(world, fx, tx, wall.minY(), wall.maxY(), fz, tz,
                                material),
                        1L + index);
                index++;
            }
        }
    }

    private void applyFillChunk(@NotNull World world,
                                int fromX, int toX,
                                int minY, int maxY,
                                int fromZ, int toZ,
                                @NotNull Material material) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }
}
