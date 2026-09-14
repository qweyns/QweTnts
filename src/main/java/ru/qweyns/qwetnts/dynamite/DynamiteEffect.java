package ru.qweyns.qwetnts.dynamite;

import org.bukkit.SoundCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Звук + частицы для одного события динамита (установка, поджог, взрыв).
 *
 * <p>Все поля необязательные: пустая строка или нулевое количество
 * означает «эффект выключен».</p>
 */
public record DynamiteEffect(@Nullable String sound,
                             @NotNull SoundCategory category,
                             float volume,
                             float pitch,
                             @Nullable String particle,
                             int particleCount,
                             double spread) {

    /** Пустой эффект: ничего не проигрывать. */
    public static final DynamiteEffect NONE =
            new DynamiteEffect(null, SoundCategory.BLOCKS, 1.0f, 1.0f, null, 0, 0.0);

    public static @NotNull DynamiteEffect of(@Nullable String sound,
                                             @Nullable String category,
                                             float volume,
                                             float pitch,
                                             @Nullable String particle,
                                             int particleCount,
                                             double spread) {
        return new DynamiteEffect(
                blankToNull(sound),
                ru.qweyns.qwetnts.util.Effects.category(category),
                volume,
                pitch,
                blankToNull(particle),
                Math.max(0, particleCount),
                Math.max(0.0, spread));
    }

    public boolean isEmpty() {
        return (sound() == null || sound().isBlank())
                && (particle() == null || particle().isBlank());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
