package ru.qweyns.qwetnts.cannon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * Всё, что происходит с блоком пушки: установка, открытие меню, зарядка,
 * выстрел по редстоуну, поломка и гибель от взрыва.
 *
 * <p>Приоритеты расставлены так, чтобы не мешать QPS: установку помечаем
 * только если её никто не отменил ({@code MONITOR}), а открытие меню
 * перехватываем на {@code HIGHEST} — раньше, чем сработает раздатчик.</p>
 */
public final class CannonListener implements Listener {

    private final QweTnts plugin;

    public CannonListener(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Установка
    // ------------------------------------------------------------------

    /** Ставим блок — помечаем его пушкой. Отменённую установку не трогаем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (!plugin.cannon().enabled()) return;
        if (!plugin.cannonItem().isCannon(event.getItemInHand())) return;

        Player player = event.getPlayer();
        if (!player.hasPermission(plugin.cannon().permission())) {
            event.setCancelled(true);
            plugin.lang().send(player, LangKeys.NO_PERMISSION);
            return;
        }

        // Помечаем на следующем тике: сразу после установки блок-сущность
        // раздатчика может быть ещё не создана, и метка бы потерялась.
        Block placed = event.getBlockPlaced();
        Schedulers.runAtLocation(plugin, placed.getLocation(),
                () -> CannonBlocks.mark(plugin, placed), 1L);

        plugin.lang().send(player, LangKeys.CANNON_PLACED);
    }

    // ------------------------------------------------------------------
    // Меню
    // ------------------------------------------------------------------

    /** ПКМ по пушке — открываем своё меню вместо инвентаря раздатчика. */
    // ignoreCancelled: если клик уже обработан (например, наш же
    // DynamiteUseListener поставил динамит и отменил событие), пушка
    // не должна вдогонку открывать меню.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!plugin.cannon().enabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || !CannonBlocks.isCannon(plugin, block)) return;

        Player player = event.getPlayer();

        // Шифт — как в vanilla: не открываем меню, а даём поставить блок
        // вплотную к пушке.
        if (player.isSneaking()) return;

        event.setCancelled(true);

        if (!player.hasPermission(plugin.cannon().permission())) {
            plugin.lang().send(player, LangKeys.NO_PERMISSION);
            return;
        }

        // Открываем на следующем тике: так клиент гарантированно не успевает
        // показать инвентарь самого раздатчика.
        Schedulers.runAtLocation(plugin, block.getLocation(), () -> open(player, block));
    }

