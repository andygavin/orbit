package com.orbital.prog;

/**
 * End of line assertion.
 */
public record EndLine(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}