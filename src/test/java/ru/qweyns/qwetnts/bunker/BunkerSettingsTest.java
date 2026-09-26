package ru.qweyns.qwetnts.bunker;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Настройки бункера: шансы и геометрия стены.
 *
 * <p>Проверяется то, что нельзя увидеть глазами на сервере: что шанс 0,4 %
 * не превратился в ноль, что ключ {@code C4} находится без учёта регистра
 * и что после пробития проход перестаёт считаться стеной.</p>
 */
class BunkerSettingsTest {

    private static final List<Material> STAGES =
            List.of(Material.ANCIENT_DEBRIS, Material.CRYING_OBSIDIAN,
                    Material.OBSIDIAN, Material.AIR);

    private static BunkerSettings.Wall wall() {
        return new BunkerSettings.Wall(-2, 40, -2, 2, 50, 2, STAGES, 3, true);
    }

    @Test
    void chanceFallsBackToDefault() {
        BunkerSettings settings = new BunkerSettings(true, "world", wall(), 500L,
                Map.of("c4", 5.0, "shockwave", 12.5), 0.4,
                BunkerSettings.Respawn.NONE, true, BunkerSettings.Effects.NONE, BunkerSettings.Reward.NONE);

        assertEquals(5.0, settings.chanceFor("c4"), 1e-9);
        assertEquals(12.5, settings.chanceFor("shockwave"), 1e-9);
        assertEquals(0.4, settings.chanceFor("dynamite_a"), 1e-9,
                "для динамита без своей строки берётся default");
    }

    @Test
    void chanceIsCaseInsensitive() {
        BunkerSettings settings = new BunkerSettings(true, "world", wall(), 0L,
                Map.of("c4", 5.0), 0.4,
                BunkerSettings.Respawn.NONE, true, BunkerSettings.Effects.NONE, BunkerSettings.Reward.NONE);

        assertEquals(5.0, settings.chanceFor("C4"), 1e-9,
                "id в конфиге пишут по-разному: C4 и c4 — один динамит");
    }

    @Test
    void lastStageIsTheBreach() {
        BunkerSettings settings = new BunkerSettings(true, "world", wall(), 0L,
                Map.of(), 0.4, BunkerSettings.Respawn.NONE, true,
                BunkerSettings.Effects.NONE, BunkerSettings.Reward.NONE);

        assertEquals(3, settings.lastStageIndex(),
                "четыре стадии: индекс последней — 3");
        assertEquals(Material.AIR, settings.wall().stageMaterial(settings.lastStageIndex()),
                "последняя стадия — это дыра, иначе стена никогда не будет пробита");
    }

    @Test
    void wallGeometry() {
        BunkerSettings.Wall wall = wall();

        assertTrue(wall.contains(0, 45, 0));
        assertFalse(wall.contains(3, 45, 0), "за пределами прямоугольника стены нет");
        assertTrue(wall.isDefined());

        // Запас на то, что снаряд взрывается рядом, а не в кладке.
        assertTrue(wall.near(4, 45, 0, 3));
        assertFalse(wall.near(6, 45, 0, 3));

        assertEquals(45, wall.centerY());
        assertEquals(Material.OBSIDIAN, wall.stageMaterial(2));
        assertNull(wall.stageMaterial(99), "стадии с таким индексом нет");
    }

    @Test
    void airInsideWallIsNotAWallAnymore() {
        BunkerSettings.Wall wall = wall();

        assertTrue(wall.isStageMaterial(Material.ANCIENT_DEBRIS));
        assertTrue(wall.isStageMaterial(Material.OBSIDIAN));
        assertFalse(wall.isStageMaterial(Material.AIR),
                "после пробития проход — обычное пространство: взрывы там больше не гасим");
        assertFalse(wall.isStageMaterial(null));
        assertFalse(wall.isStageMaterial(Material.STONE));
    }

    /**
     * Награда — это команды от имени консоли: шалкер за стеной ставит другой
     * плагин. Проверяем, что подстановки доходят до команды целиком.
     */
    @Test
    void rewardSubstitutesPlaceholders() {
        BunkerSettings.Reward reward = new BunkerSettings.Reward(
                List.of("myevents spawn shulker %world% %x% %y% %z% %player%"));

        String resolved = reward.apply(reward.commands().get(0),
                "Qweyn", "anarchy", 0, 62, -17);

        assertEquals("myevents spawn shulker anarchy 0 62 -17 Qweyn", resolved);
    }

    @Test
    void emptyRewardIssInert() {
        assertTrue(BunkerSettings.Reward.NONE.isEmpty());
        assertFalse(new BunkerSettings.Reward(List.of("say hi")).isEmpty());
    }

    @Test
    void disabledBunkerIsInert() {
        assertFalse(BunkerSettings.DISABLED.enabled());
        assertFalse(BunkerSettings.DISABLED.isActiveIn(null));
        assertFalse(BunkerSettings.DISABLED.wall().isDefined());
        assertEquals(0, BunkerSettings.DISABLED.lastStageIndex());
        assertEquals(0.0, BunkerSettings.DISABLED.chanceFor("c4"), 1e-9);
    }
}
