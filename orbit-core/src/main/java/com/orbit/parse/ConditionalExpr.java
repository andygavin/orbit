package com.orbit.parse;

import java.util.Objects;

/**
 * AST node representing a .NET conditional subpattern {@code (?(condition)yes|no)}.
 *
 * <p>The condition is represented as a sealed {@link Condition} hierarchy:
 * <ul>
 *   <li>{@link GroupIndexCondition} — condition is whether a numbered group participated.</li>
 *   <li>{@link GroupNameCondition} — condition is whether a named group or balance stack is
 *       non-empty.</li>
 *   <li>{@link LookaheadCondition} — condition is a lookahead (positive or negative).</li>
 * </ul>
 *
 * <p>The {@code noAlt} branch is {@link Epsilon} when the pattern uses the single-branch
 * form {@code (?(cond)yes)}.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param condition the condition to evaluate; must not be null
 * @param yes       the branch to take when the condition is satisfied; must not be null
 * @param noAlt     the branch to take when the condition is not satisfied; must not be null
 *                  (use {@link Epsilon} for no-else)
 */
public record ConditionalExpr(Condition condition, Expr yes, Expr noAlt) implements Expr {

  /** Creates a new {@code ConditionalExpr}. */
  public ConditionalExpr {
    Objects.requireNonNull(condition, "condition must not be null");
    Objects.requireNonNull(yes, "yes must not be null");
    Objects.requireNonNull(noAlt, "noAlt must not be null");
  }

  // -----------------------------------------------------------------------
  // Condition sealed hierarchy
  // -----------------------------------------------------------------------

  /**
   * Sealed base type for conditional subpattern conditions.
   *
   * <p>Instances are immutable and safe for use by multiple concurrent threads.
   */
  public sealed interface Condition
      permits GroupIndexCondition, GroupNameCondition, LookaheadCondition {}

  /**
   * Condition that tests whether a numbered capturing group participated in the current match.
   *
   * @param groupIndex the 1-based group index; must be >= 1
   */
  public record GroupIndexCondition(int groupIndex) implements Condition {
    /** Creates a new {@code GroupIndexCondition}. */
    public GroupIndexCondition {
      if (groupIndex < 1) {
        throw new IllegalArgumentException("groupIndex must be >= 1, got " + groupIndex);
      }
    }
  }

  /**
   * Condition that tests whether a named capturing group participated or whether a balance
   * stack is non-empty.
   *
   * <p>The engine resolves this at runtime: first it checks for a named capture group with
   * this name; if none exists it checks the balance stack.
   *
   * @param name the group or stack name; must not be null or blank
   */
  public record GroupNameCondition(String name) implements Condition {
    /** Creates a new {@code GroupNameCondition}. */
    public GroupNameCondition {
      Objects.requireNonNull(name, "name must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
    }
  }

  /**
   * Condition that tests a lookahead assertion at the current position.
   *
   * @param lookaheadBody the body of the lookahead; must not be null
   * @param positive      {@code true} for {@code (?=...)} (positive lookahead),
   *                      {@code false} for {@code (?!...)} (negative lookahead)
   */
  public record LookaheadCondition(Expr lookaheadBody, boolean positive) implements Condition {
    /** Creates a new {@code LookaheadCondition}. */
    public LookaheadCondition {
      Objects.requireNonNull(lookaheadBody, "lookaheadBody must not be null");
    }
  }
}
