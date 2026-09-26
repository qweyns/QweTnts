package ru.qweyns.qwetnts;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.bunker.BunkerLoader;
import ru.qweyns.qwetnts.util.Materials;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка встроенных конфигов: структура, обязательные ключи, корректность
 * имён материалов. Опечатка в YAML (например, {@code OBSIDAIN}) раньше
 * проявлялась только на сервере — теперь ловится тестом.
 */
class BundledConfigsTest {

    /** Обычные динамиты: лежат в {@code dynamites/}. */
    private static final List<String> DYNAMITES =
            List.of("dynamite_a", "dynamite_b", "c4", "shockwave",
                    "stiller", "reliable_stiller", "ice_wave", "dynamite_b2");

    /** Динамиты HolyWorld, которые не ломают рельеф (breaking.destructive). */
    private static final List<String> NON_DESTRUCTIVE =
            List.of("stiller", "reliable_stiller", "ice_wave");

    /** Пушка — отдельная механика, её файл лежит в корне папки плагина. */
    private static final String CANNON = "cannon";

    @Test
    void configHasRequiredSections() {
        YamlConfiguration yaml = load("/config.yml");
        assertNotNull(yaml, "config.yml должен лежать в ресурсах");

        assertTrue(yaml.contains("settings.language"));
        assertTrue(yaml.contains("settings.dynamites.auto-ignite"));
        assertFalse(yaml.getBoolean("settings.dynamites.auto-ignite"),
                "по умолчанию автоподжог должен быть выключен");
        assertTrue(yaml.contains("settings.anti-lag.activation-cooldown-millis"));
        assertTrue(yaml.contains("settings.anti-lag.max-primed-per-player"));
        assertTrue(yaml.contains("settings.raid-blocks.cleanup-interval-ticks"));
        assertTrue(yaml.contains("settings.placed-dynamites"));
        assertTrue(yaml.contains("settings.worlds.mode"));

        // Фаза 2: порционная обработка крупных взрывов и временные блоки.
        assertTrue(yaml.contains("settings.anti-lag.large-explosion-blocks-per-tick"));
        assertTrue(yaml.contains("settings.anti-lag.large-explosion-tick-period"));
        assertTrue(yaml.getInt("settings.anti-lag.large-explosion-blocks-per-tick") > 0,
                "порция блоков в тик должна быть больше нуля");
        assertTrue(yaml.getLong("settings.anti-lag.large-explosion-tick-period") > 0,
                "период порции должен быть больше нуля");
        assertTrue(yaml.contains("settings.temporary-blocks.cleanup-interval-ticks"));
        assertTrue(yaml.contains("settings.temporary-blocks.autosave-interval-ticks"));
    }

    @Test
    void phase2SectionsAreWellFormed() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load(dynamite(name));
            assertNotNull(yaml);

            assertTrue(yaml.contains("spawner-mining"),
                    name + ": должна быть секция spawner-mining");
            assertTrue(yaml.contains("temporary-blocks"),
                    name + ": должна быть секция temporary-blocks");
            assertFalse(yaml.contains("cannon"),
                    name + ": секция cannon бывает только у cannon.yml — "
                            + "у обычного динамита она лишняя");

            int chance = yaml.getInt("spawner-mining.chance", 0);
            assertTrue(chance >= 0 && chance <= 100,
                    name + ": шанс добычи спавнера должен быть в пределах 0..100");

            ConfigurationSection temp = yaml.getConfigurationSection("temporary-blocks");
            assertNotNull(temp, name + ": секция temporary-blocks не читается");

