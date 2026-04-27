package com.orbit.prog;

/**
 * Byte range match instruction for byte-oriented matching.
 */
public record ByteRangeMatch(byte lo, byte hi, int next) implements Instr {
    public ByteRangeMatch {
        if (lo > hi) {
            throw new IllegalArgumentException("lo must be <= hi");
        }
    }

    @Override
    public int next() {
        return next;
    }
}