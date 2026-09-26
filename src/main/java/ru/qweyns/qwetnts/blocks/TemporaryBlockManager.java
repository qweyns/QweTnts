package ru.qweyns.qwetnts.blocks;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Io;
import ru.qweyns.qwetnts.util.Schedulers;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Временные блоки после взрыва (Фаза 2 ТЗ): например, лёд, который тает
 * через N секунд.
 *
 * <p>На каждую позицию запоминается <b>исходный</b> материал, поэтому после
 * истечения времени место возвращается в исходное состояние, а не остаётся
 * дырой. Учёт переживает рестарт сервера (атомарная запись в
 * {@code temporary-blocks.yml}).</p>
 *
 * <p>Все обращения к блокам — только с главного потока: восстановлением
 * занимается периодическая задача на глобальном планировщике.</p>
 */
public final class TemporaryBlockManager {

    /** Ключ позиции: мир + координаты блока. */
    public record Key(@NotNull String world, int x, int y, int z) {

        public static @NotNull Key of(@NotNull Block block) {
            return new Key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        public static @Nullable Key parse(@NotNull String world, @Nullable String coords) {
            if (coords == null || coords.isBlank()) return null;
            String[] parts = coords.split(",");
            if (parts.length != 3) return null;
            try {
                return new Key(world,
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        public @NotNull String serialize() {
            return x + "," + y + "," + z;
        }
    }

    /** Что вернуть на место и когда. */
    public record Entry(@NotNull Material restore, long expiresAtMillis) {
    }

    private final QweTnts plugin;
    private final File file;
    private final Map<Key, Entry> blocks = new ConcurrentHashMap<>();

    private ScheduledTask cleanupTask;
    private ScheduledTask autoSaveTask;

    public TemporaryBlockManager(@NotNull QweTnts plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "temporary-blocks.yml");
    }

    /** Запомнить позицию: что было до подмены и когда вернуть назад. */
    public void put(@NotNull Block block, long durationMs) {
        if (durationMs <= 0L) return;
        blocks.put(Key.of(block), new Entry(block.getType(), System.currentTimeMillis() + durationMs));
    }

    public boolean contains(@NotNull Block block) {
        return blocks.containsKey(Key.of(block));
    }

    public long expiresAt(@NotNull Block block) {
        Entry entry = blocks.get(Key.of(block));
        return entry == null ? 0L : entry.expiresAtMillis();
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public @NotNull Collection<Map.Entry<Key, Entry>> entries() {
        return blocks.entrySet();
    }

    // ------------------------------------------------------------------
    // Восстановление
    // ------------------------------------------------------------------

    /**
     * Вернуть исходные блоки там, где время истекло.
     *
     * @return сколько позиций восстановлено
     */
    public int restoreExpired(@NotNull Server server) {
        return restore(server, false);
    }

    /** Вернуть всё (используется при выгрузке плагина). */
    public int restoreAll(@NotNull Server server) {
        return restore(server, true);
    }

    private int restore(@NotNull Server server, boolean force) {
        if (blocks.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        int restored = 0;

        for (Key key : new LinkedHashSet<>(blocks.keySet())) {
            Entry entry = blocks.get(key);
            if (entry == null) continue;
            if (!force && entry.expiresAtMillis() > now) continue;

            World world = server.getWorld(key.world());
            if (world == null) {
                // Мир выгружен — оставляем запись, восстановим позже.
                continue;
            }

            // Чанк выгружен — не грузим его ради восстановления: лёд там
            // никого не смущает, а вернём материал, когда чанк загрузится.
            if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;

            try {
                // getBlockAt/getType тоже внутри try: на Folia чтение
                // блока чужого региона бросает IllegalStateException.
                Block block = world.getBlockAt(key.x(), key.y(), key.z());
                if (block.getType() != entry.restore()) {
                    block.setType(entry.restore(), false);
                }
            } catch (IllegalStateException ex) {
                // Folia: чужой регион — попробуем в следующий проход.
                plugin.getLogger().fine("Временный блок не восстановлен (чужой регион): "
                        + ex.getMessage());
                continue;
            }

            blocks.remove(key);
            restored++;
        }

        if (restored > 0 && Bukkit.isPrimaryThread()) {
            plugin.getLogger().fine("Восстановлено временных блоков: " + restored);
        }
        return restored;
    }

    // ------------------------------------------------------------------
    // Периодические задачи
    // ------------------------------------------------------------------

    /**
     * Запускает периодику. Восстановление идёт на главном потоке (трогаем
     * блоки), автосохранение — в пуле I/O.
     */
    public void startTasks(@NotNull ExecutorService ioPool,
                           long cleanupIntervalTicks,
                           long autosaveIntervalTicks) {
        cancelTasks();

        cleanupTask = Schedulers.runGlobalTimer(plugin, task -> {
            if (blocks.isEmpty()) return;
            restoreExpired(plugin.getServer());
        }, cleanupIntervalTicks, cleanupIntervalTicks);

        autoSaveTask = Schedulers.runAsyncTimer(plugin, task -> {
            if (blocks.isEmpty()) return;
            saveAsync(ioPool);
        }, autosaveIntervalTicks, autosaveIntervalTicks);
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

    /** Остановка: задачи долой, остаток — синхронно на диск. */
    public void shutdown() {
        cancelTasks();
        if (!blocks.isEmpty()) save();
    }

    // ------------------------------------------------------------------
    // Персистентность
    // ------------------------------------------------------------------

    public void load() {
        if (!file.isFile()) return;

        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось прочитать temporary-blocks.yml", ex);
            return;
        }

        for (String world : yaml.getKeys(false)) {
            var section = yaml.getConfigurationSection(world);
            if (section == null) continue;

            for (String coords : section.getKeys(false)) {
                Key key = Key.parse(world, coords);
                if (key == null) continue;

                var entry = section.getConfigurationSection(coords);
                if (entry == null) continue;

                Material restore = Material.AIR;
                String raw = entry.getString("restore");
                if (raw != null) {
                    Material parsed = org.bukkit.Material.matchMaterial(raw);
                    if (parsed != null) restore = parsed;
                }
                long until = entry.getLong("until", System.currentTimeMillis());
                blocks.put(key, new Entry(restore, until));
            }
        }

        if (!blocks.isEmpty()) {
            plugin.getLogger().info("Восстановлено временных блоков из файла: " + blocks.size());
        }
    }

    public void saveAsync(@NotNull ExecutorService io) {
        String dump = dumpToYaml();
        io.execute(() -> Io.writeAtomic(file.toPath(), dump, plugin.getLogger()));
    }

    /** Синхронное сохранение — только для onDisable. */
    public void save() {
        Io.writeAtomic(file.toPath(), dumpToYaml(), plugin.getLogger());
    }

    private @NotNull String dumpToYaml() {
        StringBuilder out = new StringBuilder(256);
        for (Map.Entry<Key, Entry> entry : blocks.entrySet()) {
            Key key = entry.getKey();
            Entry value = entry.getValue();
            out.append(quote(key.world())).append(":\n");
            out.append("  ").append(quote(key.serialize())).append(":\n");
            out.append("    restore: ").append(quote(value.restore().name())).append('\n');
            out.append("    until: ").append(value.expiresAtMillis()).append('\n');
        }
        return out.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Убрать временные блоки, вернув исходный материал.
     *
     * <p>Раньше здесь был просто {@code blocks.clear()}: запись исчезала, а
     * лёд оставался в мире навсегда — именно так вёл себя
     * {@code /qtnt clear temporary-blocks}. Теперь каждая позиция сначала
     * возвращается в исходное состояние.</p>
     *
     * <p>Правка планируется в «родном» регионе позиции: команду мог
     * выполнить игрок, а на Folia менять блок чужого региона нельзя —
     * прямой вызов бросил бы {@link IllegalStateException}.</p>
     */
    public void clear() {
        for (Key key : new LinkedHashSet<>(blocks.keySet())) {
            Entry entry = blocks.get(key);
            blocks.remove(key);
            if (entry == null) continue;

            World world = plugin.getServer().getWorld(key.world());
            if (world == null) continue;   // мир выгружен — вернуть некуда

            Material restore = entry.restore();
            Schedulers.runAtLocation(plugin,
                    new Location(world, key.x() + 0.5, key.y() + 0.5, key.z() + 0.5),
                    () -> {
                        try {
                            Block block = world.getBlockAt(key.x(), key.y(), key.z());
                            if (block.getType() != restore) {
                                block.setType(restore, false);
                            }
                        } catch (IllegalStateException ex) {
                            // Folia: чужой регион. Следующий проход восстановит.
                            plugin.getLogger().fine("Временный блок не восстановлен: "
                                    + ex.getMessage());
                        }
                    });
        }
        blocks.clear();
    }
}
