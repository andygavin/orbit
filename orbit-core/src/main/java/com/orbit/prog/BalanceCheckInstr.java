package com.orbit.prog;

import java.util.Objects;

/**
 * Test whether a named balance stack is non-empty, continuing on success or failing otherwise.
 *
 * <p>This instruction is used by {@code (?(Name)yes|no)} conditional patterns to test whether
 * the named balance stack has any entries. It is a pure read: no undo frame is needed.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param name the balance stack name; must not be null or blank
 * @param next the program counter to jump to on success (stack non-empty); must be >= 0
 */
public record BalanceCheckInstr(String name, int next) implements Instr {

  /** Creates a new {@code BalanceCheckInstr}. */
  public BalanceCheckInstr {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (next < 0) {
      throw new IllegalArgumentException("next must be >= 0, got " + next);
    }
  }

  @Override
  public int next() {
    return next;
  }
}
