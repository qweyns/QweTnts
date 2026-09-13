package ru.qweyns.qwetnts.raid;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Io;
import ru.qweyns.qwetnts.util.Schedulers;

import java.io.File;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Рейд-блоки по механике HW Lite: С4 и Разрывная волна после взрыва оставляют
 * метку на N минут на месте каждого уничтоженного обсидиана — ставить на это
 * место OBSIDIAN/CRYING_OBSIDIAN/ANCIENT_DEBRIS нельзя никому (анти-феникс).
 *
 * <p>Хранение в памяти {@code (world, x,y,z) -> expiresAtMs}, сериализация в
 * {@code raid-blocks.yml} на диске с атомарной записью, периодическая чистка
 * истёкших записей и автосохранение на I/O-пуле.</p>
 */
public final class RaidBlockManager {

    private final Map<String, Map<BlockPos, Long>> byWorld = new ConcurrentHashMap<>();
    private final QweTnts plugin;
    private final File file;

    private ScheduledTask cleanupTask;
    private ScheduledTask autoSaveTask;

    public RaidBlockManager(QweTnts plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "raid-blocks.yml");
    }

    /** Ставит рейд-блок в указанной позиции. Потокобезопасно. */
    public void mark(Location loc, long durationMs) {
        if (loc.getWorld() == null) return;
        if (durationMs <= 0) return;
        long until = System.currentTimeMillis() + durationMs;
        byWorld.computeIfAbsent(loc.getWorld().getName(), k -> new ConcurrentHashMap<>())
                .put(BlockPos.of(loc), until);
    }

    /**
     * @return epochMs окончания или 0, если в позиции нет активного рейд-блока.
     */
    public long expiresAt(Location loc) {
        if (loc.getWorld() == null) return 0L;
        Map<BlockPos, Long> m = byWorld.get(loc.getWorld().getName());
        if (m == null) return 0L;
        BlockPos pos = BlockPos.of(loc);
        Long until = m.get(pos);
        if (until == null) return 0L;
        long now = System.currentTimeMillis();
        if (until <= now) {
            m.remove(pos);
            return 0L;
        }
        return until;
    }

    public int size() {
        int total = 0;
        for (var m : byWorld.values()) total += m.size();
        return total;
    }

    /** Синхронная загрузка меток с диска. */
    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (String worldName : yaml.getKeys(false)) {
            var section = yaml.getConfigurationSection(worldName);
            if (section == null) continue;
            Map<BlockPos, Long> map = new ConcurrentHashMap<>();
            for (String key : section.getKeys(false)) {
                long until = section.getLong(key);
                if (until > now) {
                    BlockPos pos = BlockPos.parse(key);
                    if (pos != null) map.put(pos, until);
                }
            }
            if (!map.isEmpty()) byWorld.put(worldName, map);
        }
    }

    /**
     * Сериализует текущее состояние в YAML и отдаёт в I/O-пул для атомарной записи.
     * Можно вызывать из главного потока — запись на диск пойдёт асинхронно.
     */
    public void saveAsync(ExecutorService io) {
        String dump = dumpToYaml();
        io.execute(() -> Io.writeAtomic(file.toPath(), dump, plugin.getLogger()));
    }

    /** Синхронное сохранение (для onDisable). */
    public void save() {
        Io.writeAtomic(file.toPath(), dumpToYaml(), plugin.getLogger());
    }

    private String dumpToYaml() {
        long now = System.currentTimeMillis();
        StringWriter sw = new StringWriter(1024);
        for (var worldEntry : byWorld.entrySet()) {
            sw.write(worldEntry.getKey()).write(":\n");
            for (var posEntry : worldEntry.getValue().entrySet()) {
                long v = posEntry.getValue();
                if (v <= now) continue;
                sw.write("  ").write(posEntry.getKey().serialize()).write(": ").write(Long.toString(v)).write('\n');
            }
        }
        return sw.toString();
    }

    /**
     * Запускает асинхронную периодическую чистку и автосохранение (Folia-safe).
     */
    public void startTasks(ExecutorService ioPool, long cleanupIntervalTicks,
                           long autoSaveIntervalTicks) {
        cancelTasks();
        cleanupTask = Schedulers.runAsyncTimer(plugin, task -> {
            long now = System.currentTimeMillis();
            for (var worldMap : byWorld.values()) {
                Iterator<Map.Entry<BlockPos, Long>> it = worldMap.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue() <= now) it.remove();
                }
            }
        }, cleanupIntervalTicks, cleanupIntervalTicks);

        autoSaveTask = Schedulers.runAsyncTimer(plugin, task -> saveAsync(ioPool),
                autoSaveIntervalTicks, autoSaveIntervalTicks);
    }

    public void cancelTasks() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    public void shutdown(ExecutorService ioPool) {
        cancelTasks();
        // Финальный сейв синхронно, чтобы при остановке гарантированно записать.
        try {
            save();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка финального сохранения рейд-блоков", e);
        }
        byWorld.clear();
    }

    /** Простой ключ (x,y,z) без обращения к Block/World. */
    public record BlockPos(int x, int y, int z) {
        public static BlockPos of(Location loc) {
            return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        public static BlockPos parse(String s) {
            try {
                String[] parts = s.split(",");
                if (parts.length != 3) return null;
                return new BlockPos(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public String serialize() {
            return x + "," + y + "," + z;
        }
    }
}
