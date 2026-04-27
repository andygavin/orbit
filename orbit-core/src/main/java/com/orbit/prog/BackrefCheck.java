package com.orbit.prog;

/**
 * Backreference check instruction.
 *
 * <p>Matches the input at the current position against the text captured by group
 * {@code groupIndex} (0-based). When {@code caseInsensitive} is {@code true}, the
 * comparison is performed case-insensitively; otherwise it is case-sensitive.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record BackrefCheck(int groupIndex, boolean caseInsensitive, int next) implements Instr {

  /**
   * Creates a new {@code BackrefCheck}.
   *
   * @param groupIndex      0-based capture group index; must be >= 0
   * @param caseInsensitive {@code true} if the match should be case-insensitive
   * @param next            the program counter to advance to on success
   * @throws IllegalArgumentException if {@code groupIndex} is negative
   */
  public BackrefCheck {
    if (groupIndex < 0) {
      throw new IllegalArgumentException("groupIndex must be >= 0");
    }
  }

  @Override
  public int next() {
    return next;
  }
}
