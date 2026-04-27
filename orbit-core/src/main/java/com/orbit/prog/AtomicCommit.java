package com.orbit.prog;

/**
 * Atomic-commit instruction that enforces the no-backtrack commitment for atomic groups and
 * possessive quantifiers.
 *
 * <p>When executed by the backtracking engine, this instruction runs the continuation at
 * {@link #next()} as a sub-call. Failure in the continuation is converted to a committed-failure
 * sentinel so that {@link Split} nodes inside the preceding atomic body or possessive loop cannot
 * retry their fallback branches.
 *
 * <p>Two modes are supported, controlled by {@link #loopCommit}:
 * <ul>
 *   <li>{@code loopCommit=false} (atomic group mode): continuation failure returns
 *       {@code COMMITTED_FAIL}. {@link com.orbit.prog.UnionSplit} will downgrade
 *       {@code COMMITTED_FAIL} to {@code -1} when deciding whether to try an alternative
 *       arm, allowing outer unions to retry at the same position.</li>
 *   <li>{@code loopCommit=true} (possessive loop mode): continuation failure returns
 *       {@code LOOP_COMMITTED_FAIL}. {@link com.orbit.prog.UnionSplit} does <em>not</em>
 *       downgrade {@code LOOP_COMMITTED_FAIL}, so it propagates through union bodies
 *       intact. The {@link com.orbit.prog.PossessiveSplit} gate converts it to {@code -1}
 *       when the body consumed at least one character, allowing outer patterns to retry
 *       at a different start position.</li>
 * </ul>
 *
 * <p>In the PikeVM this instruction is treated as an epsilon jump to {@link #next()} because
 * possessive and atomic patterns are always routed to the {@code NEEDS_BACKTRACKER} engine.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record AtomicCommit(int next, boolean loopCommit) implements Instr {

  /**
   * Creates an atomic-commit instruction in atomic group mode ({@code loopCommit=false}).
   *
   * @param next the PC of the first instruction of the continuation; must be non-negative
   */
  public AtomicCommit(int next) {
    this(next, false);
  }
}
