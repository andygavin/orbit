package com.orbit.parse;

import com.orbit.util.SourceSpan;

/**
 * Anchor expression (start, end, word boundary, etc.).
 */
public record Anchor(AnchorType type) implements Expr {

    @Override
    public SourceSpan span() {
        return SourceSpan.fromLength(1);
    }
}