package ru.qweyns.qwetnts.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка валидатора URL вебхука. Опечатка или «левый» адрес в конфиге
 * не должна превращаться в возможность слать запросы куда угодно.
 */
class SettingsDiscordTest {

    @Test
    void allowsDiscordHostsOverHttps() {
        assertTrue(Settings.Discord.isAllowedUrl(
                "https://discord.com/api/webhooks/123/abc"));
        assertTrue(Settings.Discord.isAllowedUrl(
                "https://canary.discord.com/api/webhooks/123/abc"));
    }

    @Test
    void rejectsEverythingElse() {
        assertFalse(Settings.Discord.isAllowedUrl("http://discord.com/api/webhooks/1/a"));
        assertFalse(Settings.Discord.isAllowedUrl("https://evil.example.com/hook"));
        assertFalse(Settings.Discord.isAllowedUrl("https://discord.com.evil.example.com/hook"));
        assertFalse(Settings.Discord.isAllowedUrl(""));
        assertFalse(Settings.Discord.isAllowedUrl("not a url at all"));
    }

    @Test
    void disabledByDefault() {
        assertFalse(Settings.Discord.DISABLED.enabled());
        assertFalse(Settings.Discord.DISABLED.wants(
                Settings.Discord.DiscordEvent.REGION_DESTROYED));
        assertEquals("QweTnts", Settings.Discord.DISABLED.username());
    }
}
