package com.orbital.prog;

/**
 * Beginning of text assertion.
 */
public record BeginText(int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}