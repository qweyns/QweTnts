package ru.qweyns.qwetnts.hologram;

import org.bukkit.Location;
import org.bukkit.entity.TNTPrimed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteType;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Голограмма над горящим зарядом: обратный отсчёт до взрыва.
 *
 * <p>Жизненный цикл короткий и жёсткий: показали при поджоге, обновляли по
 * тикам, убрали по взрыву. Всё остальное — защита от крайностей:</p>
 *
 * <ul>
 *   <li><b>предел числа</b> ({@code max-active}): сотня зажжённых зарядов не
 *       должна порождать сотню сущностей;</li>
 *   <li><b>уборка мёртвых</b>: заряд мог исчезнуть без взрыва (сгорел в
 *       воде, удален плагином) — тик это видит по {@code isValid()};</li>
 *   <li><b>один провайдер на заряд</b>: выбранный плагин мог отвалиться
 *       между тиками — тогда голограмму дорисует встроенный.</li>
 * </ul>
 *
 * <p>Ванильный тротил голограммы не получает: аддон отвечает за свои
 * динамиты.</p>
 */
public final class FuseHologramManager {

    private final QweTnts plugin;
    private final Map<UUID, Track> active = new ConcurrentHashMap<>();
    private final Map<HologramSettings.Provider, HologramProvider> providers =
            new java.util.EnumMap<>(HologramSettings.Provider.class);

    private record Track(@NotNull TNTPrimed charge,
                         @NotNull DynamiteType type,
                         @NotNull HologramSettings settings,
                         @NotNull String player,
                         @NotNull Location origin) {
    }

    public FuseHologramManager(@NotNull QweTnts plugin) {
        this.plugin = plugin;
        refresh();
    }

    /**
     * Пересоздать провайдеры (настройки перечитаны).
     *
     * <p>На Folia мосты не создаются вовсе: FancyHolograms и DecentHolograms
     * работают только на Paper, поэтому там всегда встроенные голограммы.</p>
     */
    public void refresh() {
        for (HologramProvider provider : providers.values()) {
            provider.removeAll();
        }
        active.clear();
        providers.clear();

        providers.put(HologramSettings.Provider.NATIVE, new NativeHologramProvider(plugin));

        if (!Schedulers.isFolia()) {
            FancyHologramsProvider fancy = new FancyHologramsProvider(plugin);
            if (fancy.isAvailable()) {
                providers.put(HologramSettings.Provider.FANCY, fancy);
            }
            DecentHologramsProvider decent = new DecentHologramsProvider(plugin);
            if (decent.isAvailable()) {
                providers.put(HologramSettings.Provider.DECENT, decent);
            }
        }
    }

    /**
     * Показать голограмму над зажжённым зарядом.
     *
     * @param player кто поджёг; {@code null} — подставится прочерк
     */
    public void onIgnite(@NotNull TNTPrimed charge,
                         @Nullable DynamiteType type,
                         @Nullable String player) {
        if (type == null) return;   // ванильный TNT — не наш

        HologramSettings settings = resolve(type);
        if (!settings.enabled() || settings.lines().isEmpty()) return;

        UUID id = charge.getUniqueId();
        if (active.containsKey(id)) return;
        if (settings.maxActive() > 0 && active.size() >= settings.maxActive()) return;

        Location origin = point(charge, settings);
        Track track = new Track(charge, type, settings,
                player == null ? "" : player, origin.clone());

        if (active.putIfAbsent(id, track) != null) return;   // уже показываем

        try {
            provider(settings).show(id, origin, settings, lines(track));
        } catch (RuntimeException ex) {
            // Провайдер не справился — запись снимаем, иначе менеджер считал
            // бы голограмму показанной и никогда бы её не убрал.
            active.remove(id, track);
            plugin.getLogger().log(Level.FINE,
                    "Не удалось показать голограмму над " + type.id(), ex);
        }
    }

    /** Заряд взорвался (или исчез) — убрать голограмму сразу, не дожидаясь тика. */
    public void onDetonated(@NotNull TNTPrimed charge) {
        remove(charge.getUniqueId());
    }

