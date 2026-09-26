package ru.qweyns.qwetnts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.config.LangKeys;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка от повторения бага «сырой ключ в чате»: если в lang-файле ключ
 * написан с опечаткой или удалён, сборка падает здесь, а не у игрока в чате.
 */
class LangKeysTest {

    private static final List<String> LANGUAGES = List.of("ru_RU", "en_US");

    @Test
    void everyKeyUsedInCodeExistsInAllLanguages() {
        for (String language : LANGUAGES) {
            YamlConfiguration yaml = load("/lang/" + language + ".yml");
            assertNotNull(yaml, "файл lang/" + language + ".yml должен лежать в ресурсах");

            for (String key : LangKeys.all()) {
                assertTrue(yaml.contains(key),
                        "lang/" + language + ".yml: нет ключа '" + key + "'");
                String value = yaml.getString(key);
                assertNotNull(value, "lang/" + language + ".yml: ключ '" + key + "' пуст");
                assertFalse(value.isBlank(),
                        "lang/" + language + ".yml: ключ '" + key + "' без текста");
            }
        }
    }

    @Test
    void languagesHaveSameKeySet() {
        YamlConfiguration reference = load("/lang/ru_RU.yml");
        for (String language : LANGUAGES) {
            YamlConfiguration yaml = load("/lang/" + language + ".yml");
            for (String key : reference.getKeys(true)) {
                assertTrue(yaml.contains(key),
                        "lang/" + language + ".yml: нет ключа '" + key + "' (есть в ru_RU)");
            }
            for (String key : yaml.getKeys(true)) {
                assertTrue(reference.contains(key),
                        "lang/ru_RU.yml: нет ключа '" + key + "' (есть в " + language + ")");
            }
        }
    }

    @Test
    void prefixIsDefinedEverywhere() {
        for (String language : LANGUAGES) {
            YamlConfiguration yaml = load("/lang/" + language + ".yml");
            String prefix = yaml.getString(LangKeys.PREFIX);
            assertNotNull(prefix);
            assertFalse(prefix.isBlank(), "lang/" + language + ".yml: пустой prefix");
        }
    }

    private static YamlConfiguration load(String resource) {
        try (InputStream in = LangKeysTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }
}
