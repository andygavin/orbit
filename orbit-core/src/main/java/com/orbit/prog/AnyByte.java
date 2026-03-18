package com.orbital.prog;

/**
 * Match any byte.
 */
public record AnyByte(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}