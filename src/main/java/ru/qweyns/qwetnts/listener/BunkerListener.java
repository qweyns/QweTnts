package ru.qweyns.qwetnts.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;

import java.util.Iterator;

/**
 * Стена бункера и взрывы.
 *
 * <h2>Зачем отдельный слушатель</h2>
 * <p>{@link DynamiteExplodeListener} решает судьбу блоков по правилам
 * динамита — там стена просто пропускается. Здесь — вторая половина
 * работы, и она не про динамиты:</p>
 * <ol>
 *   <li><b>Вычистить стену из любого списка разрушения.</b> Взрыв может
 *       быть не нашим: ванильный TNT, другой плагин, крипер с усилением.
 *       Обычный обсидиан vanilla не даст в {@code blockList}, но чужой
 *       плагин — запросто. Поэтому убираем блоки стены до всех остальных
 *       (приоритет {@code LOWEST}).</li>
 *   <li><b>Засчитать выстрел.</b> Только снаряд с меткой
 *       {@code qwetnts:cannon-shot}, только рядом со стеной, и не чаще
 *       {@code cooldown-millis}.</li>
 * </ol>
 */
public final class BunkerListener implements Listener {

    private final QweTnts plugin;

    public BunkerListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplode(@NotNull EntityExplodeEvent event) {
        if (!plugin.bunker().isEnabled()) return;

        Location center = event.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        if (plugin.bunker().protectsIn(world)) {
            stripWall(world, event);
        }

        // Выстрел засчитываем и при выключённой защите: администратор может
        // захотеть оставить стену уязвимой, но шанс всё равно должен крутиться.
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;

        String kind = tnt.getPersistentDataContainer()
                .get(plugin.keys().dynamiteKind, PersistentDataType.STRING);
        DynamiteType type = kind == null || kind.isBlank()
                ? null
                : plugin.registry().byId(kind);

        plugin.bunker().onShot(tnt, center, type, shooterOf(tnt));
    }

    /**
     * Кто стрелял: имя пишет {@code CannonService} в момент выстрела.
     *
     * <p>Нужно только для награды, поэтому отсутствие метки — не ошибка,
     * а «стрелявшего не знаем».</p>
     */
    private @Nullable String shooterOf(@NotNull TNTPrimed tnt) {
        String name = tnt.getPersistentDataContainer()
                .get(plugin.keys().cannonShooter, PersistentDataType.STRING);
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * Убирает блоки стены из {@code blockList} — значит, сервер их не тронет.
     *
     * <p>Идём итератором, а не {@code removeIf}: список от vanilla — это
     * живая коллекция события, и пересборка каждого взрыва в новый список
     * стоила бы дороже, чем выборочное удаление.</p>
     */
    private void stripWall(@NotNull World world, @NotNull EntityExplodeEvent event) {
        Iterator<Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            if (plugin.bunker().isWallIn(world, iterator.next())) {
                iterator.remove();
            }
        }
    }
}
