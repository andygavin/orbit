package com.orbital.prog;

/**
 * Positive lookahead assertion.
 */
public record Lookahead(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}