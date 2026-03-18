package com.orbital.prog;

/**
 * Beginning of line assertion.
 */
public record BeginLine(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}