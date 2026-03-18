package com.orbital.prog;

import com.orbital.util.EngineHint;
import com.orbital.prefilter.Prefilter;

/**
 * Metadata about a compiled program.
 */
public record Metadata(
    EngineHint hint,
    Prefilter prefilter,
    int groupCount,
    int maxOutputLength,
    boolean isWeighted,
    boolean isTransducer
) {
    public Metadata {
        if (hint == null) {
            throw new NullPointerException("Hint cannot be null");
        }
        if (prefilter == null) {
            throw new NullPointerException("Prefilter cannot be null");
        }
        if (groupCount < 0) {
            throw new IllegalArgumentException("Group count must be >= 0");
        }
        if (maxOutputLength < 0) {
            throw new IllegalArgumentException("Max output length must be >= 0");
        }
    }
}