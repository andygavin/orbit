package com.orbit.engine;

/**
 * Thrown by {@link com.orbit.engine.engines.BoundedBacktrackEngine} when the number of
 * operations performed during a single match invocation exceeds the configured backtrack
 * budget.
 *
 * <p>This exception provides ReDoS protection: a pattern that exhibits catastrophic
 * backtracking on a given input will be interrupted rather than running indefinitely.
 *
 * <p>Instances are safe to catch and handle; the engine is left in a clean state after
 * throwing.
 */
public class MatchTimeoutException extends Exception {

  private final String patternString;
  private final int inputLength;
  private final int budget;

  /**
   * Creates a new {@code MatchTimeoutException} with structured diagnostic context.
   *
   * @param patternString the compiled pattern string, or {@code "<unknown>"} if unavailable;
   *                      must not be null
   * @param inputLength   the length of the input string being matched; must be >= 0
   * @param budget        the backtrack budget that was exceeded; must be > 0
   */
  public MatchTimeoutException(String patternString, int inputLength, int budget) {
    super(buildMessage(patternString, inputLength, budget));
    this.patternString = patternString;
    this.inputLength = inputLength;
    this.budget = budget;
  }

  /**
   * Returns the pattern string associated with the timed-out match.
   *
   * @return the pattern string; never null
   */
  public String patternString() {
    return patternString;
  }

  /**
   * Returns the length of the input against which the match was attempted.
   *
   * @return the input length; >= 0
   */
  public int inputLength() {
    return inputLength;
  }

  /**
   * Returns the backtrack budget that was exceeded.
   *
   * @return the budget; > 0
   */
  public int budget() {
    return budget;
  }

  private static String buildMessage(String patternString, int inputLength, int budget) {
    return String.format(
        "Backtrack budget of %d operations exceeded for pattern '%s' on input of length %d",
        budget, patternString, inputLength);
  }
}
