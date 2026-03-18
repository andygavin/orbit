package com.orbital.prog;

/**
 * Backreference check.
 */
public record BackrefCheck(int groupIndex, int next) implements Instr {
    public BackrefCheck {
        if (groupIndex < 0) {
            throw new IllegalArgumentException("Group index must be >= 0");
        }
    }

    @Override
    public int next() {
        return next;
    }
}