    /** Раздатчик больше ничего сам не раздаёт: заряд хранится у нас. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(@NotNull BlockDispenseEvent event) {
        if (!plugin.cannon().enabled()) return;
        if (CannonBlocks.isCannon(plugin, event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private void open(@NotNull Player player, @NotNull Block block) {
        if (!CannonBlocks.isCannon(plugin, block)) return;

        CannonBlocks.State state = CannonBlocks.read(plugin, block);
        ItemStack ammo = state.isEmpty() ? null : plugin.cannonService().ammoItem(state.kind(), state.count());

        // Если заряд лежит, но такой динамит выключили — показываем предметом
        // как есть, чтобы игрок его забрал, а не потерял.
        if (ammo == null && !state.isEmpty()) {
            ammo = new ItemStack(Material.TNT, Math.min(state.count(), 64));
        }

        CannonMenu menu = new CannonMenu(plugin.cannon());
        player.openInventory(menu.create(block.getLocation(), ammo));
    }

    // ------------------------------------------------------------------
    // Защита рамки
    // ------------------------------------------------------------------

    /** Панели рамки трогать нельзя: ни взять, ни положить, ни сдвинуть. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder() instanceof CannonMenu.CannonHolder)) return;

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= CannonMenu.SIZE) {
            // Клик в инвентаре игрока: shift-клик вверх разрешаем — проверим
            // содержимое при закрытии.
            return;
        }
        if (CannonMenu.isBorder(raw)) {
            event.setCancelled(true);
        }
    }

    /** Протаскивание мышью по рамке тоже запрещено. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder() instanceof CannonMenu.CannonHolder)) return;

        for (int raw : event.getRawSlots()) {
            if (CannonMenu.isBorder(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Закрыли меню — сохраняем заряд в блок, а лишнее возвращаем игроку. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder() instanceof CannonMenu.CannonHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        ItemStack ammo = view.getTopInventory().getItem(CannonMenu.AMMO_SLOT);
        Block block = holder.location().getBlock();

        if (!CannonBlocks.isCannon(plugin, block)) {
            // Пушку успели сломать, пока меню было открыто.
            giveBack(player, ammo);
            return;
        }

        CannonSettings settings = plugin.cannon();

        if (CannonMenu.isAir(ammo)) {
            CannonBlocks.write(plugin, block, null, 0, player.getUniqueId());
            return;
        }

        String kind = plugin.cannonService().kindOf(ammo);
        if (kind == null || !settings.ammo().allows(kind)) {
            CannonBlocks.write(plugin, block, null, 0, player.getUniqueId());
            giveBack(player, ammo);
            plugin.lang().send(player, LangKeys.CANNON_AMMO_DENIED);
            return;
        }

        CannonBlocks.write(plugin, block, kind, ammo.getAmount(), player.getUniqueId());
        plugin.lang().send(player, LangKeys.CANNON_LOADED,
                "%name%", plugin.cannonService().displayName(kind),
                "%count%", String.valueOf(ammo.getAmount()));
    }

    private void giveBack(@NotNull Player player, @Nullable ItemStack stack) {
        if (CannonMenu.isAir(stack)) return;
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------
    // Выстрел по редстоуну
    // ------------------------------------------------------------------

    /**
     * Сигнал редстоуна — выстрел. Реагируем только на фронт (0 → больше 0),
     * иначе непрерывный сигнал палил бы каждый тик.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(@NotNull BlockRedstoneEvent event) {
        if (!plugin.cannon().enabled()) return;
        if (event.getOldCurrent() > 0 || event.getNewCurrent() <= 0) return;

        Block block = event.getBlock();
        if (!CannonBlocks.isCannon(plugin, block)) return;

        long delay = plugin.cannon().launch().delayTicks();
        if (delay <= 0L) {
            plugin.cannonService().fire(block, null);
            return;
        }
        Schedulers.runAtLocation(plugin, block.getLocation(),
                () -> plugin.cannonService().fire(block, null), delay);
    }

    // ------------------------------------------------------------------
    // Поломка и гибель
    // ------------------------------------------------------------------

    /** Сломали рукой — отдаём и пушку, и заряд. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (!plugin.cannon().enabled()) return;

        Block block = event.getBlock();
        if (!CannonBlocks.isCannon(plugin, block)) return;

        CannonBlocks.State state = CannonBlocks.read(plugin, block);
        event.setDropItems(false);

        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        drop(at, plugin.cannonItem().create());
        ItemStack ammo = state.isEmpty() ? null : plugin.cannonService().ammoItem(state.kind(), state.count());
        drop(at, ammo);
    }

    /**
     * Взрыв: пушку убираем из списка сами, чтобы вместо неё не выпал обычный
     * раздатчик. Владелец получает и пушку, и заряд обратно.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(@NotNull EntityExplodeEvent event) {
        if (!plugin.cannon().enabled()) return;

        List<Block> cannons = new ArrayList<>();
        for (Block block : event.blockList()) {
            if (CannonBlocks.isCannon(plugin, block)) {
                cannons.add(block);
            }
        }
        if (cannons.isEmpty()) return;

        for (Block block : cannons) {
            CannonBlocks.State state = CannonBlocks.read(plugin, block);
            event.blockList().remove(block);

            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            ItemStack ammo = state.isEmpty()
                    ? null
                    : plugin.cannonService().ammoItem(state.kind(), state.count());

            // Блок убираем на следующем тике: сейчас идёт обработка взрыва.
            Schedulers.runAtLocation(plugin, at, () -> {
                if (CannonBlocks.isCannon(plugin, block)) {
                    CannonBlocks.clear(plugin, block);
                    block.setType(Material.AIR, false);
                }
                drop(at, plugin.cannonItem().create());
                drop(at, ammo);
            }, 1L);
        }
    }

    private void drop(@NotNull Location at, @Nullable ItemStack stack) {
        if (CannonMenu.isAir(stack)) return;
        at.getWorld().dropItemNaturally(at, stack);
    }
}
