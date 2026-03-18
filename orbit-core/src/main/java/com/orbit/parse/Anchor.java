package com.orbital.parse;

/**
 * Anchor expression (start, end, word boundary, etc.).
 */
public record Anchor(AnchorType type) implements Expr {

    @Override
    public SourceSpan span() {
        return SourceSpan.fromLength(1);
    }
}