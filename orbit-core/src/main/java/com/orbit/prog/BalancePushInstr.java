package com.orbit.prog;

import java.util.Objects;

/**
 * Push the current input position onto a named balance stack.
 *
 * <p>When the engine backtracks past this instruction, the push must be undone (the stack
 * entry is removed).
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param name the balance stack name; must not be null or blank
 * @param next the program counter of the next instruction; must be >= 0
 */
public record BalancePushInstr(String name, int next) implements Instr {

  /** Creates a new {@code BalancePushInstr}. */
  public BalancePushInstr {
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
