package com.orbital.prog;

/**
 * Negative lookahead assertion.
 */
public record LookaheadNeg(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}