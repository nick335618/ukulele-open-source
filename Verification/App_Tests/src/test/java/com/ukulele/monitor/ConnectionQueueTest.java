package com.ukulele.monitor;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionQueueTest {
    @Test public void allQueriesFitWithoutDisruptingInFlightOperation() {
        ControlCommandQueue queue = new ControlCommandQueue();
        assertTrue(queue.offer("STATE", false));
        queue.offerConnectionQueries();
        queue.offerConnectionQueries();
        for (String expected : new String[] {"STATE", "POWER:QUERY", "GESTURE_GET", "GESTURE_GET2",
                "SONG_INFO", "TOUCH_GET", "FX_GET", "CAPO_GET", "MODEL_GET", "XC_GET", "GUIDE_FADE_GET", "LM_GET"}) {
            assertEquals(expected, queue.poll());
        }
        assertTrue(queue.isEmpty());
    }

    @Test public void disconnectClearsOldQueriesAndNormalQueueStaysBounded() {
        ControlCommandQueue queue = new ControlCommandQueue();
        queue.offerConnectionQueries();
        queue.clear();
        assertTrue(queue.isEmpty());
        for (int i=0;i<8;i++) assertTrue(queue.offer("query" + i, false));
        assertFalse(queue.offer("query8", false));
    }
}
