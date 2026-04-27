package com.orbit.api;

import java.util.Objects;

/**
 * Token representing transformed output.
 */
public record OutputToken(String type, String value, int start, int end, String output) implements Token {
    public OutputToken {
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        Objects.requireNonNull(output, "Output cannot be null");
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