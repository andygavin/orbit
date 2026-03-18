package com.orbital.parse;

import com.orbital.parse.CharClass;
import com.orbital.parse.Anchor;
import com.orbital.parse.Backref;
import com.orbital.parse.Concat;
import com.orbital.parse.Epsilon;
import com.orbital.parse.Group;
import com.orbital.parse.Literal;
import com.orbital.parse.Pair;
import com.orbital.parse.Quantifier;
import com.orbital.parse.Union;

/**
 * Sealed AST hierarchy representing regular expressions.
 * This is the immutable parse tree before analysis.
 */
public sealed interface Expr permits
    Literal, CharClass, Pair, Concat, Union,
    Quantifier, Group, Anchor, Epsilon, Backref {

    /**
     * Returns the source location information for this expression.
     */
    default SourceSpan span() {
        return SourceSpan.EMPTY;
    }
}