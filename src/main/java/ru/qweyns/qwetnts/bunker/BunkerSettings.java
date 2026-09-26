package ru.qweyns.qwetnts.bunker;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки бункера: стена, шансы, респавн, эффекты.
 *
 * <p>Механика HolyWorld Lite: под замком стоит комната со стеной из древних
 * обломков, а напротив — раздатчик. Игроки кладут в раздатчик динамит, он
 * выстреливает по стене, и у каждого типа динамита свой шанс снять одну
 * стадию: обычный 0,4 %, C4 — 5 %, Разрывная волна — 12,5 %. Стена
 * деградирует целиком: древние обломки → плачущий обсидиан → обсидиан →
 * дыра.</p>
 *
 * <h2>Что здесь не живёт</h2>
 * <p>Ни замок, ни шалкеры с лутом, ни награда за пробитую стену. Аддон
 * отвечает ровно за механику стены: остальное — мир и другие плагины.</p>
 */
public record BunkerSettings(boolean enabled,
                             @NotNull String world,
                             @NotNull Wall wall,
                             long cooldownMillis,
                             @NotNull Map<String, Double> chances,
                             double defaultChance,
                             @NotNull Respawn respawn,
                             boolean announce,
                             @NotNull Effects effects,
                             @NotNull Reward reward) {

    public static final BunkerSettings DISABLED = new BunkerSettings(
            false, "", Wall.NONE, 0L, Map.of(), 0.0, Respawn.NONE, true,
            Effects.NONE, Reward.NONE);

    /** Бункер включён и взрыв произошёл в его мире. */
    public boolean isActiveIn(@Nullable World eventWorld) {
        return enabled && eventWorld != null && eventWorld.getName().equals(world);
    }

    /**
     * Шанс пробить стену для типа динамита, в процентах.
     *
     * <p>Ключ — строковый id динамита ({@code c4}, {@code shockwave}) или
     * {@code tnt} для ванильного заряда без метки.</p>
     */
    public double chanceFor(@NotNull String key) {
        Double chance = chances.get(key.toLowerCase(Locale.ROOT));
        return chance == null ? defaultChance : chance;
    }

    /** Индекс последней стадии: до него стена цела, на нём — пробита. */
    public int lastStageIndex() {
        return Math.max(0, wall.stages().size() - 1);
    }

    /**
     * Прямоугольник стены.
     *
     * @param stages    материалы стадий по порядку, последний — {@code AIR}
     *                  (это и есть пробитие)
     * @param hitMargin на сколько блоков вокруг стены считать попадание
     * @param protect   вычищать ли блоки стены из списков разрушения
     */
    public record Wall(int minX, int minY, int minZ,
                       int maxX, int maxY, int maxZ,
                       @NotNull List<Material> stages,
                       int hitMargin,
                       boolean protect) {

        public static final Wall NONE =
                new Wall(0, 0, 0, -1, -1, -1, List.of(), 3, true);

        public boolean isDefined() {
            return !stages.isEmpty() && maxX >= minX && maxY >= minY && maxZ >= minZ;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        /** Эпицентр взрыва рядом со стеной (с запасом {@code margin} блоков). */
        public boolean near(int x, int y, int z, int margin) {
            int m = Math.max(0, margin);
            return x >= minX - m && x <= maxX + m
                    && y >= minY - m && y <= maxY + m
                    && z >= minZ - m && z <= maxZ + m;
        }

        public @Nullable Material stageMaterial(int index) {
            if (index < 0 || index >= stages.size()) return null;
            return stages.get(index);
        }

        /**
         * Материал одной из стадий — по нему стена защищается от взрывов.
         *
         * <p>{@code AIR} не считается: после пробития проход становится
         * обычным пространством, и взрывы там больше не гасятся.</p>
         */
        public boolean isStageMaterial(@Nullable Material material) {
            return material != null && material != Material.AIR && stages.contains(material);
        }

        /** Середина стены по вертикали — точка для планирования задач. */
        public int centerY() {
            return (minY + maxY) / 2;
        }

        public @NotNull Location center(@NotNull World world) {
            return new Location(world,
                    (minX + maxX) / 2.0,
                    (minY + maxY) / 2.0,
                    (minZ + maxZ) / 2.0);
        }
    }

    /**
     * Возврат стены в исходное состояние.
     *
     * @param randomStage как на HolyWorld: при появлении ивента стадия
     *                    стены случайна — слабая, средняя или прочная
     */
    public record Respawn(boolean enabled,
                          long intervalMinutes,
                          boolean randomStage) {

        public static final Respawn NONE = new Respawn(false, 60L, true);
    }

    /**
     * Награда за пробитую стену.
     *
     * <p>Аддон ничего не спавнит сам: он выполняет команды от имени консоли,
     * а шалкер с добычей ставит другой плагин — тот, который на сервере
     * отвечает за ивенты. Так награда остаётся в ведении того, кто её
     * настраивает, и не тянет за собой зависимость от чужого формата.</p>
     *
     * <p>Подстановки в командах: {@code %player%} — кто пробил стену,
     * {@code %world%}, {@code %x%}, {@code %y%}, {@code %z%} — центр стены.</p>
     */
    public record Reward(@NotNull List<String> commands) {

        public static final Reward NONE = new Reward(List.of());

        public boolean isEmpty() {
            return commands.isEmpty();
        }

        /** Подставить плейсхолдеры в одну команду. */
        public @NotNull String apply(@NotNull String command,
                                     @NotNull String player,
                                     @NotNull String world,
                                     int x, int y, int z) {
            return command
                    .replace("%player%", player)
                    .replace("%world%", world)
                    .replace("%x%", Integer.toString(x))
                    .replace("%y%", Integer.toString(y))
                    .replace("%z%", Integer.toString(z));
        }
    }

    /** Эффекты деградации и пробития. */
    public record Effects(@NotNull DynamiteEffect onDegrade,
                          @NotNull DynamiteEffect onBreach) {

        public static final Effects NONE =
                new Effects(DynamiteEffect.NONE, DynamiteEffect.NONE);
    }
}
