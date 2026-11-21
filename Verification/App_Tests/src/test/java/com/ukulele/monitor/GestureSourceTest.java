package com.ukulele.monitor;

import org.junit.Test;
import static org.junit.Assert.*;

public class GestureSourceTest {
    private byte[] frame(int version,int desired,int actual,int result,int flags,int caps,int revision) {
        byte[] resultFrame={(byte)0xA4,(byte)version,(byte)desired,(byte)actual,(byte)result,(byte)flags,
            0x34,0x12,(byte)revision,(byte)(revision>>8),(byte)caps,0,0};
        for(int i=0;i<12;i++)resultFrame[12]^=resultFrame[i];
        return resultFrame;
    }

    @Test public void legacySwitchesRemainCompatible() {
        GestureState state=GestureState.parse(frame(1,3,3,2,7,3,1));
        assertNotNull(state);
        assertTrue(state.fresh());
        assertFalse(state.selectableSource());
        assertEquals("原品格判定",state.sourceName());
        assertNull(GestureState.parse(frame(1,7,7,2,7,3,1)));
    }

    @Test public void confirmedSourceUsesActualDeviceReadback() {
        GestureState pending=GestureState.parse(frame(2,7,3,1,9,7,65535));
        GestureState confirmed=GestureState.parse(frame(2,7,7,2,7,7,0));
        assertTrue(pending.waiting());
        assertTrue(pending.selectableSource());
        assertEquals("原品格判定",pending.sourceName());
        assertEquals("辅助电容揉弦",confirmed.sourceName());
        assertTrue(confirmed.acceptsAfter(pending));
        assertFalse(pending.acceptsAfter(confirmed));
    }

    @Test public void corruptOrContradictoryFramesAreRejected() {
        assertNull(GestureState.parse(frame(2,7,7,2,7,3,1)));
        assertNull(GestureState.parse(frame(2,7,3,2,7,7,1)));
        byte[] corrupted=frame(2,7,7,2,7,7,1);
        corrupted[6]^=1;
        assertNull(GestureState.parse(corrupted));
    }
}
