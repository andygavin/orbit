package com.orbital.parse;

/**
 * Epsilon (empty) expression.
 */
public record Epsilon() implements Expr {
    @Override
    public SourceSpan span() {
        return SourceSpan.EMPTY;
    }
}