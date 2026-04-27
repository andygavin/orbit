package com.orbit.hir;

import java.util.Collections;
import java.util.List;

/**
 * Set of literals extracted from an expression.
 */
public record LiteralSet(
    String prefix,
    String suffix,
    List<String> innerLiterals,
    boolean isExact
) {
    public static final LiteralSet EMPTY = new LiteralSet(
        "", "", Collections.emptyList(), false
    );

    public LiteralSet {
        if (prefix == null) {
            throw new NullPointerException("Prefix cannot be null");
        }
        if (suffix == null) {
            throw new NullPointerException("Suffix cannot be null");
        }
        if (innerLiterals == null) {
            throw new NullPointerException("Inner literals cannot be null");
        }
        for (String item : innerLiterals) {
            if (item == null) {
                throw new NullPointerException("Inner literals cannot contain null");
            }
        }
    }
}