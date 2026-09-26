package ru.qweyns.qwetnts.integration;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.config.Settings;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сборка JSON для Discord. Спецсимволы в тексте (кавычки, переводы строк)
 * обязаны экранироваться, иначе вебхук вернёт 400 и алерт потеряется.
 */
class DiscordWebhookTest {

    private static Settings.Discord discord(String username, String avatar, String mention) {
        return new Settings.Discord(true, "https://discord.com/api/webhooks/1/a",
                username, avatar, mention, 5000L,
                Set.of(Settings.Discord.DiscordEvent.REGION_DESTROYED));
    }

    @Test
    void escapesSpecialCharacters() {
        assertEquals("say \\\"hi\\\"", DiscordWebhook.escape("say \"hi\""));
        assertEquals("a\\\\b", DiscordWebhook.escape("a\\b"));
        assertEquals("line1\\nline2", DiscordWebhook.escape("line1\nline2"));
        assertEquals("", DiscordWebhook.escape(null));
    }

    @Test
    void bodyContainsContentAndUsername() {
        String body = DiscordWebhook.buildBody(discord("QweTnts", "", ""), "Приват 42 уничтожен");

        assertTrue(body.contains("\"content\":\"Приват 42 уничтожен\""), body);
        assertTrue(body.contains("\"username\":\"QweTnts\""), body);
        assertFalse(body.contains("avatar_url"), "пустой avatar-url не должен попадать в JSON");
    }

    @Test
    void bodyAddsMentionAndAvatar() {
        String body = DiscordWebhook.buildBody(discord("Qwe", "https://x/a.png", "777"),
                "тест \"кавычки\"");

        assertTrue(body.contains("\"content\":\"<@&777> тест \\\"кавычки\\\"\""), body);
        assertTrue(body.contains("\"avatar_url\":\"https://x/a.png\""), body);
    }
}
