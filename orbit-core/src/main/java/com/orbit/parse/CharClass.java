package com.orbital.parse;

import java.util.List;

/**
 * Character class expression.
 */
public record CharClass(boolean negated, List<CharRange> ranges) implements Expr {
    public CharClass {
        if (ranges == null) {
            throw new NullPointerException("Ranges cannot be null");
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("CharClass must have at least one range");
        }
    }

    @Override
    public SourceSpan span() {
        // Approximate span based on ranges count
        return SourceSpan.fromLength(ranges.size() * 5);
    }
}