package com.orbit.parse;

import com.orbit.util.SourceSpan;
import java.util.List;

/**
 * Alternation of expressions.
 */
public record Union(List<Expr> alternatives) implements Expr {
    public Union {
        if (alternatives == null) {
            throw new NullPointerException("Alternatives cannot be null");
        }
        if (alternatives.size() < 2) {
            throw new IllegalArgumentException("Union must have at least 2 alternatives");
        }
        for (int i = 0; i < alternatives.size(); i++) {
            if (alternatives.get(i) == null) {
                throw new NullPointerException("Alternative at index " + i + " cannot be null");
            }
        }
    }

    @Override
    public SourceSpan span() {
        SourceSpan[] spans = alternatives.stream()
            .map(Expr::span)
            .toArray(SourceSpan[]::new);
        return SourceSpan.combine(spans);
    }
}