    /** Периодический тик: обновить отсчёт и позицию, убрать мёртвые заряды. */
    public void tick() {
        if (active.isEmpty()) return;

        for (UUID id : active.keySet().toArray(new UUID[0])) {
            Track track = active.get(id);
            if (track == null) {
                continue;
            }

            TNTPrimed charge = track.charge();
            if (!charge.isValid() || charge.isDead()) {
                remove(id);
                continue;
            }

            Location at = track.settings().follow()
                    ? point(charge, track.settings())
                    : track.origin();

            try {
                provider(track.settings()).update(id, at, lines(track));
            } catch (RuntimeException ex) {
                // Ошибка обновления повторялась бы каждый тик до конца
                // фитиля — именно так одна поломка превращалась в сотни
                // одинаковых строк в логе. Снимаем табличку сразу: заряд
                // без отсчёта всё равно взорвётся как надо.
                plugin.getLogger().log(Level.FINE,
                        "Голограмма снята: обновление не удалось", ex);
                remove(id);
            }
        }
    }

    public void removeAll() {
        for (UUID id : active.keySet().toArray(new UUID[0])) {
            remove(id);
        }
    }

    /** Сколько голограмм сейчас на сервере. */
    public int activeCount() {
        return active.size();
    }

    /** Какие мосты реально поднялись — для лога и {@code /qtnt stats}. */
    public @NotNull String availableProviders() {
        StringBuilder out = new StringBuilder("NATIVE");
        HologramProvider fancy = providers.get(HologramSettings.Provider.FANCY);
        if (fancy != null) out.append(", ").append(fancy.id());
        HologramProvider decent = providers.get(HologramSettings.Provider.DECENT);
        if (decent != null) out.append(", ").append(decent.id());
        return out.toString();
    }

    private void remove(@NotNull UUID id) {
        Track track = active.remove(id);
        if (track == null) return;
        provider(track.settings()).remove(id);
    }

    /**
     * Настройки заряда: секция {@code hologram} его файла поверх общих из
     * {@code config.yml}. Так у C4 может быть свой вид, а у остальных — общий.
     */
    private @NotNull HologramSettings resolve(@NotNull DynamiteType type) {
        return HologramSettings.merge(type.hologram(), plugin.settings().holograms());
    }

    /** Провайдер по настройкам: выбранный плагин может отсутствовать — берём доступный. */
    private @NotNull HologramProvider provider(@NotNull HologramSettings settings) {
        HologramProvider chosen = switch (settings.provider()) {
            case NATIVE -> providers.get(HologramSettings.Provider.NATIVE);
            case FANCY -> firstAvailable(HologramSettings.Provider.FANCY,
                    HologramSettings.Provider.DECENT);
            case DECENT -> firstAvailable(HologramSettings.Provider.DECENT,
                    HologramSettings.Provider.FANCY);
            case AUTO -> firstAvailable(HologramSettings.Provider.FANCY,
                    HologramSettings.Provider.DECENT);
        };
        return chosen != null ? chosen : providers.get(HologramSettings.Provider.NATIVE);
    }

    private @Nullable HologramProvider firstAvailable(@NotNull HologramSettings.Provider first,
                                                      @NotNull HologramSettings.Provider second) {
        HologramProvider provider = providers.get(first);
        if (provider != null && provider.isAvailable()) return provider;
        provider = providers.get(second);
        if (provider != null && provider.isAvailable()) return provider;
        return null;
    }

    private static @NotNull Location point(@NotNull TNTPrimed charge, @NotNull HologramSettings settings) {
        return charge.getLocation().add(0.0, settings.offset(), 0.0);
    }

    private @NotNull List<String> lines(@NotNull Track track) {
        TNTPrimed charge = track.charge();
        int ticks = Math.max(0, charge.getFuseTicks());
        // Округляем вверх: пока осталось хоть 2,1 секунды, видим «3».
        int seconds = (ticks + 19) / 20;
        return HologramText.render(track.settings().lines(), seconds, ticks,
                track.type().displayName(), track.player().isBlank() ? null : track.player());
    }
}
