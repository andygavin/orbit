package com.orbit.engine.engines;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparseSetTest {

    @Test
    void newSetIsEmpty() {
        SparseSet s = new SparseSet(10);
        assertEquals(0, s.size());
    }

    @Test
    void addAndContains() {
        SparseSet s = new SparseSet(10);
        s.add(3);
        s.add(7);
        assertTrue(s.contains(3));
        assertTrue(s.contains(7));
        assertFalse(s.contains(0));
        assertFalse(s.contains(9));
        assertEquals(2, s.size());
    }

    @Test
    void addIsDeduplicated() {
        SparseSet s = new SparseSet(10);
        s.add(5);
        s.add(5);
        assertEquals(1, s.size());
    }

    @Test
    void clearIsO1AndResetsContains() {
        SparseSet s = new SparseSet(10);
        s.add(1);
        s.add(2);
        s.add(3);
        s.clear();
        assertEquals(0, s.size());
        assertFalse(s.contains(1));
        assertFalse(s.contains(2));
        assertFalse(s.contains(3));
    }

    @Test
    void getReturnsInsertionOrder() {
        SparseSet s = new SparseSet(10);
        s.add(4);
        s.add(1);
        s.add(9);
        assertEquals(4, s.get(0));
        assertEquals(1, s.get(1));
        assertEquals(9, s.get(2));
    }

    @Test
    void addAfterClearWorksCorrectly() {
        SparseSet s = new SparseSet(10);
        s.add(2);
        s.add(5);
        s.clear();
        s.add(5);
        s.add(8);
        assertTrue(s.contains(5));
        assertTrue(s.contains(8));
        assertFalse(s.contains(2));
        assertEquals(2, s.size());
    }

    @Test
    void capacityBoundaryIds() {
        SparseSet s = new SparseSet(5);
        s.add(0);
        s.add(4);
        assertTrue(s.contains(0));
        assertTrue(s.contains(4));
        assertEquals(2, s.size());
    }

    @Test
    void multipleAddClearCycles() {
        SparseSet s = new SparseSet(8);
        for (int cycle = 0; cycle < 3; cycle++) {
            for (int i = 0; i < 8; i++) s.add(i);
            assertEquals(8, s.size());
            s.clear();
            assertEquals(0, s.size());
        }
    }
}
