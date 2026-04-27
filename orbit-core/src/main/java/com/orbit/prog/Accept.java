package com.orbit.prog;

/**
 * Terminal accept state.
 */
public record Accept() implements Instr {
    @Override
    public int next() {
        throw new UnsupportedOperationException("Accept has no next instruction");
    }
}