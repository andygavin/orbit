package com.orbit.parse;

import java.util.Objects;

/**
 * AST node representing a .NET balancing group operation.
 *
 * <p>Covers three syntactic forms:
 * <ul>
 *   <li>{@code (?<Name>pat)} — push: {@code pushName} non-null, {@code popName} null. This is a
 *       standard named capture extended to also push onto a named balance stack.</li>
 *   <li>{@code (?<-Name>pat)} — pop-only: {@code pushName} null, {@code popName} non-null. Pops
 *       the top of {@code popName} stack; fails if empty.</li>
 *   <li>{@code (?<NewName-OldName>pat)} — combined: both non-null. Pops {@code popName} and then
 *       pushes {@code pushName}.</li>
 * </ul>
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param pushName the name of the stack to push onto after a successful match; may be null for
 *                 pop-only forms
 * @param popName  the name of the stack to pop before executing the body; may be null for
 *                 push-only forms
 * @param body     the sub-expression to match; must not be null
 */
public record BalanceGroupExpr(String pushName, String popName, Expr body) implements Expr {

  /** Creates a new {@code BalanceGroupExpr}. */
  public BalanceGroupExpr {
    Objects.requireNonNull(body, "body must not be null");
    if (pushName == null && popName == null) {
      throw new IllegalArgumentException("At least one of pushName or popName must be non-null");
    }
    if (pushName != null && pushName.isBlank()) {
      throw new IllegalArgumentException("pushName must not be blank");
    }
    if (popName != null && popName.isBlank()) {
      throw new IllegalArgumentException("popName must not be blank");
    }
  }
}
