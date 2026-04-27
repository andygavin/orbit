package com.orbit.parse;

import com.orbit.util.SourceSpan;

/**
 * Literal string expression.
 */
public record Literal(String value) implements Expr {
    public Literal {
        if (value == null) {
            throw new NullPointerException("Literal value cannot be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Literal value cannot be empty");
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.fromLength(value.length());
    }
}