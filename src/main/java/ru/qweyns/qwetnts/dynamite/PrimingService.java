package ru.qweyns.qwetnts.dynamite;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Effects;

import java.util.logging.Level;

/**
 * Единственная точка, где создаётся зажжённый динамит {@link TNTPrimed}.
 *
 * <p>Здесь же навешивается PDC-метка типа, мощность, фитиль и источник
 * (нужен QPS, чтобы засчитать атаку привата на поджигавшего).</p>
 */
public final class PrimingService {

    private final QweTnts plugin;

    public PrimingService(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /**
     * Поджечь блок с установленным динамитом.
     *
     * @return зажжённый заряд или {@code null}, если поджечь не удалось
     */
    public @Nullable TNTPrimed ignite(@NotNull Block block,
                                      @NotNull DynamiteType type,
                                      @Nullable Player igniter) {
        if (block.getType() == Material.AIR) return null;

        Location center = block.getLocation().add(0.5, 0.0, 0.5);
        World world = block.getWorld();
        if (world == null) return null;

        // Снимаем запись ДО спавна: повторный вызов (например, из двух
        // обработчиков) уже ничего не найдёт и не создаст дубль.
        plugin.placedDynamites().remove(block);

        TNTPrimed primed = spawn(world, center, type, igniter);
        if (primed == null) return null;

        block.setType(Material.AIR, false);
        Effects.play(plugin, type.effects().ignite(), center);
        return primed;
    }

    /**
     * Поджечь динамит «из рук» (режим {@code auto-ignite: true}):
     * заряд появляется перед игроком.
     */
    public @Nullable TNTPrimed igniteFromHand(@NotNull Location location,
                                              @NotNull DynamiteType type,
                                              @Nullable Player igniter) {
        World world = location.getWorld();
        if (world == null) return null;

        TNTPrimed primed = spawn(world, location, type, igniter);
        if (primed != null) {
            Effects.play(plugin, type.effects().ignite(), location);
        }
        return primed;
    }

    // ------------------------------------------------------------------
    // Запуск снаряда (Тнт-пушка)
    // ------------------------------------------------------------------

    /**
     * Запускает заряженный динамит как снаряд.
     *
     * <p>Снаряд — тот же {@link TNTPrimed} с той же PDC-меткой, что и при
     * обычном поджоге, поэтому он сохраняет все свойства своего типа: правила
     * ломания блоков, урон, рейд-блоки и тип взрыва для QPS.</p>
     *
     * @param type    тип динамита; {@code null} — ванильный TNT
     * @param fuseTicks сколько тиков до взрыва
     * @param gravity   {@code false} — снаряд летит по прямой
     */
    public @Nullable TNTPrimed launch(@NotNull Location origin,
                                      @Nullable DynamiteType type,
                                      @Nullable Player source,
                                      @NotNull Vector velocity,
                                      int fuseTicks,
                                      boolean gravity) {
        World world = origin.getWorld();
        if (world == null) return null;

        TNTPrimed primed = spawn(world, origin, type, source);
        if (primed == null) return null;

        primed.setFuseTicks(Math.max(1, fuseTicks));
        primed.setGravity(gravity);
        primed.setVelocity(velocity);
        return primed;
    }

    /**
     * Создаёт зажжённый заряд.
     *
     * @param type тип динамита; {@code null} — ванильный TNT (только фитиль,
     *             мощность и поджог задаёт вызывающий код)
     */
    private @Nullable TNTPrimed spawn(@NotNull World world,
                                      @NotNull Location location,
                                      @Nullable DynamiteType type,
                                      @Nullable Player igniter) {
        try {
            TNTPrimed primed = world.spawn(location, TNTPrimed.class);
            if (primed == null || !primed.isValid()) return null;

            if (type != null) {
                primed.setFuseTicks(type.rollFuseTicks(BlastMath.random()));
                primed.setYield(type.explosion().power());
                primed.setIsIncendiary(type.explosion().fire());
                primed.getPersistentDataContainer().set(
                        plugin.keys().dynamiteKind,
                        PersistentDataType.STRING,
                        type.id());
            }

            // Запоминаем чанк рождения: квоту анти-лага надо снимать именно
            // там, где заряд появился, а не там, где он взорвётся. Координаты
            // считаем по блоку — без getChunk(), чтобы не дёргать загрузку
            // чанка раньше времени и не трогать чужой регион на Folia.
            var data = primed.getPersistentDataContainer();
            data.set(plugin.keys().originChunkX, PersistentDataType.INTEGER,
                    location.getBlockX() >> 4);
            data.set(plugin.keys().originChunkZ, PersistentDataType.INTEGER,
                    location.getBlockZ() >> 4);

            if (igniter != null && igniter.isOnline()) {
                primed.setSource(igniter);
            }

            // Звук поджога не играем здесь: он задаётся в effects.on-ignite
            // файла динамита, иначе настройка молча перебивалась бы
            // ванильным звуком независимо от конфига.
            return primed;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось создать зажжённый динамит "
                            + (type == null ? "TNT" : type.id()), ex);
            return null;
        }
    }
}
