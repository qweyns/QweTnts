package ru.qweyns.qwetnts.dynamite;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

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
        return spawn(world, location, type, igniter);
    }

    private @Nullable TNTPrimed spawn(@NotNull World world,
                                      @NotNull Location location,
                                      @NotNull DynamiteType type,
                                      @Nullable Player igniter) {
        try {
            TNTPrimed primed = world.spawn(location, TNTPrimed.class);
            if (primed == null || !primed.isValid()) return null;

            primed.setFuseTicks(type.fuseTicks());
            primed.setYield(type.power());
            primed.setIsIncendiary(false);
            if (igniter != null && igniter.isOnline()) {
                primed.setSource(igniter);
            }
            primed.getPersistentDataContainer().set(
                    plugin.keys().dynamiteKind,
                    PersistentDataType.STRING,
                    type.id());

            world.playSound(location, Sound.ENTITY_TNT_PRIMED,
                    SoundCategory.BLOCKS, 1.0f, 1.0f);
            return primed;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось создать зажжённый динамит " + type.id(), ex);
            return null;
        }
    }
}
