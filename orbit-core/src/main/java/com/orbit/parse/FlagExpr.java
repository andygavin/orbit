package com.orbit.parse;

import com.orbit.util.PatternFlag;

import java.util.EnumSet;
import java.util.Objects;

/**
 * AST node that scopes a set of pattern flags over a body sub-expression.
 *
 * <p>Produced by the parser for both standalone inline flag groups ({@code (?i)}) and
 * flag-scoped non-capturing groups ({@code (?i:body)}).  In the standalone case the
 * {@code body} is a {@link Concat} of the atoms that follow the flag group to the end
 * of the enclosing group.  In the scoped case the {@code body} is exactly the group
 * contents.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record FlagExpr(EnumSet<PatternFlag> flags, Expr body) implements Expr {

  /** Creates a new {@code FlagExpr}, copying the flag set defensively. */
  public FlagExpr {
    Objects.requireNonNull(flags, "flags must not be null");
    Objects.requireNonNull(body, "body must not be null");
    flags = EnumSet.copyOf(flags.isEmpty() ? EnumSet.noneOf(PatternFlag.class) : flags);
  }
}
