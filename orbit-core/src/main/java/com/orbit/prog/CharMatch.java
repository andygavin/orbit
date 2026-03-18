package com.orbital.prog;

/**
 * Matches a character in the range [lo, hi].
 */
public record CharMatch(char lo, char hi, int next) implements Instr {
    public CharMatch {
        if (lo > hi) {
            throw new IllegalArgumentException("lo must be <= hi");
        }
    }

    @Override
    public int next() {
        return next;
    }
}