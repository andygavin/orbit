package com.orbital.prog;

/**
 * Emit output string in a transducer.
 */
public record TransOutput(String delta, int next) implements Instr {
    public TransOutput {
        if (delta == null) {
            throw new NullPointerException("Delta cannot be null");
        }
    }

    @Override
    public int next() {
        return next;
    }
}