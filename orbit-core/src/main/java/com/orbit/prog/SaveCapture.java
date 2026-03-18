package com.orbital.prog;

/**
 * Save capture group boundary (start or end).
 */
public record SaveCapture(int groupIndex, boolean isStart, int next) implements Instr {
    public SaveCapture {
        if (groupIndex < 0) {
            throw new IllegalArgumentException("Group index must be >= 0");
        }
    }

    @Override
    public int next() {
        return next;
    }
}