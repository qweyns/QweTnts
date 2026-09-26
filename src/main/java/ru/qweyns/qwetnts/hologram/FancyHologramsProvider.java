package ru.qweyns.qwetnts.hologram;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.data.TextHologramData;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Голограммы через FancyHolograms.
 *
 * <p>Нюанс, из-за которого мост нельзя писать «в лоб»: FancyHolograms
 * сохраняет свои голограммы в собственный файл, а наши живут несколько
 * секунд. Поэтому {@code persistent} выключен — иначе после каждого выстрела
 * из пушки в конфиге плагина оставался бы мусор.</p>
 */
public final class FancyHologramsProvider implements HologramProvider {

    private static final String PREFIX = "qwetnts-";

    private final QweTnts plugin;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public FancyHologramsProvider(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String id() {
        return "FancyHolograms";
    }

    @Override
    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("FancyHolograms")
                && FancyHologramsPlugin.isEnabled();
    }

    @Override
    public void show(@NotNull UUID id,
                     @NotNull Location at,
                     @NotNull HologramSettings settings,
                     @NotNull List<String> lines) {
        String name = PREFIX + id;
        names.put(id, name);

        Schedulers.runAtLocation(plugin, at, () -> {
            if (!plugin.isEnabled() || !isAvailable()) return;

            try {
                de.oliver.fancyholograms.api.HologramManager manager = manager();
                if (manager == null) return;

                Hologram hologram = manager.getHologram(name).orElse(null);
                if (hologram == null) {
                    hologram = manager.create(new TextHologramData(name, at));
                    manager.addHologram(hologram);
                }
                if (!(hologram.getData() instanceof TextHologramData data)) return;

                apply(data, at, settings, lines);
                hologram.forceUpdate();
                hologram.refreshForViewersInWorld();
            } catch (RuntimeException ex) {
                plugin.getLogger().fine("FancyHolograms: не удалось создать голограмму: "
                        + ex.getMessage());
            }
        });
    }

    @Override
    public void update(@NotNull UUID id, @NotNull Location at, @NotNull List<String> lines) {
        String name = names.get(id);
        if (name == null || !isAvailable()) return;

        Schedulers.runAtLocation(plugin, at, () -> {
            try {
                de.oliver.fancyholograms.api.HologramManager manager = manager();
                if (manager == null) return;

                Hologram hologram = manager.getHologram(name).orElse(null);
                if (hologram == null) return;
                if (!(hologram.getData() instanceof TextHologramData data)) return;

                data.setLocation(at);
                data.setText(lines);
                hologram.forceUpdate();
            } catch (RuntimeException ex) {
                plugin.getLogger().fine("FancyHolograms: не удалось обновить голограмму: "
                        + ex.getMessage());
            }
        });
    }

    @Override
    public void remove(@NotNull UUID id) {
        String name = names.remove(id);
        if (name == null) return;

        try {
            de.oliver.fancyholograms.api.HologramManager manager = manager();
            if (manager == null) return;

            manager.getHologram(name).ifPresent(manager::removeHologram);
        } catch (RuntimeException ex) {
            // Плагин мог отключиться между тиками: наша голограмма — мусор,
            // который FancyHolograms не сохраняет (persistent=false).
            plugin.getLogger().fine("FancyHolograms: голограмма уже удалена: "
                    + ex.getMessage());
        }
    }

    @Override
    public void removeAll() {
        for (UUID id : names.keySet().toArray(new UUID[0])) {
            remove(id);
        }
    }

    private void apply(@NotNull TextHologramData data,
                       @NotNull Location at,
                       @NotNull HologramSettings settings,
                       @NotNull List<String> lines) {
        data.setPersistent(false);      // иначе FH сохранит временные голограммы в свой файл
        data.setLocation(at);
        data.setText(lines);
        data.setTextShadow(settings.shadow());
        data.setSeeThrough(settings.seeThrough());
        data.setTextAlignment(alignment(settings.alignment()));
        data.setBillboard(billboard(settings.billboard()));
        data.setScale(new Vector3f(settings.scale(), settings.scale(), settings.scale()));
        data.setVisibilityDistance((int) Math.round(settings.displayRange()));
    }

    private @Nullable de.oliver.fancyholograms.api.HologramManager manager() {
        if (!FancyHologramsPlugin.isEnabled()) return null;
        FancyHologramsPlugin api = FancyHologramsPlugin.get();
        return api == null ? null : api.getHologramManager();
    }

    private @NotNull Display.Billboard billboard(@NotNull String raw) {
        try {
            return Display.Billboard.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Display.Billboard.CENTER;
        }
    }

    private @NotNull TextDisplay.TextAlignment alignment(@NotNull String raw) {
        try {
            return TextDisplay.TextAlignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TextDisplay.TextAlignment.CENTER;
        }
    }
}
