package ru.qweyns.qwetnts.hologram;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.util.Colors;
import ru.qweyns.qwetnts.util.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Встроенные голограммы на {@link TextDisplay}: работают без сторонних
 * плагинов и единственные доступны на Folia.
 *
 * <p>По возможности повторяет поведение мостов DecentHolograms и
 * FancyHolograms: те же настройки внешнего вида. Не поддерживается только
 * {@code permission} — отфильтровать зрителей по праву умеют лишь сами
 * плагины, поэтому при заданном праве пишем предупреждение один раз.</p>
 */
public final class NativeHologramProvider implements HologramProvider {

    private final QweTnts plugin;
    private final Map<UUID, TextDisplay> active = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public NativeHologramProvider(@NotNull QweTnts plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String id() {
        return "NATIVE";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void show(@NotNull UUID id,
                     @NotNull Location at,
                     @NotNull HologramSettings settings,
                     @NotNull List<String> lines) {
        TextDisplay existing = active.get(id);
        if (existing != null && existing.isValid()) {
            update(id, at, lines);
            return;
        }

        Location spawnAt = at.clone();
        Schedulers.runAtLocation(plugin, spawnAt, () -> {
            if (!plugin.isEnabled() || active.containsKey(id)) return;

            World world = spawnAt.getWorld();
            if (world == null) return;

            TextDisplay display = world.spawn(spawnAt, TextDisplay.class,
                    spawned -> configure(spawned, settings));
            display.text(Colors.format(String.join("\n", lines)));

            TextDisplay previous = active.put(id, display);
            if (previous != null && previous != display) {
                removeSilently(previous);
            }
        });
    }

    @Override
    public void update(@NotNull UUID id, @NotNull Location at, @NotNull List<String> lines) {
        TextDisplay display = active.get(id);
        if (display == null) return;

        Schedulers.runAtEntity(plugin, display, () -> {
            if (active.get(id) != display) return;
            if (!display.isValid()) {
                active.remove(id, display);
                return;
            }
            // follow-projectile выключен — точка та же самая, и телепорт был
            // бы лишним пакетом всем, кто видит табличку.
            Location current = display.getLocation();
            if (current.getWorld() != at.getWorld() || current.distanceSquared(at) > 1e-6) {
                display.teleport(at);
            }
            display.text(Colors.format(String.join("\n", lines)));
        });
    }

    @Override
    public void remove(@NotNull UUID id) {
        TextDisplay display = active.remove(id);
        if (display == null) return;

        Schedulers.runAtEntity(plugin, display, display::remove);
    }

    @Override
    public void removeAll() {
        for (UUID id : active.keySet().toArray(new UUID[0])) {
            remove(id);
        }
    }

    private void removeSilently(@NotNull TextDisplay display) {
        Schedulers.runAtEntity(plugin, display, display::remove);
    }

    private void configure(@NotNull TextDisplay display, @NotNull HologramSettings settings) {
        // Иначе после рестарта в мире останутся Display без хозяина.
        display.setPersistent(false);
        display.setBillboard(billboard(settings.billboard()));
        display.setAlignment(alignment(settings.alignment()));
        display.setShadowed(settings.shadow());
        display.setSeeThrough(settings.seeThrough());
        display.setViewRange((float) settings.displayRange());

        float scale = settings.scale();
        display.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));

        Color background = parseColor(settings.background());
        if (background != null) {
            display.setBackgroundColor(background);
        }

        if (!settings.permission().isBlank()) {
            warnOnce("permission",
                    "Право на просмотр для встроенных голограмм не поддерживается — настройка пропущена.");
        }
    }

    private @NotNull Display.Billboard billboard(@NotNull String raw) {
        try {
            return Display.Billboard.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnOnce("billboard:" + raw, "Неизвестный billboard '" + raw + "' — использую CENTER.");
            return Display.Billboard.CENTER;
        }
    }

    private @NotNull TextDisplay.TextAlignment alignment(@NotNull String raw) {
        try {
            return TextDisplay.TextAlignment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnOnce("alignment:" + raw, "Неизвестное выравнивание '" + raw + "' — использую CENTER.");
            return TextDisplay.TextAlignment.CENTER;
        }
    }

    /** Цвет подложки: {@code #40000000} или {@code #RRGGBB}. Пусто — без подложки. */
    private static @Nullable Color parseColor(@NotNull String raw) {
        String hex = raw.trim();
        if (hex.isEmpty()) return null;
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            return hex.length() == 6
                    ? Color.fromARGB(0xFF000000 | Integer.parseUnsignedInt(hex, 16))
                    : Color.fromARGB(Integer.parseUnsignedInt(hex, 16));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void warnOnce(@NotNull String key, @NotNull String message) {
        if (warned.add(key)) {
            plugin.getLogger().warning("[голограммы] " + message);
        }
    }
}
