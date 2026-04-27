package com.orbit.transducer;

/**
 * A single arc in a finite-state transducer graph.
 *
 * <p>Each arc carries an input label ({@code ilabel}), an output label ({@code olabel}), and
 * the ID of the target state ({@code nextstate}). Labels are raw Unicode BMP code points in the
 * range 1–65535, or the sentinel value {@link #EPS} (0) to denote the absence of a label.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record Arc(int ilabel, int olabel, int nextstate) {

  /** Epsilon sentinel: the absence of a label on either side. */
  static final int EPS = 0;

  /**
   * Returns true if this arc consumes no input and emits no output.
   *
   * <p>Only these arcs are removed by {@code rmEpsilon}. An arc with one non-EPS label is
   * not a true epsilon and is preserved.
   *
   * @return true when both {@code ilabel} and {@code olabel} equal {@link #EPS}
   */
  boolean isTrueEpsilon() {
    return ilabel == EPS && olabel == EPS;
  }

  /**
   * Returns true if this arc consumes no input but emits output.
   *
   * <p>Insertion arcs survive {@code rmEpsilon} because they contribute observable output.
   *
   * @return true when {@code ilabel} is {@link #EPS} and {@code olabel} is not
   */
  boolean isInsertion() {
    return ilabel == EPS && olabel != EPS;
  }

  /**
   * Returns true if this arc consumes input.
   *
   * <p>The {@code olabel} may or may not be {@link #EPS}.
   *
   * @return true when {@code ilabel} is not {@link #EPS}
   */
  boolean isConsuming() {
    return ilabel != EPS;
  }
}
