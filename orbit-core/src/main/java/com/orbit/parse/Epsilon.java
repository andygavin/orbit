package com.orbit.parse;

import com.orbit.util.SourceSpan;

/**
 * Epsilon (empty) expression.
 */
public record Epsilon() implements Expr {
    @Override
    public SourceSpan span() {
        return SourceSpan.EMPTY;
    }
}