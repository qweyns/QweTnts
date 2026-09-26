package ru.qweyns.qwetnts.hologram;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Строки голограммы: подстановка плейсхолдеров.
 *
 * <p>Отдельный класс, а не код внутри менеджера, затем же, чем
 * {@code HologramText} в QweProtectStones: подстановку можно проверить
 * тестом без сервера. Для текста голограммы это особенно ценно — увидеть
 * её глазами можно только на живом сервере.</p>
 */
public final class HologramText {

    /** Секунды до взрыва. */
    public static final String SECONDS = "%seconds%";
    /** Название динамита. */
    public static final String NAME = "%name%";
    /** Игрок, поджёгший заряд (или прочерк). */
    public static final String PLAYER = "%player%";
    /** Сколько тиков фитиля осталось. */
    public static final String TICKS = "%ticks%";

    private static final String DASH = "\u2014";

    private HologramText() {
    }

    /**
     * Подставить значения в строки конфига.
     *
     * @param lines    строки из {@code holograms.lines} (или из файла динамита)
     * @param seconds  сколько секунд осталось до взрыва
     * @param ticks    сколько тиков фитиля осталось
     * @param name     название динамита
     * @param player   кто поджёг; {@code null} — подставится прочерк
     * @return строки с готовой MiniMessage-разметкой (ещё не распарсенной:
     *         провайдеры ждут {@code List<String>})
     */
    public static @NotNull List<String> render(@NotNull List<String> lines,
                                               int seconds,
                                               int ticks,
                                               @NotNull String name,
                                               @Nullable String player) {
        String shownPlayer = player == null || player.isBlank() ? DASH : player;
        List<String> out = new ArrayList<>(lines.size());

        for (String line : lines) {
            if (line == null) continue;
            out.add(line
                    .replace(SECONDS, Integer.toString(Math.max(0, seconds)))
                    .replace(TICKS, Integer.toString(Math.max(0, ticks)))
                    .replace(NAME, name)
                    .replace(PLAYER, shownPlayer));
        }
        return out;
    }
}
