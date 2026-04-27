package com.orbit.prog;

/**
 * Possessive (atomic) split instruction that commits to its first branch without
 * offering the second branch as a backtracking choice point.
 *
 * <p>In the backtracking engine, {@code next1} is executed immediately and {@code next2} is
 * <em>never</em> pushed as a choice frame. This gives atomic-group semantics: once the
 * possessive body has consumed input, it cannot be unwound by backtracking.
 *
 * <p>In the PikeVM, this instruction is treated identically to {@link Split}: both branches
 * are explored as concurrent threads. Because patterns containing possessive quantifiers are
 * always routed through {@code NEEDS_BACKTRACKER}, the PikeVM's behavior on this instruction
 * does not affect correctness in practice.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record PossessiveSplit(int next1, int next2) implements Instr {

  /**
   * Throws {@link UnsupportedOperationException}; possessive splits have two possible
   * continuation program counters.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public int next() {
    throw new UnsupportedOperationException("PossessiveSplit has two possible next instructions");
  }
}
