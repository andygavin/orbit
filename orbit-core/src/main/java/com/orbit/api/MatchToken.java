package com.orbital.api;

/**
 * Token representing a matched text.
 */
public record MatchToken(String type, String value, int start, int end) implements Token {
    public MatchToken {
        if (type == null) {
            throw new NullPointerException("Type cannot be null");
        }
        if (value == null) {
            throw new NullPointerException("Value cannot be null");
        }
        if (start < 0) {
            throw new IllegalArgumentException("Start position cannot be negative");
        }
        if (end < start) {
            throw new IllegalArgumentException("End must be >= start");
        }
    }

    @Override
    public int start() {
        return start;
    }

    @Override
    public int end() {
        return end;
    }
}