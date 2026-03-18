package com.orbital.prog;

import java.io.Serializable;

/**
 * Thread-safe compilation result.
 */
public record CompileResult(
    Prog prog,
    Prefilter prefilter,
    Metadata metadata
) implements Serializable {
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