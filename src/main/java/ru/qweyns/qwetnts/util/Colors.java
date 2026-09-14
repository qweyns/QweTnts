package ru.qweyns.qwetnts.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Форматирование строк: MiniMessage + legacy-коды {@code &c/§c/&#RRGGBB}.
 *
 * <p>Полный аналог {@code ColorUtil} из QweProtectStones: те же теги, те же
 * правила, чтобы оформление сообщений обоих плагинов было одинаковым.</p>
 */
public final class Colors {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final Pattern LEGACY_PATTERN =
            Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])|&#([0-9a-fA-F]{6})");

    private Colors() {
    }

    /** Разобрать строку конфига в {@link Component} (MiniMessage + legacy-коды). */
    public static @NotNull Component format(@Nullable String text) {
        if (text == null || text.isBlank()) return Component.empty();

        String preProcessed = LEGACY_PATTERN.matcher(text).replaceAll(match -> {
            if (match.group(2) != null) return "<#" + match.group(2) + ">";
            String tag = legacyToTag(Character.toLowerCase(match.group(1).charAt(0)));
            // $ и \ — спецсимволы в строке замены, поэтому quoteReplacement
            return Matcher.quoteReplacement(tag != null ? tag : match.group());
        });

        return MINI_MESSAGE.deserialize(preProcessed);
    }

    /** То же, но без курсива — для названий и лора предметов. */
    public static @NotNull Component formatItem(@Nullable String text) {
        return format(text).decoration(TextDecoration.ITALIC, false);
    }

    /** Убрать всю разметку — для логов и сравнений. */
    public static @NotNull String strip(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return MINI_MESSAGE.stripTags(text).replaceAll("\u00a7.", "");
    }

    /**
     * Экранировать MiniMessage-разметку в чужом тексте (ник игрока, название
     * мира), чтобы {@code <red>} из ника не сломал сообщение.
     */
    public static @Nullable String escapeMini(@Nullable String text) {
        if (text == null) return null;
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** В legacy-строку с {@code §} — для сторонних API, принимающих строки. */
    public static @NotNull String toLegacy(@Nullable String text) {
        if (text == null || text.isBlank()) return "";
        return SECTION_SERIALIZER.serialize(format(text));
    }

    private static @Nullable String legacyToTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'k' -> "<obfuscated>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }
}
