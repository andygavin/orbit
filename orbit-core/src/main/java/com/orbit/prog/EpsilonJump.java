package com.orbital.prog;

/**
 * Epsilon transition (no character consumed).
 */
public record EpsilonJump(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}