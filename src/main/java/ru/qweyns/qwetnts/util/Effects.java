package ru.qweyns.qwetnts.util;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.qweyns.qwetnts.QweTnts;
import ru.qweyns.qwetnts.dynamite.DynamiteEffect;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Звуки и частицы динамитов.
 *
 * <p>Имена берутся из конфига строками: звук — ключом реестра
 * ({@code entity.tnt.primed}), частица — именем {@code Particle}
 * ({@code EXPLOSION}). Кеш защищает от повторного разбора на каждом взрыве,
 * а неизвестные значения просто молча игнорируются.</p>
 */
public final class Effects {

    private static final Map<String, Optional<Sound>> SOUND_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Particle>> PARTICLE_CACHE = new ConcurrentHashMap<>();

    private Effects() {
    }

    /** Проиграть эффект в точке. Никогда не бросает исключений наружу. */
    public static void play(@NotNull QweTnts plugin,
                            @Nullable DynamiteEffect effect,
                            @Nullable Location at) {
        if (effect == null || at == null) return;

        World world = at.getWorld();
        if (world == null) return;

        try {
            Sound sound = sound(effect.sound());
            if (sound != null) {
                world.playSound(at, sound, effect.category(), effect.volume(), effect.pitch());
            }

            Particle particle = particle(effect.particle());
            if (particle != null && effect.particleCount() > 0) {
                world.spawnParticle(particle, at, effect.particleCount(),
                        effect.spread(), effect.spread(), effect.spread());
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Не удалось проиграть эффект: " + ex.getMessage());
        }
    }

    public static @Nullable Sound sound(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        return SOUND_CACHE
                .computeIfAbsent(key.trim().toLowerCase(Locale.ROOT), Effects::lookupSound)
                .orElse(null);
    }

    public static @Nullable Particle particle(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        return PARTICLE_CACHE
                .computeIfAbsent(name.trim().toUpperCase(Locale.ROOT), Effects::lookupParticle)
                .orElse(null);
    }

    private static @NotNull Optional<Sound> lookupSound(@NotNull String key) {
        try {
            NamespacedKey namespacedKey = NamespacedKey.fromString(key);
            Sound sound = namespacedKey == null ? null : Registry.SOUNDS.get(namespacedKey);
            return Optional.ofNullable(sound);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static @NotNull Optional<Particle> lookupParticle(@NotNull String name) {
        try {
            return Optional.ofNullable(Particle.valueOf(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Разбор {@link SoundCategory}; при ошибке — BLOCKS. */
    public static @NotNull SoundCategory category(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return SoundCategory.BLOCKS;
        try {
            return SoundCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SoundCategory.BLOCKS;
        }
    }
}
