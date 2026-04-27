package com.orbit.prog;

/**
 * Byte match instruction for byte-oriented matching.
 */
public record ByteMatch(byte value, int next) implements Instr {
    @Override
    public int next() {
        return next;
    }
}