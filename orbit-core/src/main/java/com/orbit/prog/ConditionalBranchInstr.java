package com.orbit.prog;

import java.util.Objects;

/**
 * Conditional branch instruction for .NET {@code (?(condition)yes|no)} subpatterns.
 *
 * <p>At runtime, the engine evaluates the condition and jumps to {@code yesPC} if the
 * condition is satisfied or {@code noPC} otherwise. The condition itself does not consume
 * any input; only the taken branch does.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param kind     the kind of condition to test; must not be null
 * @param refIndex the 1-based group index for {@link Kind#GROUP_INDEX}; ignored for other kinds
 * @param refName  the group or stack name for {@link Kind#GROUP_NAME} and
 *                 {@link Kind#BALANCE_STACK}; null for other kinds
 * @param lookaheadBody the compiled sub-program for {@link Kind#LOOKAHEAD_POS} and
 *                      {@link Kind#LOOKAHEAD_NEG}; null for other kinds
 * @param yesPC    the program counter of the yes-branch
 * @param noPC     the program counter of the no-branch
 */
public record ConditionalBranchInstr(
    Kind kind,
    int refIndex,
    String refName,
    Prog lookaheadBody,
    int yesPC,
    int noPC) implements Instr {

  /**
   * The kind of condition tested by a {@link ConditionalBranchInstr}.
   */
  public enum Kind {
    /** Condition: numbered group {@code refIndex} participated in the match. */
    GROUP_INDEX,
    /** Condition: named group {@code refName} participated in the match. */
    GROUP_NAME,
    /** Condition: balance stack named {@code refName} is non-empty. */
    BALANCE_STACK,
    /** Condition: positive lookahead using {@code lookaheadBody} matches at current position. */
    LOOKAHEAD_POS,
    /** Condition: negative lookahead using {@code lookaheadBody} does NOT match. */
    LOOKAHEAD_NEG,
  }

  /** Creates a new {@code ConditionalBranchInstr}. */
  public ConditionalBranchInstr {
    Objects.requireNonNull(kind, "kind must not be null");
  }

  /**
   * Returns {@code yesPC}, satisfying the {@link Instr#next()} contract. Callers that need
   * the full branch table must inspect {@link #yesPC()} and {@link #noPC()} directly.
   *
   * @return {@code yesPC}
   */
  @Override
  public int next() {
    return yesPC;
  }
}
