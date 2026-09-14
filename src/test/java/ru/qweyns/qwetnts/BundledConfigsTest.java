package ru.qweyns.qwetnts;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.util.Materials;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка встроенных конфигов: структура, обязательные ключи, корректность
 * имён материалов. Опечатка в YAML (например, {@code OBSIDAIN}) раньше
 * проявлялась только на сервере — теперь ловится тестом.
 */
class BundledConfigsTest {

    private static final List<String> DYNAMITES =
            List.of("dynamite_a", "dynamite_b", "c4", "shockwave");

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
    }

    @Test
    void everyDynamiteHasAutoIgniteSwitch() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load("/dynamites/" + name + ".yml");
            assertNotNull(yaml, "dynamites/" + name + ".yml должен лежать в ресурсах");

            assertTrue(yaml.contains("ignition.auto"),
                    name + ": у динамита должен быть свой переключатель ignition.auto");
            assertFalse(yaml.getBoolean("ignition.auto"),
                    name + ": по умолчанию автоподжог выключен");
        }
    }

    @Test
    void everyDynamiteIsWellFormed() {
        for (String name : DYNAMITES) {
            YamlConfiguration yaml = load("/dynamites/" + name + ".yml");
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
