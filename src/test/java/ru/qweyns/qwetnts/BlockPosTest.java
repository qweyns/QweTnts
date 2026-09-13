package ru.qweyns.qwetnts;

import org.junit.jupiter.api.Test;
import ru.qweyns.qwetnts.raid.RaidBlockManager;

import static org.junit.jupiter.api.Assertions.*;

class BlockPosTest {

    @Test
    void parseRoundTrip() {
        var pos = new RaidBlockManager.BlockPos(12, 34, -56);
        assertEquals(pos, RaidBlockManager.BlockPos.parse(pos.serialize()));
    }

    @Test
    void parseInvalid() {
        assertNull(RaidBlockManager.BlockPos.parse("1,2"));
        assertNull(RaidBlockManager.BlockPos.parse("a,b,c"));
        assertNull(RaidBlockManager.BlockPos.parse(""));
    }
}
