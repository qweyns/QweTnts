package ru.qweyns.qwetnts.dynamite;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.dynamite.DynamiteType.TransformChain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цепочка деградации: та же механика, что у стены бункера, только для
 * обычных блоков мира.
 *
 * <p>Проверяется главное: что блок снимает ровно одну ступень за раз, что
 * последняя ступень дальше не деградирует и что чужие материалы цепочка не
 * трогает — иначе взрыв начал бы портить всё подряд.</p>
 */
class TransformChainTest {

    private static final List<Material> STAGES =
            List.of(Material.ANCIENT_DEBRIS, Material.CRYING_OBSIDIAN,
                    Material.OBSIDIAN, Material.AIR);

    private static final TransformChain CHAIN = new TransformChain(STAGES, 100);

    @Test
    void degradesOneStageAtATime() {
        assertEquals(Material.CRYING_OBSIDIAN, CHAIN.next(Material.ANCIENT_DEBRIS));
        assertEquals(Material.OBSIDIAN, CHAIN.next(Material.CRYING_OBSIDIAN));
        assertEquals(Material.AIR, CHAIN.next(Material.OBSIDIAN));
    }

    @Test
    void lastStageHasNowhereToGo() {
        assertNull(CHAIN.next(Material.AIR),
                "последняя ступень — дыра: деградировать дальше некуда, блок исчезает");
        assertFalse(CHAIN.degrades(Material.AIR));
    }

    @Test
    void ignoresMaterialsOutsideChain() {
        assertNull(CHAIN.next(Material.STONE));
        assertNull(CHAIN.next(Material.END_STONE));
        assertFalse(CHAIN.degrades(Material.STONE));
        assertNull(CHAIN.next(null));
    }

    @Test
    void needsAtLeastTwoStages() {
        assertFalse(TransformChain.NONE.isEnabled(), "пустая цепочка выключена");
        assertFalse(new TransformChain(List.of(Material.OBSIDIAN), 100).isEnabled(),
                "одна ступень — это не цепочка, а подмена материала");
        assertTrue(CHAIN.isEnabled());
    }

    @Test
    void zeroChanceTurnsChainOff() {
        TransformChain dead = new TransformChain(
                List.of(Material.ANCIENT_DEBRIS, Material.AIR), 0);

        assertFalse(dead.isEnabled());
        assertNull(dead.next(Material.ANCIENT_DEBRIS),
                "шанс 0 — значит цепочка не работает вовсе, а не «иногда»");
    }
}
