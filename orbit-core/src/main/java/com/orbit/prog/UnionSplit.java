package com.orbit.prog;

/**
 * Non-deterministic split to two alternatives, emitted specifically for regex {@code |} union
 * alternation.
 *
 * <p>Semantically identical to {@link Split} for most engines. In
 * {@link com.orbit.engine.engines.BoundedBacktrackEngine}, this variant converts a
 * {@code COMMITTED_FAIL} result from the preferred branch into a normal failure ({@code -1})
 * before attempting the fallback branch. This prevents a possessive quantifier in one
 * alternative from suppressing the other alternative.
 *
 * <p>Plain {@link Split} is used for all other branching (character class range dispatch,
 * quantifier loops, etc.) and does NOT perform this conversion, preserving the
 * {@code COMMITTED_FAIL} propagation needed for possessive loop correctness.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record UnionSplit(int next1, int next2) implements Instr {

  /**
   * Throws {@link UnsupportedOperationException} because a {@code UnionSplit} has two possible
   * successor instructions.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public int next() {
    throw new UnsupportedOperationException("UnionSplit has two possible next instructions");
  }
}
