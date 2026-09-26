package ru.qweyns.qwetnts.integration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.Settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Отправка алертов в Discord через вебхук (Фаза 2).
 *
 * <p>Никаких сторонних библиотек: в Java 21 есть штатный
 * {@link HttpClient}. Все запросы уходят асинхронно и никогда не блокируют
 * игровой поток, а любая ошибка сети превращается максимум в запись в лог —
 * сервер из-за Discord не падает и не тормозит.</p>
 *
 * <p>Включается секцией {@code settings.alerts.discord} в config.yml.
 * URL вебхука проверяется при загрузке настроек
 * ({@link Settings.Discord#isAllowedUrl(String)}): только HTTPS и только
 * известные хосты Discord.</p>
 */
public final class DiscordWebhook {

    /** Минимальный интервал между отправками — чтобы спам рейдом не забанил вебхук. */
    private static final long MIN_INTERVAL_MILLIS = 1_000L;

    private final QweTnts plugin;
    private final AtomicLong lastSentAt = new AtomicLong(0L);

    private volatile HttpClient http;

    public DiscordWebhook(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Отправить алерт, если Discord включён и это событие интересно. */
    public void send(@NotNull Settings.Discord.DiscordEvent event, @NotNull String content) {
        Settings.Discord discord = plugin.settings().alerts().discord();
        if (!discord.wants(event)) return;

        String text = content.trim();
        if (text.isEmpty()) return;

        // Грубая защита от флуда: один алерт в секунду максимум.
        long now = System.currentTimeMillis();
        long prev = lastSentAt.get();
        if (prev != 0L && now - prev < MIN_INTERVAL_MILLIS) return;
        if (!lastSentAt.compareAndSet(prev, now)) return;

        String body = buildBody(discord, text);

        HttpClient client = http();
        if (client == null) return;

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(discord.webhookUrl()))
                    .timeout(Duration.ofMillis(discord.timeoutMillis()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "QweTnts/" + plugin.getPluginMeta().getVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING, "Некорректный webhook-url Discord", ex);
            return;
        }

        // Discord отвечает 204 No Content — тело нам не нужно.
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Не удалось отправить алерт в Discord", error);
                        return;
                    }
                    int status = response == null ? -1 : response.statusCode();
                    if (status < 200 || status >= 300) {
                        plugin.getLogger().warning(
                                "Discord отклонил алерт, код " + status);
                    }
                });
    }

    /**
     * Остановка при выгрузке плагина: HTTP-клиент держит свои потоки, и без
     * этого вызова они мешали бы чистому рестарту (особенно на /reload).
     */
    public void shutdown() {
        HttpClient client = http;
        http = null;
        if (client == null) return;
        try {
            client.shutdownNow();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Ошибка остановки HTTP-клиента Discord", ex);
        }
    }

    private @Nullable HttpClient http() {
        HttpClient local = http;
        if (local != null) return local;

        synchronized (this) {
            if (http != null) return http;
            try {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Не удалось создать HTTP-клиент для Discord", ex);
                return null;
            }
            return http;
        }
    }

    /** Тело запроса: обычное сообщение вебхука. */
    static @NotNull String buildBody(@NotNull Settings.Discord discord, @NotNull String content) {
        String mention = discord.mentionRoleId();
        boolean hasMention = mention != null && !mention.isBlank();

        StringBuilder out = new StringBuilder(256);
        out.append('{');
        out.append("\"content\":\"").append(escape(hasMention ? "<@&" + mention.trim() + "> " : ""))
                .append(escape(content)).append("\"");
        out.append(",\"username\":\"").append(escape(discord.username())).append('"');
        if (discord.avatarUrl() != null && !discord.avatarUrl().isBlank()) {
            out.append(",\"avatar_url\":\"").append(escape(discord.avatarUrl())).append('"');
        }
        out.append('}');
        return out.toString();
    }

    static @NotNull String escape(@Nullable String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
