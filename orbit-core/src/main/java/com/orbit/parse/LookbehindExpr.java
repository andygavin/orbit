package com.orbit.parse;

import java.util.Objects;

/**
 * AST node representing a lookbehind assertion ({@code (?<=body)} or {@code (?<!body)}).
 *
 * <p>A positive lookbehind ({@code positive=true}) succeeds when {@code body} matches the
 * text immediately before the current position without consuming any input.  A negative
 * lookbehind ({@code positive=false}) succeeds when {@code body} does NOT match.
 *
 * <p>Only fixed-length lookbehind bodies are supported.  Variable-length bodies cause a
 * {@link PatternSyntaxException} at parse time.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record LookbehindExpr(Expr body, boolean positive) implements Expr {

  /** Creates a new {@code LookbehindExpr}. */
  public LookbehindExpr {
    Objects.requireNonNull(body, "body must not be null");
  }
}
