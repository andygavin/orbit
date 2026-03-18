package com.orbital.prog;

/**
 * Non-deterministic split to two alternatives.
 */
public record Split(int next1, int next2) implements Instr {
    @Override
    public int next() {
        throw new UnsupportedOperationException("Split has two possible next instructions");
    }
}