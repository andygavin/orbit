package com.orbit.prog;

import java.util.Objects;

/**
 * Positive lookbehind assertion supporting bounded variable-length patterns.
 *
 * <p>At the current position {@code pos}, scans all start positions {@code s} in the range
 * {@code [max(lbFrom, pos - maxLen), pos - minLen]} and executes {@code body} forward from
 * each {@code s}. If any sub-match spans exactly {@code [s, pos]}, execution continues at
 * {@code next}; otherwise the current thread is killed. No input is consumed.
 *
 * <p>Both fixed-length ({@code minLen == maxLen}) and bounded variable-length lookbehind
 * bodies are supported. Unbounded lookbehind ({@code maxLen == Integer.MAX_VALUE}) must be
 * rejected by the parser before a {@code LookbehindPos} instruction is created.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record LookbehindPos(Prog body, int minLen, int maxLen, int next) implements Instr {

  /**
   * Creates a new {@code LookbehindPos}, validating that {@code minLen} and {@code maxLen}
   * are non-negative and that {@code maxLen >= minLen}.
   *
   * @throws IllegalArgumentException if {@code minLen < 0} or {@code maxLen < minLen}
   */
  public LookbehindPos {
    Objects.requireNonNull(body, "body must not be null");
    if (minLen < 0) {
      throw new IllegalArgumentException("minLen must be >= 0");
    }
    if (maxLen < minLen) {
      throw new IllegalArgumentException("maxLen must be >= minLen");
    }
  }

  @Override
  public int next() {
    return next;
  }
}
