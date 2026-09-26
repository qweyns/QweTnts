package ru.qweyns.qwetnts.integration;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qweyns.qweprotectstones.api.QpsApi;
import org.qweyns.qweprotectstones.config.Tunables;
import org.qweyns.qweprotectstones.regions.Region;
import org.qweyns.qweprotectstones.regions.RegionFlag;
import ru.qweyns.qwetnts.QweTnts;

import java.util.logging.Level;

/**
 * Тонкая прослойка над API QweProtectStones.
 *
 * <p>Здесь сосредоточены две вещи, которые обязательны для аддона:</p>
 * <ol>
 *   <li><b>null-безопасность:</b> QPS мог быть выгружен/перезагружен — любой
 *       вызов обёрнут, отсутствие API трактуется как «приватов нет»;</li>
 *   <li><b>правила §5.2 ТЗ:</b> ядро привата не разрушаемо никогда, а всё
 *       остальное — только при флаге {@code EXPLOSION_DAMAGE}.</li>
 * </ol>
 */
public final class QpsBridge {

    private final QweTnts plugin;

    public QpsBridge(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Живой API или {@code null}, если QPS недоступен. */
    private @Nullable QpsApi api() {
        if (!QpsApi.isAvailable()) return null;
        try {
            return QpsApi.get();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "QPS API недоступен", ex);
            return null;
        }
    }

    public boolean isAvailable() {
        return QpsApi.isAvailable();
    }

    /**
     * Можно ли взрывом разрушить блок (§5.2 ТЗ).
     *
     * <p>Вне приватов — всегда можно. Внутри: ядро — никогда, остальное
     * только при включённом флаге {@code EXPLOSION_DAMAGE}.</p>
     */
    public boolean canExplode(@Nullable Block block) {
        if (block == null) return false;

        QpsApi api = api();
        if (api == null) return true;

        Location location = block.getLocation();
        try {
            Region region = api.getRegionAt(location);
            if (region == null) return true;
            if (region.isCore(location)) return false;
            return api.flagAt(location, RegionFlag.EXPLOSION_DAMAGE);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Ошибка проверки привата при взрыве — блок оставлен", ex);
            // Любая ошибка интеграции не должна ломать блоки игроков.
            return false;
        }
    }

    /**
     * Точка внутри чьего-то привата?
     *
     * <p>Нужно механике Динамита Б2 из HolyWorld Lite: он «не работает на
     * всех стандартных заприваченных территориях». Если API недоступен,
     * считаем, что приватов нет — иначе заряд перестал бы работать везде.</p>
     */
    public boolean insideRegion(@Nullable Location location) {
        if (location == null) return false;

        QpsApi api = api();
        if (api == null) return false;

        try {
            return api.getRegionAt(location) != null;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Ошибка проверки привата — точка считается свободной", ex);
            return false;
        }
    }

    /**
     * Можно ли игроку ставить динамит в эту точку.
     *
     * <p>Проверку делаем самим QPS — действием {@link Tunables.TrustAction#BUILD}.
     * Тогда работают и роли из {@code roles.yml}, и бан, и публичный доступ,
     * и порог {@code trust.required.build}: «вписан в приват» само по себе
     * права ставить взрывчатку не даёт.</p>
     *
     * <p>Если API недоступен — разрешаем: плагин не должен становиться
     * единственной защитой сервера.</p>
     */
    public boolean canPlace(@NotNull Player player, @Nullable Location location) {
        if (location == null) return false;
        if (player.hasPermission("qwetnts.bypass.region")) return true;

        QpsApi api = api();
        if (api == null) return true;

        try {
            Region region = api.getRegionAt(location);
            if (region == null) return true;            // вне приватов — можно
            if (region.isCore(location)) return false;  // ядро не трогаем
            return api.isTrusted(player, location, Tunables.TrustAction.BUILD);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка проверки привата при установке", ex);
            return true;
        }
    }
}
