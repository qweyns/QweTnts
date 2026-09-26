package ru.qweyns.qwetnts.hologram;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Голограммы через DecentHolograms.
 *
 * <p>Голограммы создаются с {@code saveToFile = false}: они живут, пока
 * горит фитиль, и попадать в конфиг DecentHolograms не должны — иначе после
 * рестарта сервер поднял бы сотни мёртвых голограмм.</p>
 */
public final class DecentHologramsProvider implements HologramProvider {

    private static final String PREFIX = "qwetnts-";

    private final QweTnts plugin;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public DecentHologramsProvider(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String id() {
        return "DecentHolograms";
    }

    @Override
    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("DecentHolograms");
    }

    @Override
    public void show(@NotNull UUID id,
                     @NotNull Location at,
                     @NotNull HologramSettings settings,
                     @NotNull List<String> lines) {
        if (!isAvailable()) return;

        String name = PREFIX + id;
        names.put(id, name);

        try {
            DHAPI.createHologram(name, at, false, lines);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().fine("DecentHolograms отказался создать голограмму: " + ex.getMessage());
        }
    }

    @Override
    public void update(@NotNull UUID id, @NotNull Location at, @NotNull List<String> lines) {
        String name = names.get(id);
        if (name == null || !isAvailable()) return;

        Hologram hologram = hologram(name);
        if (hologram == null) return;

        try {
            for (int i = 0; i < lines.size(); i++) {
                DHAPI.setHologramLine(hologram, i, lines.get(i));
            }
            DHAPI.moveHologram(name, at);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().fine("DecentHolograms: не удалось обновить голограмму: " + ex.getMessage());
        }
    }

    @Override
    public void remove(@NotNull UUID id) {
        String name = names.remove(id);
        if (name == null || !isAvailable()) return;

        try {
            DHAPI.removeHologram(name);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().fine("DecentHolograms: голограмма уже удалена: " + ex.getMessage());
        }
    }

    @Override
    public void removeAll() {
        for (UUID id : names.keySet().toArray(new UUID[0])) {
            remove(id);
        }
    }

    private @Nullable Hologram hologram(@NotNull String name) {
        try {
            return DHAPI.getHologram(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
