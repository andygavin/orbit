package com.orbit.parse;

import java.util.Objects;

/**
 * AST node representing an atomic group {@code (?>body)}.
 *
 * <p>An atomic group matches its body greedily and then <em>commits</em>: once the body
 * has been matched, the engine will not backtrack into it to try shorter alternatives.
 * This is semantically equivalent to a possessive quantifier wrapping the body with
 * exactly one required iteration.
 *
 * <p>Atomic groups are a .NET and PCRE regex feature; they require the backtracking engine
 * because the PikeVM cannot implement atomic commitment correctly.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record AtomicGroup(Expr body) implements Expr {

  /**
   * Creates a new {@code AtomicGroup} wrapping the given body expression.
   *
   * @param body the body expression to match atomically; must not be null
   * @throws NullPointerException if {@code body} is null
   */
  public AtomicGroup {
    Objects.requireNonNull(body, "body must not be null");
  }
}
