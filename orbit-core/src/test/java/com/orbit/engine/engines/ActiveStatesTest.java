package com.orbit.engine.engines;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActiveStatesTest {

    @Test
    void newInstanceHasEmptySet() {
        ActiveStates a = new ActiveStates(10, 3);
        assertEquals(0, a.set.size());
    }

    @Test
    void slotTableInitialisedToMinValue() {
        ActiveStates a = new ActiveStates(4, 3);
        for (int state = 0; state < 4; state++) {
            for (int slot = 0; slot < 3; slot++) {
                assertEquals(Long.MIN_VALUE, a.getSlot(state, slot));
            }
        }
    }

    @Test
    void setAndGetSlot() {
        ActiveStates a = new ActiveStates(5, 2);
        a.setSlot(2, 0, 42L);
        a.setSlot(2, 1, 99L);
        assertEquals(42L, a.getSlot(2, 0));
        assertEquals(99L, a.getSlot(2, 1));
        // Adjacent states unaffected
        assertEquals(Long.MIN_VALUE, a.getSlot(1, 0));
        assertEquals(Long.MIN_VALUE, a.getSlot(3, 0));
    }

    @Test
    void copySlotsPreservesValues() {
        ActiveStates a = new ActiveStates(5, 3);
        a.setSlot(1, 0, 10L);
        a.setSlot(1, 1, 20L);
        a.setSlot(1, 2, 30L);

        a.copySlots(1, 3);

        assertEquals(10L, a.getSlot(3, 0));
        assertEquals(20L, a.getSlot(3, 1));
        assertEquals(30L, a.getSlot(3, 2));
        // Source unchanged
        assertEquals(10L, a.getSlot(1, 0));
    }

    @Test
    void sparseSetTracksActiveStates() {
        ActiveStates a = new ActiveStates(8, 2);
        a.set.add(3);
        a.set.add(6);
        assertTrue(a.set.contains(3));
        assertTrue(a.set.contains(6));
        assertFalse(a.set.contains(0));
        assertEquals(2, a.set.size());
    }

    @Test
    void clearSetLeavesSlotDataIntact() {
        // Clearing the SparseSet does not reset slotTable —
        // the next position step overwrites only the rows it activates.
        ActiveStates a = new ActiveStates(4, 2);
        a.set.add(2);
        a.setSlot(2, 0, 77L);
        a.set.clear();
        assertEquals(0, a.set.size());
        // Slot data not erased — this is intentional; callers overwrite before reading.
        assertEquals(77L, a.getSlot(2, 0));
    }

    @Test
    void strideIsolatesStates() {
        // With stride 3, state 0 occupies indices [0,2], state 1 occupies [3,5].
        // Writing to state 0 must not affect state 1 and vice versa.
        ActiveStates a = new ActiveStates(3, 3);
        a.setSlot(0, 2, 111L);
        a.setSlot(1, 0, 222L);
        assertEquals(111L, a.getSlot(0, 2));
        assertEquals(Long.MIN_VALUE, a.getSlot(0, 0));
        assertEquals(222L, a.getSlot(1, 0));
        assertEquals(Long.MIN_VALUE, a.getSlot(1, 2));
    }

    @Test
    void singleStateWithStride1() {
        ActiveStates a = new ActiveStates(1, 1);
        a.setSlot(0, 0, -1L);
        assertEquals(-1L, a.getSlot(0, 0));
    }
}
