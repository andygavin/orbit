package com.orbit.prog;

import java.util.Objects;

/**
 * Negative lookahead assertion.
 *
 * <p>At the current position, executes {@code body} as a sub-match starting at that
 * position.  If {@code body} does NOT match, execution continues at {@code next};
 * otherwise the current thread is killed.  No input is consumed.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record LookaheadNeg(Prog body, int next) implements Instr {

  /** Creates a new {@code LookaheadNeg}. */
  public LookaheadNeg {
    Objects.requireNonNull(body, "body must not be null");
  }

  @Override
  public int next() {
    return next;
  }
}
