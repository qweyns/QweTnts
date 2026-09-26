package ru.qweyns.qwetnts.hologram;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Способ нарисовать голограмму: встроенный или через сторонний плагин.
 *
 * <p>Ключ — {@link UUID} заряда, а не блока: голограмма живёт, пока горит
 * фитиль, и заряд за это время успевает улететь от точки поджога (снаряд
 * пушки). Поэтому update принимает новую позицию.</p>
 *
 * <p>Все реализации обязаны терпеть вызовы из любого потока: на Folia
 * сущности принадлежат региону, и работать с ними можно только из него.
 * Планирование — через {@code Schedulers}.</p>
 */
public interface HologramProvider {

    /** Короткое имя для логов: что именно рисует голограмму. */
    @NotNull String id();

    /** Доступен ли этот способ прямо сейчас (плагин установлен и включён). */
    boolean isAvailable();

    /** Показать голограмму. Если с таким id уже есть — переиспользовать. */
    void show(@NotNull UUID id,
              @NotNull Location at,
              @NotNull HologramSettings settings,
              @NotNull List<String> lines);

    /** Обновить текст и, если голограмма двигается, позицию. */
    void update(@NotNull UUID id, @NotNull Location at, @NotNull List<String> lines);

    /** Убрать голограмму. */
    void remove(@NotNull UUID id);

    /** Убрать все голограммы этого провайдера (выгрузка плагина, reload). */
    void removeAll();
}
