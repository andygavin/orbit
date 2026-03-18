package com.orbital.util;

/**
 * Source code location information.
 */
public record SourceSpan(int start, int end) {
    public static final SourceSpan EMPTY = new SourceSpan(0, 0);

    public SourceSpan {
        if (start < 0) {
            throw new IllegalArgumentException("Start position cannot be negative");
        }
        if (end < start) {
            throw new IllegalArgumentException("End position must be >= start");
        }
    }

    public static SourceSpan fromLength(int length) {
        return new SourceSpan(0, length);
    }

    public static SourceSpan combine(SourceSpan... spans) {
        if (spans.length == 0) {
            return EMPTY;
        }
        int start = spans[0].start();
        int end = spans[0].end();
        for (int i = 1; i < spans.length; i++) {
            start = Math.min(start, spans[i].start());
            end = Math.max(end, spans[i].end());
        }
        return new SourceSpan(start, end);
    }
}