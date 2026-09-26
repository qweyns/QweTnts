package ru.qweyns.qwetnts.cannon;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.antilag.AntiLag;
import ru.qweyns.qwetnts.config.LangKeys;
import ru.qweyns.qwetnts.config.Settings;
import ru.qweyns.qwetnts.dynamite.BlastMath;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Effects;
import ru.qweyns.qwetnts.util.Items;

import java.util.Locale;
import java.util.UUID;

/**
 * Выстрел пушки: берёт один заряд из блока и запускает его в сторону дула.
 *
 * <p>Главное правило (оно же на HolyWorld): <b>запущенный динамит сохраняет
 * все свои свойства</b>. Поэтому снаряд — это ровно тот же {@link TNTPrimed}
 * с той же PDC-меткой, что и при обычном поджоге: дальше работают те же
 * правила ломания блоков, урон, рейд-блоки, спавнер-майнинг и тип взрыва
 * для QPS.</p>
 */
public final class CannonService {

    /** Фитиль ванильного TNT: 4 секунды (как в vanilla). */
    private static final int VANILLA_FUSE_TICKS = 80;

    private final QweTnts plugin;

    public CannonService(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Боеприпасы
    // ------------------------------------------------------------------

    /**
     * Определяет, что за боеприпас лежит в руках.
     *
     * @return id динамита, {@link CannonSettings#VANILLA_TNT} для ванильного
     *         TNT или {@code null}, если это не боеприпас
     */
    public @Nullable String kindOf(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;

        String kind = Items.tag(plugin.keys().dynamiteKind, stack);
        if (kind != null && plugin.registry().byId(kind) != null) {
            return kind.toLowerCase(Locale.ROOT);
        }
        return stack.getType() == Material.TNT ? CannonSettings.VANILLA_TNT : null;
    }

    /** Собирает предмет боеприпаса по его id. */
    public @Nullable ItemStack ammoItem(@NotNull String kind, int count) {
        if (count <= 0) return null;

        if (CannonSettings.VANILLA_TNT.equals(kind)) {
            ItemStack tnt = new ItemStack(Material.TNT);
            tnt.setAmount(Math.min(count, tnt.getMaxStackSize()));
            return tnt;
        }
        DynamiteType type = plugin.registry().byId(kind);
        if (type == null) return null;

        ItemStack stack = type.item();
        stack.setAmount(Math.min(count, stack.getMaxStackSize()));
        return stack;
    }

    /** Человеческое название боеприпаса — для сообщений. */
    public @NotNull String displayName(@NotNull String kind) {
        if (CannonSettings.VANILLA_TNT.equals(kind)) {
            return plugin.lang().raw(LangKeys.CANNON_AMMO_TNT);
        }
        DynamiteType type = plugin.registry().byId(kind);
        return type != null ? type.displayName() : kind;
    }

    // ------------------------------------------------------------------
    // Выстрел
    // ------------------------------------------------------------------

    /**
     * Произвести выстрел, если пушка заряжена и это разрешено настройками.
     *
     * @param shooter игрок, которому засчитывается выстрел (для QPS);
     *                {@code null} — берётся тот, кто заряжал пушку
     */
    public void fire(@NotNull Block block, @Nullable Player shooter) {
        CannonSettings settings = plugin.cannon();
        if (!settings.enabled() || !CannonBlocks.isCannon(plugin, block)) return;

        CannonBlocks.State state = CannonBlocks.read(plugin, block);
        if (state.isEmpty()) return;

        String kind = state.kind();
        if (!settings.ammo().allows(kind)) return;

        boolean vanilla = CannonSettings.VANILLA_TNT.equals(kind);
        DynamiteType type = vanilla ? null : plugin.registry().byId(kind);
        if (type == null && !vanilla) return; // такой динамит убрали из конфига

        Player source = shooter != null ? shooter : online(state.owner());
        if (source != null && !source.hasPermission(settings.permission())) return;

        World world = block.getWorld();
        Settings.WorldFilter filter = settings.hasOwnWorlds()
                ? settings.worlds()
                : plugin.settings().worldFilter();
        if (!filter.isAllowed(world)) return;

        Settings.AntiLag antiLag = plugin.settings().antiLag();
        int maxPerPlayer = type != null
                ? type.maxPerPlayer(antiLag.maxPrimedPerPlayer())
                : antiLag.maxPrimedPerPlayer();
        int maxPerChunk = type != null
                ? type.maxPerChunk(antiLag.maxPrimedPerChunk())
                : antiLag.maxPrimedPerChunk();

        Chunk chunk = block.getChunk();
        UUID uuid = source != null ? source.getUniqueId() : null;

        // Кулдаун не нужен: частоту стрельбы задаёт редстоун и delay-ticks.
        AntiLag.Deny deny = plugin.antiLag().canActivate(uuid, chunk, 0L, maxPerPlayer, maxPerChunk);
        if (deny != AntiLag.Deny.NONE) {
            if (source != null) {
                plugin.antiLag().notify(source, plugin.lang(), denyKey(deny),
                        "%seconds%", plugin.lang().duration(
                                plugin.antiLag().cooldownRemaining(uuid)));
            }
            return;
        }

        TNTPrimed primed = launch(block, settings, type, source);
        if (primed == null) return;

        plugin.antiLag().commit(uuid, chunk, 0L);
        CannonBlocks.write(plugin, block, kind, state.count() - 1, state.owner());
        Effects.play(plugin, settings.shot(), primed.getLocation());

        if (source != null) {
            plugin.lang().send(source, LangKeys.CANNON_FIRED,
                    "%name%", displayName(kind),
                    "%count%", String.valueOf(state.count() - 1));
        }
    }

    private @Nullable TNTPrimed launch(@NotNull Block block,
                                       @NotNull CannonSettings settings,
                                       @Nullable DynamiteType type,
                                       @Nullable Player source) {
        CannonSettings.Launch launch = settings.launch();
        Vector direction = facing(block).getDirection();

        // Старт чуть впереди блока: иначе снаряд застрянет в самой пушке.
        Location origin = block.getLocation()
                .add(0.5, 0.5, 0.5)
                .add(direction.clone().multiply(0.75));

        Vector velocity = direction.clone().multiply(launch.blocksPerTick());
        if (launch.upwardPerTick() != 0.0) {
            velocity.add(new Vector(0.0, launch.upwardPerTick(), 0.0));
        }

        int fuse = type != null ? type.rollFuseTicks(BlastMath.random()) : VANILLA_FUSE_TICKS;
        int rangeFuse = launch.rangeFuseTicks();
        if (rangeFuse > 0) {
            fuse = Math.min(fuse, Math.max(1, rangeFuse));
        }

        TNTPrimed primed = plugin.priming()
                .launch(origin, type, source, velocity, fuse, launch.gravity());
        if (primed != null) {
            // Метка «выпущено пушкой»: бункер засчитывает только такие
            // снаряды — подойти к стене с динамитом в руках нельзя.
            primed.getPersistentDataContainer().set(
                    plugin.keys().cannonShot, PersistentDataType.BYTE, (byte) 1);

            // Кто стрелял: пригодится, когда снаряд пробьёт стену бункера.
            if (source != null) {
                primed.getPersistentDataContainer().set(
                        plugin.keys().cannonShooter, PersistentDataType.STRING, source.getName());
            }
        }
        return primed;
    }

    /** Куда смотрит дуло: у раздатчика это направление его BlockData. */
    private static @NotNull BlockFace facing(@NotNull Block block) {
        if (block.getBlockData() instanceof Directional directional) {
            return directional.getFacing();
        }
        return BlockFace.UP;
    }

    private @Nullable Player online(@Nullable UUID uuid) {
        if (uuid == null) return null;
        Player player = plugin.getServer().getPlayer(uuid);
        return player != null && player.isOnline() ? player : null;
    }

    /**
     * Ключ сообщения для причины отказа.
     *
     * @throws IllegalStateException если передали {@link AntiLag.Deny#NONE}
     *         — причина «всё можно» не должна доходить до сообщения игроку
     */
    private static @NotNull String denyKey(@NotNull AntiLag.Deny deny) {
        return switch (deny) {
            case COOLDOWN -> LangKeys.COOLDOWN;
            case PLAYER_LIMIT -> LangKeys.PLAYER_LIMIT;
            case CHUNK_LIMIT -> LangKeys.CHUNK_LIMIT;
            case NONE -> throw new IllegalStateException(
                    "Deny.NONE не должен превращаться в сообщение игроку");
        };
    }
}
