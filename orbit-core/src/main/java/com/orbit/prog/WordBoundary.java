package com.orbit.prog;

/**
 * Word boundary assertion instruction, representing either {@code \b} or {@code \B}.
 *
 * <p>When {@code negated} is {@code false}, the assertion passes when the current position in the
 * input is at a word boundary (a transition between a word character and a non-word character, or
 * vice versa). When {@code negated} is {@code true}, the assertion passes when the position is
 * <em>not</em> at a word boundary.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param next        the program counter of the next instruction to execute if the assertion passes
 * @param negated     {@code false} for {@code \b}, {@code true} for {@code \B}
 * @param unicodeCase {@code true} when the pattern was compiled with Unicode word-character
 *                    semantics active at this instruction (i.e. {@code (?u)} or
 *                    {@link com.orbit.util.PatternFlag#UNICODE_CASE} was in effect)
 */
public record WordBoundary(int next, boolean negated, boolean unicodeCase) implements Instr {
  @Override
  public int next() {
    return next;
  }
}