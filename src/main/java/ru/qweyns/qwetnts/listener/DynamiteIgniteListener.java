package ru.qweyns.qwetnts.listener;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.dynamite.DynamiteType.IgniteCause;
import ru.qweyns.qwetnts.dynamite.PlacedDynamiteManager;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Поджог установленного динамита.
 *
 * <p>Пока {@code ignition.auto} выключен, заряд лежит блоком TNT и ждёт огня.
 * Каким именно огнём его можно поджечь, решает список
 * {@code ignition.causes} в файле динамита: огниво, огненный заряд, огонь,
 * лава, молния, взрыв, редстоун, удар.</p>
 *
 * <p>Ванильный поджог всегда отменяется: блок удаляется и вместо него
 * создаётся {@link TNTPrimed} с PDC-меткой, мощностью и фитилём из конфига —
 * иначе взрыв был бы обычным TNT.</p>
 */
public final class DynamiteIgniteListener implements Listener {

    private final QweTnts plugin;

    public DynamiteIgniteListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    /** Поджог огнём, огнивом, лавой, молнией, распространением огня. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(@NotNull BlockIgniteEvent event) {
        Block block = event.getBlock();
        PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(block);
        if (placed == null) return;

        DynamiteType type = plugin.registry().byId(placed.typeId());
        if (type == null) {
            plugin.placedDynamites().remove(block);
            return;
        }

        // Способ поджога не разрешён — гасим vanilla-поджог, иначе заряд
        // превратился бы в обычный TNT с дефолтным фитилём и мощностью.
        if (!type.canBeIgnitedBy(map(event.getCause()))) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        if (player != null && !hasPermission(player, type)) {
            event.setCancelled(true);
            plugin.lang().send(player, LangKeys.NO_PERMISSION);
            return;
        }

        event.setCancelled(true);
        ignite(block, type, player);
    }

    /** Огниво / огненный заряд в руке и удар кулаком по заряду. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        PlacedDynamiteManager.Placed placed = plugin.placedDynamites().at(clicked);
        if (placed == null) return;

        DynamiteType type = plugin.registry().byId(placed.typeId());
        if (type == null) {
            plugin.placedDynamites().remove(clicked);
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        IgniteCause cause;
        if (action == Action.LEFT_CLICK_BLOCK) {
            // Удар кулаком поджигает заряд, только если это разрешено
            // глобально (settings.dynamites.punch-ignites). Иначе левый клик
            // — обычная попытка сломать блок, и мы её не перехватываем.
            if (!plugin.settings().dynamites().punchIgnites()) return;
            cause = IgniteCause.PUNCH;
        } else if (item != null && item.getType() == Material.FLINT_AND_STEEL) {
            cause = IgniteCause.FLINT_AND_STEEL;
        } else if (item != null && item.getType() == Material.FIRE_CHARGE) {
            cause = IgniteCause.FIRE_CHARGE;
        } else {
            return;
        }

        if (!type.canBeIgnitedBy(cause)) {
            event.setCancelled(true);
            return;
        }
        if (!hasPermission(player, type)) {
            event.setCancelled(true);
            plugin.lang().send(player, LangKeys.NO_PERMISSION);
            return;
        }

        event.setCancelled(true);
        ignite(clicked, type, player);
    }

    /**
     * Цепная детонация: взрыв поджигает установленные динамиты в радиусе
     * {@code ignition.chain.radius} (или {@code settings.dynamites.chain-radius}).
     *
     * <p>Раньше решение принималось по {@code blockList} — списку блоков,
     * который собрал vanilla. Радиус из конфига при этом не использовался
     * вовсе, и механика была сломана в две стороны:</p>
     *
     * <ol>
     *   <li>у неразрушающих динамитов (Стиллер, Ледяная волна) список пуст
     *       с самого начала — цепочка не работала;</li>
     *   <li>заряд в трёх блоках от эпицентра поджигался даже при
     *       {@code chain-radius: 1}, а заряд за тонкой стенкой — нет, потому
     *       что vanilla его в список не положила.</li>
     * </ol>
     *
     * <p>Теперь источник истины — реестр установленных динамитов: перебираем
     * его (записей обычно единицы, а не тысячи блоков) и отбираем те, что
     * ближе радиуса. Сами блоки из списка разрушения убираются — иначе
     * установленный динамит просто исчез бы, не взорвавшись.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChainDetonation(@NotNull EntityExplodeEvent event) {
        if (plugin.placedDynamites().isEmpty()) return;

        Location center = event.getLocation();
        World world = center.getWorld();
        if (world == null) return;

        String worldName = world.getName();
        Settings.Dynamites global = plugin.settings().dynamites();

        List<Pending> targets = new ArrayList<>();
        long delay = 0L;

        for (Map.Entry<PlacedDynamiteManager.Key, PlacedDynamiteManager.Placed> entry
                : plugin.placedDynamites().entries()) {
            PlacedDynamiteManager.Key key = entry.getKey();
            if (!key.world().equals(worldName)) continue;

            DynamiteType type = plugin.registry().byId(entry.getValue().typeId());
            if (type == null) continue;                     // тип убрали из конфига
            if (!type.canBeIgnitedBy(IgniteCause.EXPLOSION)) continue;
            if (!type.chainEnabled()) continue;

            int radius = type.chainRadius(global.chainRadius());
            if (radius <= 0) continue;

            // Чанк мог быть выгружен — грузить его ради цепочки нельзя: это
            // и лишний ввод-вывод, и обращение к чужому региону на Folia.
            if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;

            double dx = (key.x() + 0.5) - center.getX();
            double dy = (key.y() + 0.5) - center.getY();
            double dz = (key.z() + 0.5) - center.getZ();
            if (dx * dx + dy * dy + dz * dz > (double) radius * radius) continue;

            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.TNT) continue;  // запись устарела

            targets.add(new Pending(block, type, entry.getValue().placedBy()));
            delay = Math.max(delay, type.chainDelayTicks(global.chainDelayTicks()));
        }

        if (targets.isEmpty()) return;

        // Заряды, которые этот взрыв поджёг, не должны быть разрушены им же.
        Set<Pos> ignited = new HashSet<>(Math.max(16, targets.size() * 2));
        for (Pending pending : targets) {
            ignited.add(new Pos(pending.block().getX(),
                    pending.block().getY(), pending.block().getZ()));
        }
        event.blockList().removeIf(block -> ignited.contains(new Pos(
                block.getX(), block.getY(), block.getZ())));

        long shotDelay = Math.max(1L, delay);
        for (Pending pending : targets) {
            Block block = pending.block();
            DynamiteType type = pending.type();
            UUID placer = pending.placedBy();

            Schedulers.runAtLocation(plugin, block.getLocation(), () -> {
                if (block.getType() != Material.TNT) return; // успели сломать
                igniteByChain(block, type,
                        placer == null ? null : plugin.getServer().getPlayer(placer));
            }, shotDelay);
        }
    }

    /** Координаты блока внутри одного мира: дешёвый ключ для сравнения. */
    private record Pos(int x, int y, int z) {
    }

