package ru.qweyns.qwetnts;

import org.bukkit.NamespacedKey;

/** PDC-ключи, которыми аддон помечает сущности TNTPrimed, предметы и блоки. */
public final class Keys {

    /** Строковый id типа динамита (dynamite_a, dynamite_b, c4, shockwave). */
    public final NamespacedKey dynamiteKind;

    /** Метка предмета-компонента крафта (например, Взрывчатое вещество). */
    public final NamespacedKey component;

    /** Метка предмета Тнт-пушки. */
    public final NamespacedKey cannonItem;

    /** Метка блока Тнт-пушки (живёт в блок-сущности раздатчика). */
    public final NamespacedKey cannonBlock;

    /** Заряд пушки в формате {@code id:количество}. */
    public final NamespacedKey cannonAmmo;

    /** Кто последний заряжал пушку (UUID строкой) — для учёта атаки в QPS. */
    public final NamespacedKey cannonOwner;

    /** Снаряд выпущен именно Тнт-пушкой (механика бункера считает только их). */
    public final NamespacedKey cannonShot;

    /**
     * Имя игрока, сделавшего выстрел из пушки.
     *
     * <p>Имя, а не UUID: награда за пробитую стену уходит в команду другого
     * плагина, а выяснять имя по UUID в момент взрыва — значит лезть в
     * офлайн-данные на ходу.</p>
     */
    public final NamespacedKey cannonShooter;

    /**
     * Координаты чанка, в котором заряд был создан.
     *
     * <p>Квота анти-лага занимается по месту установки/поджога, а заряд
     * взрывается там, куда его занесло (снаряд пушки улетает на десятки
     * блоков). Без этих меток {@code release()} снимал бы квоту с чужого
     * чанка и тем самым открывал обход лимита.</p>
     */
    public final NamespacedKey originChunkX;
    public final NamespacedKey originChunkZ;

    public Keys(QweTnts plugin) {
        this.dynamiteKind = new NamespacedKey(plugin, "dynamite-kind");
        this.component = new NamespacedKey(plugin, "component");
        this.cannonItem = new NamespacedKey(plugin, "cannon-item");
        this.cannonBlock = new NamespacedKey(plugin, "cannon-block");
        this.cannonAmmo = new NamespacedKey(plugin, "cannon-ammo");
        this.cannonOwner = new NamespacedKey(plugin, "cannon-owner");
        this.cannonShot = new NamespacedKey(plugin, "cannon-shot");
        this.cannonShooter = new NamespacedKey(plugin, "cannon-shooter");
        this.originChunkX = new NamespacedKey(plugin, "origin-chunk-x");
        this.originChunkZ = new NamespacedKey(plugin, "origin-chunk-z");
    }
}
