package com.orbit.parse;

import java.util.Objects;

/**
 * AST node representing a lookahead assertion ({@code (?=body)} or {@code (?!body)}).
 *
 * <p>A positive lookahead ({@code positive=true}) succeeds when {@code body} matches at
 * the current position without consuming any input.  A negative lookahead
 * ({@code positive=false}) succeeds when {@code body} does NOT match.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record LookaheadExpr(Expr body, boolean positive) implements Expr {

  /** Creates a new {@code LookaheadExpr}. */
  public LookaheadExpr {
    Objects.requireNonNull(body, "body must not be null");
  }
}
