package ru.qweyns.qwetnts.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Простая i18n: языковые файлы {@code lang/<code>.yml} в папке плагина.
 * Значения — MiniMessage-строки с плейсхолдерами вида {@code {0}, {1}, ...}.
 */
public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final QweTnts plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String code;

    public Lang(QweTnts plugin) {
        this.plugin = plugin;
    }

    public void load(@NotNull String code) {
        this.code = code;
        messages.clear();
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        saveDefault(code);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) {
                messages.put(key, yaml.getString(key, ""));
            }
        }
        // Если какого-то ключа нет в выбранном языке — фоллбек на ru.
        if (!"ru".equals(code)) {
            File ruFile = new File(plugin.getDataFolder(), "lang/ru.yml");
            saveDefault("ru");
            YamlConfiguration ru = YamlConfiguration.loadConfiguration(ruFile);
            for (String k : ru.getKeys(true)) {
                messages.putIfAbsent(k, ru.getString(k, ""));
            }
        }
    }

    private void saveDefault(String code) {
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (file.exists()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку lang/");
            return;
        }
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return;
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Io.writeAtomic(file.toPath(), content, plugin.getLogger());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить lang/" + code + ".yml", ex);
        }
    }

    /** Получить сырой шаблон сообщения. */
    public @NotNull String raw(@NotNull String key) {
        return messages.getOrDefault(key, key);
    }

    /** Отрендерить MiniMessage-шаблон с подстановкой позиционных {0}, {1}, ... */
    public @NotNull Component get(@NotNull String key, Object... args) {
        String tmpl = raw(key);
        for (int i = 0; i < args.length; i++) {
            tmpl = tmpl.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return MM.deserialize(tmpl);
    }

    /** Отправить сообщение с префиксом плагина. */
    public void send(@NotNull CommandSender to, @NotNull String key, Object... args) {
        to.sendMessage(get("prefix").append(get(key, args)));
    }
}
