package com.orbit.parse;

import com.orbit.util.SourceSpan;
import java.util.OptionalInt;

/**
 * Represents a quantified sub-expression with optional laziness or possessive semantics.
 *
 * <p>The {@code lazy} and {@code possessive} fields are mutually exclusive: if
 * {@code possessive} is {@code true}, {@code lazy} must be {@code false}. A greedy
 * quantifier has both fields {@code false}.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record Quantifier(Expr child, int min, OptionalInt max, boolean possessive, boolean lazy)
    implements Expr {

  /** Creates a Quantifier, validating all invariants. */
  public Quantifier {
    if (child == null) {
      throw new NullPointerException("child must not be null");
    }
    if (min < 0) {
      throw new IllegalArgumentException("min must be >= 0");
    }
    if (max.isPresent() && max.getAsInt() < min) {
      throw new IllegalArgumentException("max must be >= min when present");
    }
    if (possessive && lazy) {
      throw new IllegalArgumentException("possessive and lazy are mutually exclusive");
    }
  }

  /**
   * Creates a greedy quantifier (neither lazy nor possessive).
   *
   * @param child      the quantified sub-expression; must not be null
   * @param min        the minimum repetition count; must be >= 0
   * @param max        the maximum repetition count; empty means unbounded
   * @param possessive {@code true} for possessive (atomic) semantics
   */
  public Quantifier(Expr child, int min, OptionalInt max, boolean possessive) {
    this(child, min, max, possessive, false);
  }

  @Override
  public SourceSpan span() {
    return SourceSpan.combine(child.span(), SourceSpan.fromLength(2));
  }
}
