package com.orbit.parse;

/**
 * Character range within a char class.
 */
public record CharRange(char lo, char hi) {
    public CharRange {
        if (lo > hi) {
            throw new IllegalArgumentException("Low char must be <= high char");
        }
    }
}