    private record Pending(@NotNull Block block,
                           @NotNull DynamiteType type,
                           @Nullable UUID placedBy) {
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private void ignite(@NotNull Block block,
                        @NotNull DynamiteType type,
                        @Nullable Player player) {
        long delay = Math.max(0L, type.ignition().delayTicks());
        if (delay > 0L) {
            Schedulers.runAtLocation(plugin, block.getLocation(),
                    () -> igniteNow(block, type, player), delay);
            return;
        }
        igniteNow(block, type, player);
    }

    private void igniteByChain(@NotNull Block block,
                               @NotNull DynamiteType type,
                               @Nullable Player player) {
        igniteNow(block, type, player);
    }

    private void igniteNow(@NotNull Block block,
                           @NotNull DynamiteType type,
                           @Nullable Player player) {
        if (!plugin.placedDynamites().contains(block)) return;

        Chunk chunk = block.getChunk();
        UUID uuid = player != null ? player.getUniqueId() : null;

        // Поджог уже установленного заряда не обязан упираться в кулдаун
        // активации, иначе связку нельзя поджечь быстро, — но лимиты действуют.
        if (!plugin.antiLag().tryRegister(uuid, chunk,
                type.maxPerPlayer(plugin.settings().antiLag().maxPrimedPerPlayer()),
                type.maxPerChunk(plugin.settings().antiLag().maxPrimedPerChunk()))) {
            if (player != null) plugin.lang().send(player, LangKeys.PLAYER_LIMIT);
            return;
        }

        TNTPrimed primed = plugin.priming().ignite(block, type, player);
        if (primed == null) {
            plugin.antiLag().release(uuid, chunk);
            return;
        }

        if (player != null) {
            plugin.lang().sendOr(player, type.messages().ignited(), LangKeys.DYNAMITE_IGNITED,
                    "%name%", type.displayName());
        }
    }

    private boolean hasPermission(@NotNull Player player, @NotNull DynamiteType type) {
        if (!type.requiresPermission()) return true;
        return player.hasPermission(type.permission())
                || player.hasPermission("qwetnts.use");
    }

    /** Причина поджога от vanilla → наше перечисление из конфига. */
    private static @NotNull IgniteCause map(@NotNull BlockIgniteEvent.IgniteCause cause) {
        return switch (cause) {
            case FLINT_AND_STEEL -> IgniteCause.FLINT_AND_STEEL;
            case FIREBALL -> IgniteCause.FIRE_CHARGE;
            case LAVA -> IgniteCause.LAVA;
            case LIGHTNING -> IgniteCause.LIGHTNING;
            case EXPLOSION, ENDER_CRYSTAL -> IgniteCause.EXPLOSION;
            case SPREAD, ARROW -> IgniteCause.FIRE;
        };
    }
}
