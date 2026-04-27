package com.orbit.parse;

/**
 * Validates that a parsed expression tree is compatible with the RE2 engine subset.
 *
 * <p>RE2 does not support backreferences, lookahead, lookbehind, atomic groups,
 * balancing groups, conditionals, or possessive quantifiers. This validator walks
 * the expression tree and throws {@link PatternSyntaxException} immediately on
 * encountering any such construct.
 *
 * <p>Instances are not needed; use the single static entry point {@link #validate}.
 *
 * <p>This class is stateless and safe for use by multiple concurrent threads.
 */
public final class Re2Validator {

  private Re2Validator() {}

  /**
   * Validates that {@code expr} contains no constructs forbidden under RE2_COMPAT mode.
   *
   * <p>Throws {@link PatternSyntaxException} on the first forbidden construct encountered
   * during a depth-first walk of the expression tree. The exception carries {@code source}
   * as the pattern string and {@code -1} as the error index.
   *
   * @param expr   the expression tree to validate; must not be null
   * @param source the original pattern string, used as the pattern field in any thrown
   *               exception; must not be null
   * @throws PatternSyntaxException if {@code expr} contains a construct not supported in
   *                                RE2_COMPAT mode
   * @throws NullPointerException   if {@code expr} or {@code source} is null
   */
  public static void validate(Expr expr, String source) throws PatternSyntaxException {
    java.util.Objects.requireNonNull(expr, "expr must not be null");
    java.util.Objects.requireNonNull(source, "source must not be null");
    walk(expr, source);
  }

  private static void walk(Expr expr, String source) throws PatternSyntaxException {
    switch (expr) {

      case Backref ignored ->
          throw new PatternSyntaxException(
              "backreferences are not supported in RE2_COMPAT mode", source, -1);

      case LookaheadExpr ignored ->
          throw new PatternSyntaxException(
              "lookahead is not supported in RE2_COMPAT mode", source, -1);

      case LookbehindExpr ignored ->
          throw new PatternSyntaxException(
              "lookbehind is not supported in RE2_COMPAT mode", source, -1);

      case AtomicGroup ignored ->
          throw new PatternSyntaxException(
              "atomic groups are not supported in RE2_COMPAT mode", source, -1);

      case BalanceGroupExpr ignored ->
          throw new PatternSyntaxException(
              "balancing groups are not supported in RE2_COMPAT mode", source, -1);

      case ConditionalExpr ignored ->
          throw new PatternSyntaxException(
              "conditionals are not supported in RE2_COMPAT mode", source, -1);

      case Quantifier q -> {
        if (q.possessive()) {
          throw new PatternSyntaxException(
              "possessive quantifiers are not supported in RE2_COMPAT mode", source, -1);
        }
        walk(q.child(), source);
      }

      case Concat c -> {
        for (Expr part : c.parts()) {
          walk(part, source);
        }
      }

      case Union u -> {
        for (Expr alt : u.alternatives()) {
          walk(alt, source);
        }
      }

      case Group g -> walk(g.body(), source);

      case FlagExpr fe -> walk(fe.body(), source);

      case KeepAssertion ignored -> {
        // KeepAssertion has no body to recurse into
      }

      // Terminal nodes: Literal, CharClass, Anchor, Epsilon, Pair — always valid
      default -> { }
    }
  }
}
