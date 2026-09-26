package ru.qweyns.qwetnts.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.hologram.HologramSettings.Provider;
import ru.qweyns.qwetnts.hologram.HologramSettings.Raw;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чтение настроек голограммы: общая секция {@code holograms} из
 * {@code config.yml} и секция {@code hologram} файла динамита поверх неё.
 */
class HologramSettingsTest {

    private static @NotNull YamlConfiguration yaml(@NotNull String content) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(content);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        return cfg;
    }

    private static ConfigurationSection section(@NotNull String content) {
        return yaml(content).getConfigurationSection("hologram");
    }

    @Test
    void missingSectionGivesDefaults() {
        assertSame(HologramSettings.DEFAULT,
                HologramSettings.from(null, HologramSettings.DEFAULT));
    }

    @Test
    void readsFullSection() {
        YamlConfiguration cfg = yaml("""
                holograms:
                  enabled: false
                  provider: DECENT
                  max-active: 12
                  follow-projectile: false
                  update-interval-ticks: 10
                  lines:
                    - "Осталось %seconds%"
                  settings:
                    offset: 2.5
                    display-range: 40
                    see-through: true
                    shadow: false
                    scale: 1.5
                    billboard: FIXED
                    text-alignment: RIGHT
                    background: "#40000000"
                    permission: "qwetnts.admin"
                """);

        HologramSettings read = HologramSettings.from(
                cfg.getConfigurationSection("holograms"), HologramSettings.DEFAULT);

        assertFalse(read.enabled());
        assertEquals(Provider.DECENT, read.provider());
        assertEquals(12, read.maxActive());
        assertFalse(read.follow());
        assertEquals(10L, read.updateIntervalTicks());
        assertEquals(2.5, read.offset());
        assertEquals(40.0, read.displayRange());
        assertTrue(read.seeThrough());
        assertFalse(read.shadow());
        assertEquals(1.5f, read.scale());
        assertEquals("FIXED", read.billboard());
        assertEquals("RIGHT", read.alignment());
        assertEquals("#40000000", read.background());
        assertEquals("qwetnts.admin", read.permission());
        assertEquals(1, read.lines().size());
    }

    @Test
    void clampsDangerousValues() {
        YamlConfiguration cfg = yaml("""
                holograms:
                  max-active: 9999
                  update-interval-ticks: 0
                  settings:
                    offset: 100
                    display-range: -5
                    scale: 99
                """);

        HologramSettings read = HologramSettings.from(
                cfg.getConfigurationSection("holograms"), HologramSettings.DEFAULT);

        assertEquals(512, read.maxActive());          // предел: не дать повесить сервер
        assertEquals(1L, read.updateIntervalTicks()); // ноль тиков — тикер встанет
        assertEquals(8.0, read.offset());
        assertEquals(1.0, read.displayRange());
        assertEquals(8.0f, read.scale());
    }

    @Test
    void providerParseIsLenient() {
        assertEquals(Provider.FANCY, Provider.parse("fAnCy", Provider.AUTO));
        assertEquals(Provider.NATIVE, Provider.parse(" native ", Provider.AUTO));
        assertEquals(Provider.AUTO, Provider.parse("нет такого", Provider.AUTO));
        assertEquals(Provider.AUTO, Provider.parse(null, Provider.AUTO));
        assertEquals(Provider.AUTO, Provider.parse("  ", Provider.AUTO));
    }

    @Test
    void perDynamiteOverrideReplacesOnlyWhatIsSet() {
        Raw raw = Raw.from(section("""
                enabled: false
                lines:
                  - "До взрыва: %seconds%"
                """));

        HologramSettings merged = HologramSettings.merge(raw, HologramSettings.DEFAULT);

        assertFalse(merged.enabled());                     // из файла динамита
        assertEquals(List.of("До взрыва: %seconds%"), merged.lines());
        assertEquals(HologramSettings.DEFAULT.maxActive(), merged.maxActive());   // из config.yml
        assertEquals(HologramSettings.DEFAULT.offset(), merged.offset());
        assertEquals(HologramSettings.DEFAULT.provider(), merged.provider());
    }

    @Test
    void perDynamiteWithoutSectionTakesGlobal() {
        // Секции нет вовсе — работают общие настройки, до единого значения.
        assertEquals(HologramSettings.DEFAULT, HologramSettings.merge(null, HologramSettings.DEFAULT));
        assertEquals(HologramSettings.DEFAULT, HologramSettings.merge(Raw.from(null), HologramSettings.DEFAULT));
    }

    /**
     * Встроенный {@code config.yml} должен содержать рабочую секцию
     * голограмм: с пустыми строками плагин молча ничего не покажет.
     */
    @Test
    void bundledConfigHasWorkingHologramSection() {
        YamlConfiguration cfg;
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml не найден в ресурсах");
            cfg = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        ConfigurationSection section = cfg.getConfigurationSection("holograms");
        assertNotNull(section, "в config.yml нет секции holograms");
        assertTrue(section.getBoolean("enabled", false));

        HologramSettings read = HologramSettings.from(section, HologramSettings.DEFAULT);
        assertTrue(read.enabled());
        assertFalse(read.lines().isEmpty(), "строки голограммы пусты — показывать нечего");
        assertEquals(Provider.AUTO, read.provider());
        assertTrue(read.maxActive() > 0);
        assertTrue(read.updateIntervalTicks() > 0);
    }
}