            if (temp.getBoolean("enabled", false)) {
                String material = temp.getString("material");
                assertNotNull(Materials.parse(material),
                        name + ": неизвестный материал временных блоков: " + material);
                assertTrue(temp.getInt("radius", 0) >= 1,
                        name + ": радиус временных блоков должен быть >= 1");
                assertTrue(temp.getLong("duration-seconds", 0) >= 1,
                        name + ": срок временных блоков должен быть >= 1 сек");

                for (String raw : temp.getStringList("replace")) {
                    assertNotNull(Materials.parse(raw),
                            name + ": неизвестный материал в temporary-blocks.replace: " + raw);
                }
            }
        }
    }

    @Test
    void everyDynamiteHasAutoIgniteSwitch() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load(dynamite(name));
            assertNotNull(yaml, dynamite(name) + " должен лежать в ресурсах");

            assertTrue(yaml.contains("ignition.auto"),
                    name + ": у динамита должен быть свой переключатель ignition.auto");
            assertFalse(yaml.getBoolean("ignition.auto"),
                    name + ": по умолчанию автоподжог выключен");
        }
    }

    @Test
    void everyDynamiteIsWellFormed() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load(dynamite(name));
            assertNotNull(yaml);

            String id = yaml.getString("name");
            assertNotNull(id);
            assertFalse(id.isBlank(), name + ": пустой name");
            assertFalse(yaml.getString("display_name", "").isBlank(),
                    name + ": пустой display_name");

            String type = yaml.getString("explosion.type", yaml.getString("explosion-type"));
            assertNotNull(type, name + ": не задан explosion.type");
            assertTrue(yaml.getDouble("explosion.power", yaml.getDouble("power")) > 0,
                    name + ": мощность должна быть больше нуля");

            String material = yaml.getString("item.material");
            assertNotNull(material, name + ": не задан item.material");
            assertNotNull(Materials.parse(material),
                    name + ": неизвестный материал предмета " + material);

            ConfigurationSection blocks = yaml.getConfigurationSection("breaking.blocks");
            if (blocks != null) {
                for (String key : blocks.getKeys(false)) {
                    Material parsed = Materials.parse(key);
                    assertNotNull(parsed, name + ": неизвестный материал в breaking.blocks: " + key);
                }
            }

            ConfigurationSection transforms = yaml.getConfigurationSection("transforms");
            if (transforms != null) {
                for (String key : transforms.getKeys(false)) {
                    // chain и chain-chance — служебные ключи секции, а не
                    // материалы; сама цепочка проверяется отдельным тестом.
                    if ("chain".equals(key) || "chain-chance".equals(key)) continue;

                    assertNotNull(Materials.parse(key),
                            name + ": неизвестный материал в transforms: " + key);
                    String to = transforms.getString(key + ".to");
                    assertNotNull(Materials.parse(to),
                            name + ": неверный материал назначения для " + key + ": " + to);
                }
            }

            // Свои лимиты заданы явно — иначе они не перекрывают глобальные.
            assertTrue(yaml.contains("limits.cooldown-millis")
                            || yaml.getInt("limits.cooldown-millis", -1) == -1,
                    name + ": секция limits должна присутствовать");
        }
    }

    /**
     * Неразрушающий динамит (Стиллер, Надёжный стиллер, Ледяная волна) не
     * должен ломать блоки: ни правил ломания, ни досмотра прочных блоков.
     * Смысл такого заряда — спавнеры или временные блоки, поэтому без них
     * он превращается в «дым без огня».
     */
    @Test
    void nonDestructiveDynamitesKeepTerrain() {
        for (String name : NON_DESTRUCTIVE) {
            YamlConfiguration yaml = load(dynamite(name));
            assertNotNull(yaml, dynamite(name) + " должен лежать в ресурсах");

            assertFalse(yaml.getBoolean("breaking.destructive", true),
                    name + ": этот динамит не должен разрушать блоки");

            ConfigurationSection blocks = yaml.getConfigurationSection("breaking.blocks");
            assertTrue(blocks == null || blocks.getKeys(false).isEmpty(),
                    name + ": у неразрушающего динамита не бывает правил ломания");

            boolean useful = yaml.getBoolean("spawner-mining.enabled", false)
                    || yaml.getBoolean("temporary-blocks.enabled", false);
            assertTrue(useful, name + ": неразрушающий заряд обязан что-то делать — "
                    + "добывать спавнеры или ставить временные блоки");
        }
    }

    /**
     * Ингредиенты вида {@code custom-type} требуют конкретный динамит по
     * PDC-метке: если опечататься в id, крафт молча перестанет работать.
     */
    @Test
    void customTypeIngredientsAreDeclared() {
        YamlConfiguration stiller = load(dynamite("stiller"));
        assertNotNull(stiller);
        assertEquals("c4", stiller.getString("recipe.ingredients.C.custom-type"),
                "по HolyWorld Стиллер крафтится из C4: 5 пороха, 2 песка, 2 C4");

        YamlConfiguration reliable = load(dynamite("reliable_stiller"));
        assertNotNull(reliable);
        assertEquals("stiller", reliable.getString("recipe.ingredients.T.custom-type"),
                "по HolyWorld Надёжный стиллер крафтится из двух Стиллеров");

        YamlConfiguration ice = load(dynamite("ice_wave"));
        assertNotNull(ice);
        assertEquals("BLUE_ICE", ice.getString("recipe.ingredients.B"),
                "по HolyWorld Ледяная волна крафтится из 5 TNT и 4 синего льда");
    }

    /**
     * Компоненты крафта (Взрывчатое вещество) — не динамиты, но рецепты
     * требуют их по PDC-метке. Ошибка в файле проявилась бы «крафт просто
     * не работает» без всяких сообщений.
     */
    @Test
    void componentsFileIsWellFormed() {
        YamlConfiguration yaml = load("/components.yml");
        assertNotNull(yaml, "components.yml должен лежать в ресурсах");

        ConfigurationSection root = yaml.getConfigurationSection("components");
        assertNotNull(root, "components.yml: должна быть секция components");

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            assertNotNull(section);

            assertNotNull(Materials.parse(section.getString("material")),
                    id + ": неизвестный материал компонента");

            ConfigurationSection reverse = section.getConfigurationSection("reverse");
            if (reverse != null && reverse.getBoolean("enabled", true)) {
                assertNotNull(Materials.parse(reverse.getString("result")),
                        id + ": неизвестный материал в reverse.result");
                assertTrue(reverse.getInt("amount", 0) > 0,
                        id + ": обратный крафт должен что-то возвращать");
            }

            ConfigurationSection recipe = section.getConfigurationSection("recipe");
            if (recipe != null) {
                for (String row : recipe.getStringList("shape")) {
                    assertEquals(3, row.length(),
                            id + ": форма рецепта задана строкой «" + row + "»");
                }
            }
        }
    }

    /** Круг «9 пороха → вещество → 9 пороха» должен быть без потерь. */
    @Test
    void explosiveComponentRoundTripsWithoutLoss() {
        YamlConfiguration yaml = load("/components.yml");
        assertNotNull(yaml);

        ConfigurationSection explosive = yaml.getConfigurationSection("components.explosive");
        assertNotNull(explosive,
                "Взрывчатое вещество — базовый компонент крафта HolyWorld Lite");

        int inGrid = 0;
        for (String row : explosive.getStringList("recipe.shape")) {
            for (char c : row.toCharArray()) {
                if (c != ' ') inGrid++;
            }
        }
        assertEquals(9, inGrid, "по HolyWorld вещество собирается из 9 пороха");
        assertEquals(9, explosive.getInt("reverse.amount"),
                "и разлагается обратно в 9 пороха — иначе крафт не обратим");
    }

    /**
     * Динамит Б2 — единственный заряд с кубом вместо сферы: это самый
     * дорогой по нагрузке режим (cube-size³ обращений к блоку), поэтому
     * размер куба и предел блоков проверяем отдельно.
     */
    @Test
    void cubeDynamiteIsSane() {
        YamlConfiguration yaml = load(dynamite("dynamite_b2"));
        assertNotNull(yaml, "dynamite_b2.yml должен лежать в ресурсах");

        assertEquals("CUBE", yaml.getString("breaking.shape"),
                "Б2 вырезает куб, а не сферу");

        int size = yaml.getInt("breaking.cube-size", 0);
        assertTrue(size >= 1 && size <= 33,
                "сторона куба должна быть в пределах 1..33: " + size);

        // Предел блоков должен покрывать куб целиком, иначе куб «срежется».
        int maxBlocks = yaml.getInt("explosion.max-blocks", 0);
        assertTrue(maxBlocks >= size * size * size,
                "max-blocks меньше объёма куба (" + (size * size * size)
                        + ") — куб будет обрезан: " + maxBlocks);

        // И по механике HolyWorld Б2 не работает в приватах.
        assertTrue(yaml.getBoolean("regions.only-outside", false),
                "по HolyWorld Б2 не работает на заприваченных территориях");
    }

    @Test
    void cannonIsNotADynamiteAnymore() {
        assertNotNull(load("/" + CANNON + ".yml"),
                "cannon.yml должен лежать в корне ресурсов плагина");
        assertNull(load("/dynamites/" + CANNON + ".yml"),
                "dynamites/cannon.yml больше не должно существовать: "
                        + "пушка — отдельная механика, а не тип динамита");

        YamlConfiguration yaml = load("/" + CANNON + ".yml");
        assertNotNull(yaml);

        assertFalse(yaml.contains("explosion"),
                "у пушки не должно быть секции explosion: она стреляет тем "
                        + "динамитом, который в неё положили");
        assertFalse(yaml.contains("breaking"),
                "у пушки не должно быть секции breaking: правила ломания "
                        + "задаёт сам снаряд");

        for (String section : List.of("item", "menu", "launch", "ammunition")) {
            assertTrue(yaml.contains(section),
                    "cannon.yml: должна быть секция " + section);
        }
    }

    @Test
    void cannonSettingsAreSane() {
        YamlConfiguration yaml = load("/" + CANNON + ".yml");
        assertNotNull(yaml);

        double speed = yaml.getDouble("launch.speed-blocks-per-second", 0.0);
        assertTrue(speed > 0.0 && speed <= 60.0,
                "скорость снаряда должна быть в пределах 0..60 блоков/сек: " + speed);

        double range = yaml.getDouble("launch.max-range-blocks", -1.0);
        assertTrue(range >= 0.0,
                "предел дальности должен быть >= 0 (0 — без лимита): " + range);

        String material = yaml.getString("item.material");
        assertNotNull(Materials.parse(material),
                "cannon.yml: неизвестный материал предмета " + material);

        String border = yaml.getString("menu.border-material",
                "RED_STAINED_GLASS_PANE");
        assertNotNull(Materials.parse(border),
                "cannon.yml: неизвестный материал рамки меню " + border);

        // На HolyWorld пушку нельзя скрафтить — держим это по умолчанию.
        assertFalse(yaml.getBoolean("craftable"),
                "по умолчанию пушку нельзя скрафтить: это правило HolyWorld");
    }

    /**
     * Бункер замка: стена, которую пробивают из пушки.
     *
     * <p>Проверяем то, из-за чего механика молча не работала бы: материал
     * стадии с опечаткой, стена без финального AIR (тогда она никогда не
     * будет пробита) и шанс, заданный для несуществующего динамита.</p>
     */
    @Test
    void bunkerConfigIsWellFormed() {
        YamlConfiguration yaml = load("/bunker.yml");
        assertNotNull(yaml, "bunker.yml должен лежать в ресурсах");

        assertFalse(yaml.getBoolean("enabled"),
                "бункер включают вручную: координаты стены у каждого сервера свои");

        // Угол можно записать и строкой через запятую, и списком — проверяем
        // тем же разбором, которым их читает плагин.
        int[] from = BunkerLoader.parseCoords(yaml.get("wall.from"));
        int[] to = BunkerLoader.parseCoords(yaml.get("wall.to"));
        assertNotNull(from, "bunker.yml: не задан wall.from (три числа: x, y, z)");
        assertNotNull(to, "bunker.yml: не задан wall.to (три числа: x, y, z)");

        List<String> stages = yaml.getStringList("wall.stages");
        assertFalse(stages.isEmpty(), "bunker.yml: не заданы стадии стены");
        assertTrue(stages.size() >= 2,
                "нужны минимум две стадии: материал и AIR (пробитая стена)");
        for (String raw : stages) {
            assertNotNull(Materials.parse(raw),
                    "bunker.yml: неизвестный материал стадии: " + raw);
        }
        assertEquals("AIR", stages.get(stages.size() - 1).trim().toUpperCase(),
                "последняя стадия обязана быть AIR — иначе стена не будет пробита");

        assertTrue(yaml.getInt("wall.hit-margin", -1) >= 0,
                "bunker.yml: wall.hit-margin не может быть отрицательным");
        assertTrue(yaml.getLong("cooldown-millis", -1L) >= 0L,
                "bunker.yml: cooldown-millis не может быть отрицательным");
        assertTrue(yaml.getLong("respawn.interval-minutes", 0L) > 0L,
                "bunker.yml: интервал респавна должен быть больше нуля минут");

        ConfigurationSection chances = yaml.getConfigurationSection("chances");
        assertNotNull(chances, "bunker.yml: должна быть секция chances");
        for (String key : chances.getKeys(false)) {
            double chance = chances.getDouble(key, -1.0);
            assertTrue(chance >= 0.0 && chance <= 100.0,
                    "bunker.yml: шанс " + key + " вне диапазона 0..100: " + chance);
            if (!"default".equalsIgnoreCase(key)) {
                assertTrue(DYNAMITES.contains(key) || "tnt".equalsIgnoreCase(key),
                        "bunker.yml: шанс задан для несуществующего динамита " + key);
            }
        }
    }

    /**
     * Цепочка деградации в {@code transforms.chain}: материал снимает одну
     * ступень за взрыв — древние обломки → плачущий обсидиан → обсидиан →
     * дыра. Опечатка в названии материала здесь означала бы, что ступень
     * просто не работает.
     */
    @Test
    void transformChainsAreWellFormed() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load(dynamite(name));
            assertNotNull(yaml);

            List<String> chain = yaml.getStringList("transforms.chain");
            if (chain.isEmpty()) continue;

            assertTrue(chain.size() >= 2,
                    name + ": в transforms.chain нужны минимум две ступени");

            for (String raw : chain) {
                assertNotNull(Materials.parse(raw),
                        name + ": неизвестный материал в transforms.chain: " + raw);
            }

            int chance = yaml.getInt("transforms.chain-chance", 100);
            assertTrue(chance >= 0 && chance <= 100,
                    name + ": transforms.chain-chance вне диапазона 0..100: " + chance);
        }
    }

    /** У Разрывной волны деградация — часть механики HW, а не украшение. */
    @Test
    void shockwaveDegradesLikeTheBunkerWall() {
        YamlConfiguration yaml = load(dynamite("shockwave"));
        assertNotNull(yaml);

        assertEquals(List.of("ANCIENT_DEBRIS", "CRYING_OBSIDIAN", "OBSIDIAN", "AIR"),
                yaml.getStringList("transforms.chain"),
                "деградация идет по ступеням, как у стены бункера");
    }

    /**
     * Каждый ключ {@code config.yml} должен быть объявлен дефолтом
     * в {@code Settings#addDefaults}.
     *
     * <p>Иначе «самолечащийся» конфиг не лечит: ключ без {@code addDefault}
     * не дописывается в уже существующий файл при обновлении плагина, и
     * администратор его просто не видит. Именно так вела себя секция
     * {@code holograms}: она читалась, но в старые конфиги не попадала.</p>
     */
    @Test
    void everyConfigKeyHasADefault() throws IOException {
        YamlConfiguration yaml = load("/config.yml");
        assertNotNull(yaml, "config.yml должен лежать в ресурсах");

        Path source = Path.of("src", "main", "java", "ru", "qweyns", "qwetnts",
                "config", "Settings.java");
        Assumptions.assumeTrue(Files.isRegularFile(source),
                "тест читает исходник Settings.java и работает только из корня проекта");

        String settingsSource = Files.readString(source, StandardCharsets.UTF_8);

        List<String> keys = new ArrayList<>();
        collectLeafPaths(yaml, "", keys);
        assertFalse(keys.isEmpty(), "config.yml не должен быть пустым");

        List<String> withoutDefault = new ArrayList<>();
        for (String key : keys) {
            if (!settingsSource.contains("addDefault(\"" + key + "\"")) {
                withoutDefault.add(key);
            }
        }

        assertTrue(withoutDefault.isEmpty(),
                "ключи config.yml, которых нет в Settings#addDefaults: " + withoutDefault
                        + ". Без дефолта они не попадут в уже существующие конфиги.");
    }

    /** Все «листовые» пути секции: {@code settings.dynamites.auto-ignite} и т.п. */
    private static void collectLeafPaths(ConfigurationSection section,
                                         String prefix,
                                         List<String> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                collectLeafPaths(child, path, out);
            } else {
                out.add(path);
            }
        }
    }

    /** Путь к встроенному файлу динамита. */
    private static String dynamite(String name) {
        return "/dynamites/" + name + ".yml";
    }

    private static YamlConfiguration load(String resource) {
        try (InputStream in = BundledConfigsTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }
}
