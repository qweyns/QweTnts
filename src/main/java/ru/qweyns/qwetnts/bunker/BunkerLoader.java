package ru.qweyns.qwetnts.bunker;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.DynamiteLoader;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;
import ru.qweyns.qwetnts.util.Materials;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Читает {@code bunker.yml} из корня папки плагина.
 *
 * <p>Файл один, потому что бункер на сервере один: это ивент замка на
 * нулевых координатах, а не массовая механика.</p>
 */
public final class BunkerLoader {

    private static final List<Material> DEFAULT_STAGES =
            List.of(Material.ANCIENT_DEBRIS, Material.CRYING_OBSIDIAN,
                    Material.OBSIDIAN, Material.AIR);

    /** Предел числа команд награды, чтобы конфиг не стал нагрузкой. */
    private static final int MAX_REWARD_COMMANDS = 32;

    private BunkerLoader() {
    }

    /**
     * @return настройки бункера; отсутствие файла, пустая секция или ошибка
     *         разбора — не поведение «уронить сервер», а отключённый бункер
     *         с предупреждением в лог
     */
    public static @NotNull BunkerSettings load(@NotNull QweTnts plugin, @NotNull File file) {
        if (!file.isFile()) return BunkerSettings.DISABLED;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось прочитать " + file.getName() + ": " + ex.getMessage(), ex);
            return BunkerSettings.DISABLED;
        }

        if (!yaml.getBoolean("enabled", false)) return BunkerSettings.DISABLED;

        String world = yaml.getString("world", "");
        if (world == null || world.isBlank()) {
            plugin.getLogger().warning(file.getName()
                    + ": не задан world — бункер отключён.");
            return BunkerSettings.DISABLED;
        }

        BunkerSettings.Wall wall = loadWall(plugin, yaml);
        if (!wall.isDefined()) {
            plugin.getLogger().warning(file.getName()
                    + ": зона стены задана неверно — бункер отключён.");
            return BunkerSettings.DISABLED;
        }

        Map<String, Double> chances = loadChances(plugin, yaml);

        return new BunkerSettings(
                true,
                world.trim(),
                wall,
                Math.max(0L, yaml.getLong("cooldown-millis", 500L)),
                chances,
                Math.max(0.0, yaml.getDouble("chances.default", 0.4)),
                loadRespawn(yaml),
                yaml.getBoolean("announce", true),
                new BunkerSettings.Effects(
                        DynamiteLoader.parseEffect(yaml.getConfigurationSection("effects.on-degrade")),
                        DynamiteLoader.parseEffect(yaml.getConfigurationSection("effects.on-breach"))),
                loadReward(plugin, yaml));
    }

    private static @NotNull BunkerSettings.Wall loadWall(@NotNull QweTnts plugin,
                                                         @NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("wall");
        if (section == null) return BunkerSettings.Wall.NONE;

        int[] from = parseCoords(section.get("from"));
        int[] to = parseCoords(section.get("to"));
        if (from == null || to == null) return BunkerSettings.Wall.NONE;

        List<Material> stages = new ArrayList<>();
        List<String> raw = section.getStringList("stages");
        if (raw.isEmpty()) {
            stages.addAll(DEFAULT_STAGES);
        } else {
            for (String value : raw) {
                Material material = Materials.parse(value);
                if (material == null) {
                    plugin.getLogger().warning("bunker: неизвестный материал стадии: " + value);
                    continue;
                }
                stages.add(material);
            }
        }

        // Последняя стадия — всегда AIR: это и есть «стена пробита».
        if (stages.isEmpty()) {
            stages.addAll(DEFAULT_STAGES);
        }
        if (stages.get(stages.size() - 1) != Material.AIR) {
            stages.add(Material.AIR);
        }
        if (stages.size() < 2) {
            return BunkerSettings.Wall.NONE;
        }

        return new BunkerSettings.Wall(
                Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]),
                Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2]),
                List.copyOf(stages),
                Math.max(0, section.getInt("hit-margin", 3)),
                section.getBoolean("protect", true));
    }

    private static @NotNull Map<String, Double> loadChances(@NotNull QweTnts plugin,
                                                            @NotNull YamlConfiguration yaml) {
        Map<String, Double> chances = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("chances");
        if (section == null) return Map.of();

        for (String key : section.getKeys(false)) {
            if ("default".equalsIgnoreCase(key)) continue;
            double value = section.getDouble(key, -1.0);
            if (value < 0.0) {
                plugin.getLogger().warning("bunker: некорректный шанс для " + key
                        + " — строка пропущена.");
                continue;
            }
            chances.put(key.toLowerCase(Locale.ROOT), value);
        }
        return Map.copyOf(chances);
    }

    /**
     * Команды награды за пробитую стену.
     *
     * <p>Пустые строки выбрасываем, а число команд ограничиваем: список
     * приходит из конфига, и сотня команд на каждый пробой — это уже не
     * награда, а нагрузка на сервер.</p>
     */
    private static @NotNull BunkerSettings.Reward loadReward(@NotNull QweTnts plugin,
                                                             @NotNull YamlConfiguration yaml) {
        List<String> raw = yaml.getStringList("reward.commands");
        if (raw.isEmpty()) return BunkerSettings.Reward.NONE;

        List<String> commands = new ArrayList<>(Math.min(raw.size(), MAX_REWARD_COMMANDS));
        for (String command : raw) {
            if (command == null || command.isBlank()) continue;
            if (commands.size() >= MAX_REWARD_COMMANDS) {
                plugin.getLogger().warning("bunker: слишком много команд награды, "
                        + "оставлены первые " + MAX_REWARD_COMMANDS);
                break;
            }
            commands.add(command.trim());
        }
        return commands.isEmpty()
                ? BunkerSettings.Reward.NONE
                : new BunkerSettings.Reward(List.copyOf(commands));
    }

    private static @NotNull BunkerSettings.Respawn loadRespawn(@NotNull YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("respawn");
        if (section == null) return BunkerSettings.Respawn.NONE;

        return new BunkerSettings.Respawn(
                section.getBoolean("enabled", false),
                Math.max(1L, section.getLong("interval-minutes", 60L)),
                section.getBoolean("random-stage", true));
    }

    /** Три целых числа из списка вида {@code [x, y, z]}. */
    /**
     * Три координаты — списком {@code [x, y, z]} или строкой через запятую
     * {@code "-8, 40, -8"}: второй вариант компактнее и его удобнее править
     * руками, поэтому принимаем оба.
     *
     * @param raw значение из конфига: список или строка
     * @return {@code null}, если значения нет или это не три целых числа
     */
    public static int @Nullable [] parseCoords(@Nullable Object raw) {
        List<?> parts;
        if (raw instanceof List<?> list) {
            parts = list;
        } else if (raw instanceof String text && !text.isBlank()) {
            parts = List.of(text.split(","));
        } else {
            return null;
        }
        if (parts.size() < 3) return null;

        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int value = parseInt(parts.get(i));
            if (value == Integer.MIN_VALUE) return null;
            out[i] = value;
        }
        return out;
    }

    /** Целое число из значения конфига; {@code Integer.MIN_VALUE} — не число. */
    private static int parseInt(@Nullable Object raw) {
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }
}
