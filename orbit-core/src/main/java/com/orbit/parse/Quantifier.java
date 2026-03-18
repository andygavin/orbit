package com.orbital.parse;

import java.util.OptionalInt;

/**
 * Quantifier expression.
 */
public record Quantifier(Expr child, int min, OptionalInt max, boolean possessive) implements Expr {
    public Quantifier {
        if (child == null) {
            throw new NullPointerException("Child expression cannot be null");
        }
        if (min < 0) {
            throw new IllegalArgumentException("Min must be >= 0");
        }
        if (max.isPresent() && max.getAsInt() < min) {
            throw new IllegalArgumentException("Max must be >= min when present");
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.combine(child.span(), SourceSpan.fromLength(2));
    }
}