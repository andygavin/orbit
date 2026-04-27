package com.orbit.prog;

/**
 * Epsilon instruction that resets the reported match start to the current input position.
 *
 * <p>Emitted by the compiler for a {@code \K} (keep assertion) expression. When executed,
 * the match start reported by {@code group(0)} and {@code start()} is updated to the current
 * position; input consumed before this instruction is excluded from the reported result.
 *
 * <p>This instruction is semantically equivalent to a zero-width assertion: it does not
 * consume any input characters.
 *
 * <p>Patterns containing {@code ResetMatchStart} require PikeVM or backtracking semantics
 * and are classified as {@link com.orbit.util.EngineHint#PIKEVM_ONLY} by the analysis visitor.
 *
 * @param next the program counter of the next instruction to execute
 */
public record ResetMatchStart(int next) implements Instr {}
