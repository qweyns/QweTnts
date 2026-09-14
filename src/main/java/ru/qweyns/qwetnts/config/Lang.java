package ru.qweyns.qwetnts.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Colors;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Локализация — архитектурный близнец {@code LanguageManager} из
 * QweProtectStones:
 *
 * <ul>
 *   <li>файлы {@code lang/<код>.yml}, встроенные копии распаковываются сами;</li>
 *   <li>недостающие ключи берутся из jar (после обновления плагина
 *       пользовательский файл не «теряет» новые сообщения);</li>
 *   <li>плейсхолдеры в стиле QPS: {@code %player%}, {@code %prefix%};</li>
 *   <li>оформление — MiniMessage + legacy-коды ({@code &c}, {@code &#RRGGBB});</li>
 *   <li>сообщения без плейсхолдеров кешируются уже собранными компонентами.</li>
 * </ul>
 *
 * <p>Ни одного сообщения в коде: всё, что видит игрок, лежит в lang-файле.
 * Отсутствующий ключ не печатается в чат «сырым» — пишется предупреждение
 * в консоль (один раз на ключ) и сообщение скрывается.</p>
 */
public final class Lang {

    /** Языки, которые лежат внутри jar. */
    private static final List<String> BUNDLED = List.of("ru_RU", "en_US");
    private static final String FALLBACK = "en_US";

    private final QweTnts plugin;
    private final Map<String, Component> cache = new ConcurrentHashMap<>();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    private FileConfiguration lang = new YamlConfiguration();

    public Lang(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Перезагрузить выбранный язык (вызывается на старте и из /qtnt reload). */
    public void load(@Nullable String code) {
        String selected = (code == null || code.isBlank()) ? FALLBACK : code;

        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку lang/ — будут использованы встроенные переводы.");
        }

        BUNDLED.forEach(this::saveBundled);

        File file = new File(folder, selected + ".yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("Файл языка " + selected + ".yml не найден! Загружаю " + FALLBACK + ".yml.");
            file = new File(folder, FALLBACK + ".yml");
            selected = FALLBACK;
        }

        FileConfiguration loaded = file.isFile()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        // Ключи, которых нет на диске, добираем из jar — иначе после обновления
        // плагина новые сообщения приходят пустыми.
        loadBundled(selected)
                .or(this::loadBundledFallback)
                .ifPresent(loaded::setDefaults);

        this.lang = loaded;
        this.cache.clear();
        this.warnedMissing.clear();
    }

    // ------------------------------------------------------------------
    // Отправка игроку
    // ------------------------------------------------------------------

    /**
     * Отправить сообщение.
     *
     * @param replacements пары «плейсхолдер → значение»:
     *                     {@code send(player, "cooldown", "%seconds%", "3")}
     */
    public void send(@Nullable CommandSender to, @NotNull String path, String... replacements) {
        if (to == null) return;
        Component message = get(path, replacements);
        if (message.equals(Component.empty())) return;
        to.sendMessage(message);
    }

    /** Сообщение со списком строк (каждая строка lang-файла — отдельная строка чата). */
    public void sendList(@Nullable CommandSender to, @NotNull String path, String... replacements) {
        if (to == null) return;
        for (Component line : getList(path, replacements)) {
            to.sendMessage(line);
        }
    }

    public @NotNull Component get(@NotNull String path, String... replacements) {
        if (!hasReplacements(replacements) && !lang.isList(path)) {
            Component cached = cache.get(path);
            if (cached != null) return cached;

            String raw = resolve(path);
            Component message = raw.isEmpty() ? Component.empty() : Colors.format(raw);
            cache.put(path, message);
            return message;
        }

        if (lang.isList(path)) {
            List<Component> lines = getList(path, replacements);
            if (lines.isEmpty()) return Component.empty();
            return Component.join(JoinConfiguration.separator(Component.newline()), lines);
        }

        String raw = resolve(path, replacements);
        return raw.isEmpty() ? Component.empty() : Colors.format(raw);
    }

    public @NotNull List<Component> getList(@NotNull String path, String... replacements) {
        List<String> raw = lang.getStringList(path);
        if (raw.isEmpty()) {
            String single = resolve(path, replacements);
            return single.isEmpty() ? List.of() : List.of(Colors.format(single));
        }

        List<Component> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line == null) continue;
            lines.add(Colors.format(applyPrefix(applyReplacements(line, replacements))));
        }
        return lines;
    }

    /** Сырой шаблон с подставленными значениями (для логов и сторонних API). */
    public @NotNull String raw(@NotNull String path, String... replacements) {
        return resolve(path, replacements);
    }

    /**
     * Человекочитаемая длительность (ключи {@code time_minutes_seconds} и
     * {@code time_seconds}). Нужна, чтобы «5 минут» не было зашито в код.
     */
    public @NotNull String duration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0) {
            return resolve("time_minutes_seconds",
                    "%minutes%", Long.toString(minutes),
                    "%seconds%", Long.toString(seconds));
        }
        return resolve("time_seconds", "%seconds%", Long.toString(seconds));
    }

    /** Строка с {@code §}-кодами — для API, принимающих legacy-текст. */
    public @NotNull String legacy(@NotNull String path, String... replacements) {
        return Colors.toLegacy(resolve(path, replacements));
    }

    public boolean has(@NotNull String path) {
        return lang.contains(path);
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private static boolean hasReplacements(String... replacements) {
        return replacements != null && replacements.length > 0;
    }

    private String resolve(String path, String... replacements) {
        String message = lang.getString(path);
        if (message == null) {
            warnMissing(path);
            return "";
        }
        if (message.isEmpty()) return "";
        return applyPrefix(applyReplacements(message, replacements));
    }

    private String applyPrefix(String text) {
        if (text == null || text.isEmpty()) return "";
        String prefix = lang.getString("prefix", "");
        return prefix == null ? text : text.replace("%prefix%", prefix);
    }

    private String applyReplacements(String text, String... replacements) {
        if (text == null || text.isEmpty()) return "";
        if (!hasReplacements(replacements)) return text;

        String result = text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String placeholder = replacements[i];
            if (placeholder == null || placeholder.isEmpty()) continue;
            Object value = replacements[i + 1];
            result = result.replace(placeholder, value == null ? "" : value.toString());
        }
        return result;
    }

    private void warnMissing(String path) {
        if (warnedMissing.add(path)) {
            plugin.getLogger().warning("Языковой ключ '" + path + "' не найден — сообщение не будет показано.");
        }
    }

    private Optional<YamlConfiguration> loadBundledFallback() {
        return loadBundled(FALLBACK);
    }

    private Optional<YamlConfiguration> loadBundled(String language) {
        try (InputStream in = plugin.getResource("lang/" + language + ".yml")) {
            if (in == null) return Optional.empty();
            return Optional.of(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось прочитать встроенный язык " + language, ex);
            return Optional.empty();
        }
    }

    private void saveBundled(String language) {
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (file.isFile()) return;
        try {
            plugin.saveResource("lang/" + language + ".yml", false);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Встроенный языковой файл " + language + ".yml отсутствует в jar.", ex);
        }
    }
}
