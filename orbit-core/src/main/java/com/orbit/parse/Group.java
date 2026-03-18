package com.orbital.parse;

/**
 * Group expression with optional name and index.
 */
public record Group(Expr body, int index, String name) implements Expr {
    public Group {
        if (body == null) {
            throw new NullPointerException("Body expression cannot be null");
        }
        if (index < 0) {
            throw new IllegalArgumentException("Index must be >= 0");
        }
        if (name != null && name.isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be empty string");
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.combine(SourceSpan.fromLength(1), body.span(), SourceSpan.fromLength(1));
    }
}