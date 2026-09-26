package ru.qweyns.qwetnts.cannon;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;
import ru.qweyns.qwetnts.dynamite.DynamiteType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки Тнт-пушки — блока-раздатчика, который запускает динамит по
 * сигналу редстоуна (механика HolyWorld).
 *
 * <p>Объект неизменяемый: собирается {@link CannonLoader} при старте и по
 * {@code /qtnt reload}, дальше только читается.</p>
 *
 * <p>Пушка — <b>не динамит</b>: она не взрывается сама, а запускает снарядом
 * тот динамит, который в неё положили. Запущенный заряд сохраняет все свои
 * свойства: правила ломания, урон, рейд-блоки и тип взрыва для QPS.</p>
 */
public record CannonSettings(boolean enabled,
                             @NotNull String permission,
                             @NotNull DynamiteType.ItemSpec item,
                             @Nullable DynamiteType.Recipe recipe,
                             boolean craftable,
                             @NotNull Menu menu,
                             @NotNull Launch launch,
                             @NotNull Ammunition ammo,
                             @Nullable Settings.WorldFilter worlds,
                             @NotNull DynamiteEffect shot) {

    /** Особый id боеприпаса: ванильный TNT, без PDC-метки плагина. */
    public static final String VANILLA_TNT = "tnt";

    /** Выключенная пушка: используется, если файла нет или {@code enabled: false}. */
    public static final CannonSettings DISABLED = new CannonSettings(
            false,
            "qwetnts.cannon",
            new DynamiteType.ItemSpec(Material.DISPENSER, "&cТнт-Пушка", List.of(), true, 0, null, false),
            null,
            false,
            Menu.DEFAULT,
            Launch.DEFAULT,
            Ammunition.DEFAULT,
            null,
            DynamiteEffect.NONE);

    /** Задан ли свой фильтр миров ({@code null} — берётся глобальный). */
    public boolean hasOwnWorlds() {
        return worlds != null;
    }

    /**
     * Меню пушки: 3×3, по краям — декоративные панели, которые нельзя взять.
     *
     * @param title      заголовок окна
     * @param border     материал рамки (обычно {@code RED_STAINED_GLASS_PANE})
     * @param borderName имя панели (пробел — чтобы не показывалось название)
     */
    public record Menu(@NotNull String title,
                       @NotNull Material border,
                       @NotNull String borderName) {

        public static final Menu DEFAULT =
                new Menu("<#FB7185>Тнт-Пушка", Material.RED_STAINED_GLASS_PANE, " ");
    }

    /**
     * Параметры выстрела.
     *
     * @param speedBlocksPerSecond скорость снаряда, блоков в секунду
     *                             (на HolyWorld — 5)
     * @param maxRangeBlocks       предел дальности: {@code 0} — без лимита,
     *                             иначе фитиль укорачивается так, чтобы
     *                             снаряд взорвался не дальше этой дистанции
     * @param upward               вертикальная добавка к скорости
     * @param gravity              {@code false} — снаряд летит по прямой
     * @param delayTicks           задержка между сигналом редстоуна и выстрелом
     */
    public record Launch(double speedBlocksPerSecond,
                         double maxRangeBlocks,
                         double upward,
                         boolean gravity,
                         long delayTicks) {

        public static final Launch DEFAULT = new Launch(5.0, 20.0, 0.0, true, 4L);

        /** Скорость в блоках за тик (20 тиков = секунда). */
        public double blocksPerTick() {
            return Math.max(0.01, speedBlocksPerSecond) / 20.0;
        }

        /** Вертикальная добавка за тик. */
        public double upwardPerTick() {
            return upward / 20.0;
        }

        /**
         * Сколько тиков снаряд пролетит до предела дальности.
         *
         * @return тики или {@code 0}, если предел не задан
         */
        public int rangeFuseTicks() {
            if (maxRangeBlocks <= 0.0) return 0;
            return (int) Math.ceil(maxRangeBlocks / blocksPerTick());
        }
    }

    /**
     * Чем можно заряжать пушку.
     *
     * @param mode  {@code BLACKLIST} — можно всё, кроме списка;
     *              {@code WHITELIST} — только из списка
     * @param kinds id динамитов из {@code dynamites/} плюс особый
     *              {@link #VANILLA_TNT} для ванильного TNT
     */
    public record Ammunition(@NotNull Mode mode, @NotNull Set<String> kinds) {

        public static final Ammunition DEFAULT = new Ammunition(Mode.BLACKLIST, Set.of());

        public enum Mode {
            /** Запрещено только то, что в списке. */
            BLACKLIST,
            /** Разрешено только то, что в списке. */
            WHITELIST
        }

        /** Можно ли зарядить этим типом ( id приводится к нижнему регистру ). */
        public boolean allows(@Nullable String kind) {
            if (kind == null || kind.isBlank()) return false;
            boolean listed = kinds.contains(kind.trim().toLowerCase(Locale.ROOT));
            return mode == Mode.WHITELIST ? listed : !listed;
        }
    }
}
