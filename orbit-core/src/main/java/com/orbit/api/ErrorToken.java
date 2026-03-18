package com.orbital.api;

/**
 * Token representing an error.
 */
public record ErrorToken(String message, int start, int end) implements Token {
    public ErrorToken {
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
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