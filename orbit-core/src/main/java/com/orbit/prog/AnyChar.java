package com.orbital.prog;

/**
 * Match any character.
 */
public record AnyChar(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}