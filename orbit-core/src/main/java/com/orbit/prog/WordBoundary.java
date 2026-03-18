package com.orbital.prog;

/**
 * Word boundary assertion.
 */
public record WordBoundary(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}