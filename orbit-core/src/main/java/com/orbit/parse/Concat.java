package com.orbital.parse;

import java.util.List;

/**
 * Concatenation of expressions.
 */
public record Concat(List<Expr> parts) implements Expr {
    public Concat {
        if (parts == null) {
            throw new NullPointerException("Parts cannot be null");
        }
        if (parts.size() < 2) {
            throw new IllegalArgumentException("Concat must have at least 2 parts");
        }
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) == null) {
                throw new NullPointerException("Part at index " + i + " cannot be null");
            }
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.combine(parts);
    }
}