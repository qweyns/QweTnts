package ru.qweyns.qwetnts;

import org.bukkit.NamespacedKey;

/** PDC-ключи, которыми аддон помечает сущности TNTPrimed и предметы. */
public final class Keys {

    /** Строковый id типа динамита (dynamite_a, dynamite_b, c4, shockwave). */
    public final NamespacedKey dynamiteKind;

    public Keys(QweTnts plugin) {
        this.dynamiteKind = new NamespacedKey(plugin, "dynamite-kind");
    }
}
