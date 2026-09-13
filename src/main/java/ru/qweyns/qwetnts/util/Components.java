package ru.qweyns.qwetnts.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

/** Вспомогательные методы для работы с Adventure Components. */
public final class Components {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Components() {}

    /** Распарсить MiniMessage-строку. */
    public static @NotNull Component mm(@NotNull String mini) {
        return MM.deserialize(mini);
    }

    /** Распарсить строку с форматированием String.format. */
    public static @NotNull Component mmf(@NotNull String mini, Object... args) {
        return MM.deserialize(String.format(mini, args));
    }
}
