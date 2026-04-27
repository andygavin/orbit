package com.orbit.parse;

import com.orbit.util.SourceSpan;

/**
 * Backreference expression.
 */
public record Backref(int groupIndex) implements Expr {
    public Backref {
        if (groupIndex < 1) {
            throw new IllegalArgumentException("Backref group index must be >= 1");
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.fromLength(String.valueOf(groupIndex).length() + 1);
    }
}