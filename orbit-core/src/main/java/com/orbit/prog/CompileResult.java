package com.orbit.prog;

import com.orbit.prefilter.Prefilter;

/**
 * Thread-safe compilation result.
 *
 * <p>This record is not serializable. The {@link com.orbit.api.Pattern} class serializes only
 * the pattern string and flags, and recompiles on deserialization; {@code CompileResult} is
 * never written to a serialization stream.
 */
public record CompileResult(
    Prog prog,
    Prefilter prefilter,
    Metadata metadata
) {
    public CompileResult {
        if (prog == null) {
            throw new NullPointerException("Prog cannot be null");
        }
        if (prefilter == null) {
            throw new NullPointerException("Prefilter cannot be null");
        }
        if (metadata == null) {
            throw new NullPointerException("Metadata cannot be null");
        }
    }
}