package com.orbit.prog;

/**
 * End of text assertion.
 */
public record EndText(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}