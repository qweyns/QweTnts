package ru.qweyns.qwetnts.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.qweyns.qweprotectstones.regions.event.RegionDamageEvent;
import org.qweyns.qweprotectstones.regions.event.RegionDeletedEvent;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Журнал уничтоженных рейдом приватов: только самое важное, чтобы админ мог
 * разобрать спорную ситуацию. Плюс алерт в Discord.
 *
 * <p>Формат строки — из lang-файла (ключ {@code log.region_destroyed}).</p>
 *
 * <h2>Почему {@link RegionDeletedEvent}, а не {@code RegionDeleteEvent}</h2>
 * <p>{@code RegionDeleteEvent} <b>отменяемый</b>: любой плагин может запретить
 * удаление, и тогда запись в журнале была бы ложной. {@link RegionDeletedEvent}
 * приходит уже после того, как приват снят с учёта и удалён из базы, — он не
 * отменяется и гарантирует, что уничтожение действительно состоялось.</p>
 *
 * <h2>Откуда берётся имя атакующего</h2>
 * <p>Конвейер QPS сначала удаляет приват и только потом вызывает
 * {@code region.recordAttack(...)}, поэтому на моменте удаления
 * {@code getLastAttackerName()} ещё пуст. Имя ловим заранее — в
 * {@link RegionDamageEvent}, где оно передано явно, — и запоминаем на
 * короткое время.</p>
 */
public final class RaidLoggingListener implements Listener {

    /** Чтобы карта не росла бесконечно, если приват так и не уничтожили. */
    private static final int MAX_TRACKED = 512;

    private final QweTnts plugin;
    private final Map<UUID, String> lastAttacker = new ConcurrentHashMap<>();

    public RaidLoggingListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Запоминаем, кто последним бил приват: при удалении это имя ещё не записано. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NotNull RegionDamageEvent event) {
        String attacker = event.getAttackerName();
        if (attacker == null || attacker.isBlank()) return;
        if (event.getRegion() == null) return;

        if (lastAttacker.size() >= MAX_TRACKED) lastAttacker.clear();
        lastAttacker.put(event.getRegion().getId(), attacker.trim());

        alertOwner(event, attacker.trim());
    }

    /**
     * Сообщаем владельцу, что его приват атакуют.
     *
     * <p>Раньше этой фичи не было вовсе: класс {@code AlertThrottle} и ключ
     * {@code alerts.attack-alert-cooldown-millis} существовали, но
     * {@code tryAcquire()} не вызывал никто (дефект 10.3.3 аудита). Теперь
     * сообщение уходит владельцу, если он в сети, не чаще одного раза за
     * кулдаун на каждый приват: при залпе из десятка зарядов чат не забивается.</p>
     */
    private void alertOwner(@NotNull RegionDamageEvent event, @NotNull String attacker) {
        var region = event.getRegion();
        if (region == null) return;

        UUID ownerId = region.getOwnerId();
        if (ownerId == null) return;

        if (!plugin.alerts().tryAcquire(region.getId())) return;

        String explosionType = event.getExplosionType();
        DynamiteType type = explosionType == null
                ? null
                : plugin.registry().byExplosionType(explosionType);
        String charge = type != null
                ? type.displayName()
                : (explosionType == null || explosionType.isBlank() ? "?" : explosionType);

        String[] replacements = {
                "%id%", String.valueOf(region.getShortId()),
                "%type%", charge,
                "%attacker%", attacker
        };

        // Игроку — только с глобального потока: событие QPS приходит из
        // потока региона, а обращаться к чужому игроку оттуда нельзя.
        Schedulers.runGlobal(plugin, () -> {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) return;
            plugin.lang().send(owner, LangKeys.ALERT_REGION_ATTACKED, replacements);
        });

        if (plugin.discord() != null
                && plugin.settings().alerts().discord()
                        .wants(Settings.Discord.DiscordEvent.REGION_UNDER_ATTACK)) {
            plugin.discord().send(Settings.Discord.DiscordEvent.REGION_UNDER_ATTACK,
                    plugin.lang().raw(LangKeys.DISCORD_REGION_UNDER_ATTACK, replacements));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeleted(@NotNull RegionDeletedEvent event) {
        if (event.getReason() != org.qweyns.qweprotectstones.regions.event.RegionDeleteEvent.Reason.DESTROYED_BY_RAID) {
            return;
        }

        var region = event.getRegion();
        if (region == null) return;

        String attacker = null;
        if (region.getId() != null) {
            attacker = lastAttacker.remove(region.getId());
        }
        if (attacker == null || attacker.isBlank()) {
            attacker = region.getLastAttackerName();
        }
        if (attacker == null || attacker.isBlank()) {
            attacker = plugin.lang().raw(LangKeys.UNKNOWN_OWNER);
        }

        String[] replacements = {
                "%id%", String.valueOf(region.getShortId()),
                "%type%", String.valueOf(region.getTypeId()),
                "%owner%", String.valueOf(region.getOwnerName()),
                "%attacker%", attacker
        };

        plugin.getLogger().log(Level.INFO,
                plugin.lang().raw(LangKeys.LOG_REGION_DESTROYED, replacements));

        // Фаза 2: алерт в Discord (асинхронно, ошибок сети не боимся).
        if (plugin.discord() != null
                && plugin.settings().alerts().discord()
                        .wants(Settings.Discord.DiscordEvent.REGION_DESTROYED)) {
            plugin.discord().send(Settings.Discord.DiscordEvent.REGION_DESTROYED,
                    plugin.lang().raw(LangKeys.DISCORD_REGION_DESTROYED, replacements));
        }

        // UUID атакующего в API QPS не публикуется — ограничиваемся именем.
        plugin.stats().recordRegionDestroyed(null);
    }
}
