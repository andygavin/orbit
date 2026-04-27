package com.orbit.parse;

import com.orbit.parse.CharClass;
import com.orbit.parse.Anchor;
import com.orbit.parse.Backref;
import com.orbit.parse.Concat;
import com.orbit.parse.Epsilon;
import com.orbit.parse.Group;
import com.orbit.parse.Literal;
import com.orbit.parse.Pair;
import com.orbit.parse.Quantifier;
import com.orbit.parse.Union;
import com.orbit.util.SourceSpan;

/**
 * Sealed AST hierarchy representing regular expressions.
 * This is the immutable parse tree before analysis.
 */
public sealed interface Expr permits
    Literal, CharClass, Pair, Concat, Union,
    Quantifier, Group, Anchor, Epsilon, Backref,
    FlagExpr, LookaheadExpr, LookbehindExpr, AtomicGroup,
    BalanceGroupExpr, ConditionalExpr, KeepAssertion {

    /**
     * Returns the source location information for this expression.
     */
    default SourceSpan span() {
        return SourceSpan.EMPTY;
    }
}