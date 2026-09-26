package ru.qweyns.qwetnts.listener;

import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qweyns.qweprotectstones.regions.event.RegionDamageEvent;
import org.qweyns.qweprotectstones.regions.event.RegionExplosionTypeEvent;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;

/**
 * Интеграция с QPS: классификация взрыва (§3.1 ТЗ) и корректировка урона
 * осаде (§3.2 ТЗ).
 *
 * <p>{@link RegionExplosionTypeEvent} не отменяется (это «вопрос» к аддонам),
 * а вот {@link RegionDamageEvent} — отменяется, поэтому его слушаем с
 * {@code ignoreCancelled = true}.</p>
 */
public final class RegionDynamiteListener implements Listener {

    private final QweTnts plugin;

    public RegionDynamiteListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Подменяем строковый тип взрыва и множитель радиуса на свои из конфига. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onClassify(@NotNull RegionExplosionTypeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        DynamiteType type = typeOf(tnt);
        if (type == null) return;

        event.setExplosionType(type.explosion().type());
        event.setDamageRadiusMultiplier(type.explosion().radiusMultiplier());
        // ВАЖНО: статистику взрывов здесь НЕ считаем. Это событие — побочный
        // эффект конвейера QPS, а сам взрыв учитывается один раз в
        // DynamiteExplodeListener (EntityExplodeEvent). Иначе каждый взрыв
        // попадал бы в bStats дважды.
    }

    /** Сколько прочности снимает именно этот динамит. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegionDamage(@NotNull RegionDamageEvent event) {
        String explosionType = event.getExplosionType();
        if (explosionType == null || explosionType.isBlank()) return;

        DynamiteType type = plugin.registry().byExplosionType(explosionType);
        if (type == null) return;

        event.setDamage(type.explosion().siegeDamage());

        if (event.getRegion() != null) {
            plugin.getLogger().fine(() -> "Приват " + event.getRegion().getShortId()
                    + " атакован динамитом " + type.id()
                    + " (урон " + type.explosion().siegeDamage() + ")");
        }
    }

    private @Nullable DynamiteType typeOf(@NotNull TNTPrimed tnt) {
        String kind = tnt.getPersistentDataContainer()
                .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
        if (kind == null || kind.isBlank()) return null;
        return plugin.registry().byId(kind);
    }
}
