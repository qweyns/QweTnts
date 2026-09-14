package ru.qweyns.qwetnts.integration;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.qweyns.qweprotectstones.api.QpsApi;
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
    public @Nullable QpsApi api() {
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

    /** Можно ли менять блок после взрыва (деградация древних обломков). */
    public boolean canModify(@Nullable Block block) {
        return canExplode(block);
    }

    /** Ядро ли это (даже не пытаемся ничего с ним делать). */
    public boolean isCore(@Nullable Block block) {
        if (block == null) return false;
        QpsApi api = api();
        if (api == null) return false;

        try {
            Region region = api.getRegionAt(block.getLocation());
            return region != null && region.isCore(block.getLocation());
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка проверки ядра привата", ex);
            return false;
        }
    }

    /**
     * Можно ли игроку ставить динамит в эту точку.
     *
     * <p>Флага BUILD в QPS нет: ориентируемся на владельца и уровень доверия.
     * Если API недоступен — разрешаем (плагин не должен становиться
     * единственной защитой).</p>
     */
    public boolean canPlace(@NotNull Player player, @Nullable Location location) {
        if (location == null) return false;
        if (player.hasPermission("qwetnts.bypass.region")) return true;

        QpsApi api = api();
        if (api == null) return true;

        try {
            Region region = api.getRegionAt(location);
            if (region == null) return true;
            if (region.isCore(location)) return false;
            if (region.isOwner(player.getUniqueId())) return true;
            return region.getTrust(player.getUniqueId()) != null;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка проверки привата при установке", ex);
            return true;
        }
    }
}
