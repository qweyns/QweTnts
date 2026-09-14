package ru.qweyns.qwetnts.dynamite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Io;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Установленные, но ещё не подожжённые динамиты.
 *
 * <p>Когда {@code settings.dynamites.auto-ignite} выключен, игрок ставит
 * динамит обычным блоком TNT, а поджигает его отдельно — огнивом, огнём,
 * лавой или другим взрывом. Чтобы плагин знал, какой именно это динамит,
 * позиция запоминается здесь и переживает рестарт сервера
 * (атомарная запись в {@code placed.yml}).</p>
 */
public final class PlacedDynamiteManager {

    /** Ключ позиции: мир + координаты блока. */
    public record Key(@NotNull String world, int x, int y, int z) {

        public static @NotNull Key of(@NotNull Block block) {
            return new Key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        public static @NotNull Key of(@NotNull Location location) {
            World world = location.getWorld();
            return new Key(world == null ? "" : world.getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
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

    /** Запись об установленном динамите. */
    public record Placed(@NotNull String typeId,
                         @Nullable UUID placedBy,
                         @NotNull String placedByName,
                         long placedAtMillis) {
    }

    private final QweTnts plugin;
    private final File file;
    private final Map<Key, Placed> placed = new ConcurrentHashMap<>();

    public PlacedDynamiteManager(@NotNull QweTnts plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "placed.yml");
    }

    public boolean contains(@NotNull Block block) {
        return placed.containsKey(Key.of(block));
    }

    public @Nullable Placed at(@NotNull Block block) {
        return placed.get(Key.of(block));
    }

    public @Nullable Placed at(@NotNull Location location) {
        return placed.get(Key.of(location));
    }

    /** Запомнить установленный динамит. */
    public void put(@NotNull Block block, @NotNull String typeId,
                    @Nullable UUID placedBy, @NotNull String placedByName) {
        placed.put(Key.of(block), new Placed(typeId, placedBy, placedByName,
                System.currentTimeMillis()));
    }

    /** Снять запись (динамит подожжён, сломан или пропал). */
    public @Nullable Placed remove(@NotNull Block block) {
        return placed.remove(Key.of(block));
    }

    public @Nullable Placed remove(@NotNull Location location) {
        return placed.remove(Key.of(location));
    }

    public int size() {
        return placed.size();
    }

    public boolean isEmpty() {
        return placed.isEmpty();
    }

    /** Снимок всех записей (для сохранения и обходов). */
    public @NotNull Collection<Map.Entry<Key, Placed>> entries() {
        return placed.entrySet();
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
            plugin.getLogger().log(Level.WARNING, "Не удалось прочитать placed.yml", ex);
            return;
        }

        long now = System.currentTimeMillis();
        for (String world : yaml.getKeys(false)) {
            var section = yaml.getConfigurationSection(world);
            if (section == null) continue;

            for (String coords : section.getKeys(false)) {
                Key key = Key.parse(world, coords);
                if (key == null) continue;

                var entry = section.getConfigurationSection(coords);
                String typeId = entry != null ? entry.getString("type") : section.getString(coords);
                if (typeId == null || typeId.isBlank()) continue;

                UUID uuid = null;
                if (entry != null) {
                    String raw = entry.getString("by");
                    if (raw != null && !raw.isBlank()) {
                        try {
                            uuid = UUID.fromString(raw);
                        } catch (IllegalArgumentException ignored) {
                            uuid = null;
                        }
                    }
                }
                String name = entry != null ? Objects.requireNonNullElse(entry.getString("name"), "") : "";
                long at = entry != null ? entry.getLong("at", now) : now;

                placed.put(key, new Placed(typeId, uuid, name == null ? "" : name, at));
            }
        }

        if (!placed.isEmpty()) {
            plugin.getLogger().info("Восстановлено установленных динамитов: " + placed.size());
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
        for (Map.Entry<Key, Placed> entry : placed.entrySet()) {
            Key key = entry.getKey();
            Placed value = entry.getValue();
            out.append(quote(key.world())).append(":\n");
            out.append("  ").append(quote(key.serialize())).append(":\n");
            out.append("    type: ").append(quote(value.typeId())).append('\n');
            if (value.placedBy() != null) {
                out.append("    by: ").append(value.placedBy()).append('\n');
            }
            if (!value.placedByName().isBlank()) {
                out.append("    name: ").append(quote(value.placedByName())).append('\n');
            }
            out.append("    at: ").append(value.placedAtMillis()).append('\n');
        }
        return out.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Убирает «фантомные» записи: блока TNT на месте уже нет (сгорел, сломан
     * без события, мир пересоздан). Дёргать из асинхронного потока нельзя —
     * нужен доступ к блоку, поэтому вызываем на главном потоке.
     */
    public int prune(@NotNull Server server) {
        int removed = 0;
        for (Key key : placed.keySet()) {
            World world = server.getWorld(key.world());
            if (world == null) {
                // Мир мог быть выгружен — запись не трогаем, она валидна.
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != org.bukkit.Material.TNT) {
                placed.remove(key);
                removed++;
            }
        }
        if (removed > 0 && Bukkit.isPrimaryThread()) {
            plugin.getLogger().fine("Удалено устаревших записей установленных динамитов: " + removed);
        }
        return removed;
    }

    public void clear() {
        placed.clear();
    }
}
