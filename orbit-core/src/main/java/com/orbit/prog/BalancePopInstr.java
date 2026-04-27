package com.orbit.prog;

import java.util.Objects;

/**
 * Pop the top entry from a named balance stack, failing if the stack is empty.
 *
 * <p>When the engine backtracks past this instruction, the pop must be undone (the entry is
 * restored to the top of the stack).
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param name the balance stack name; must not be null or blank
 * @param next the program counter of the next instruction on success; must be >= 0
 */
public record BalancePopInstr(String name, int next) implements Instr {

  /** Creates a new {@code BalancePopInstr}. */
  public BalancePopInstr {
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
