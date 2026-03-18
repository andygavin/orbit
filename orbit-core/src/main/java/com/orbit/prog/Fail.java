package com.orbital.prog;

/**
 * Fail state - always fails.
 */
public record Fail() implements Instr {
    @Override
    public int next() {
        throw new UnsupportedOperationException("Fail has no next instruction");
    }
}