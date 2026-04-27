package com.orbit.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * ReDoS-protection benchmark: compares Orbit's bounded backtracker against JDK on patterns
 * that trigger exponential backtracking in a naive NFA engine.
 *
 * <p>The {@code stress} scenario ({@code (a+)+} on {@code "aaaaaaaaaa"}) has an exponential
 * number of ways to parse it but does match — both engines find the match quickly.
 *
 * <p>The {@code catastrophic} scenario ({@code (a+)+b} on a non-matching input of all
 * {@code a}'s) causes catastrophic backtracking in the JDK engine.  <strong>The JDK
 * benchmark method for this scenario is intentionally omitted</strong> — it would not
 * terminate in reasonable time.  Orbit's {@link com.orbit.api.MatchTimeoutException} ensures
 * it completes within the configured backtrack budget.
 *
 * <p>Run:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar StressBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class StressBenchmark {

  // --- stress: (a+)+ on a matching input ---

  private com.orbit.api.Matcher   orbitStressMatcher;
  private java.util.regex.Matcher jdkStressMatcher;
  private com.google.re2j.Matcher re2jStressMatcher;

  // --- catastrophic: (a+)+b on a failing input (JDK hangs, so no JDK method below) ---

  private com.orbit.api.Matcher   orbitCatastrophicMatcher;
  private com.google.re2j.Matcher re2jCatastrophicMatcher;

  @Setup(Level.Trial)
  public void setup() {
    String stressPattern = "(a+)+";
    String stressInput   = "a".repeat(20);   // matching input, many parse trees

    String catPattern = "(a+)+b";
    String catInput   = "a".repeat(20);      // non-matching — no 'b' at end

    orbitStressMatcher = com.orbit.api.Pattern.compile(stressPattern).matcher(stressInput);
    jdkStressMatcher   = java.util.regex.Pattern.compile(stressPattern).matcher(stressInput);
    re2jStressMatcher  = com.google.re2j.Pattern.compile(stressPattern).matcher(stressInput);

    orbitCatastrophicMatcher = com.orbit.api.Pattern.compile(catPattern).matcher(catInput);
    re2jCatastrophicMatcher  = com.google.re2j.Pattern.compile(catPattern).matcher(catInput);
  }

  // ---------------------------------------------------------------------------
  // stress: (a+)+ — matching input
  // ---------------------------------------------------------------------------

  @Benchmark
  public boolean orbitStress(Blackhole bh) {
    orbitStressMatcher.reset();
    boolean found = orbitStressMatcher.find();
    if (found) bh.consume(orbitStressMatcher.start());
    return found;
  }

  @Benchmark
  public boolean jdkStress(Blackhole bh) {
    jdkStressMatcher.reset();
    boolean found = jdkStressMatcher.find();
    if (found) bh.consume(jdkStressMatcher.start());
    return found;
  }

  @Benchmark
  public boolean re2jStress(Blackhole bh) {
    re2jStressMatcher.reset();
    boolean found = re2jStressMatcher.find();
    if (found) bh.consume(re2jStressMatcher.start());
    return found;
  }

  // ---------------------------------------------------------------------------
  // catastrophic: (a+)+b — non-matching input (JDK not included: does not terminate)
  // ---------------------------------------------------------------------------

  /**
   * Orbit: {@code (a+)+b} on {@code "aaa...a"} (no trailing {@code b}).
   *
   * <p>The bounded backtracker exhausts the budget and wraps a
   * {@link com.orbit.engine.MatchTimeoutException} in a {@link RuntimeException}; this is
   * caught and treated as a non-match.
   * The important property is that it returns in bounded time.
   */
  @Benchmark
  public boolean orbitCatastrophic(Blackhole bh) {
    orbitCatastrophicMatcher.reset();
    try {
      boolean found = orbitCatastrophicMatcher.find();
      if (found) bh.consume(orbitCatastrophicMatcher.start());
      return found;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof com.orbit.engine.MatchTimeoutException) {
        return false;  // bounded: ReDoS protection triggered
      }
      throw e;
    }
  }

  /**
   * re2j: {@code (a+)+b} on a non-matching input.
   *
   * <p>re2j uses NFA simulation so this terminates quickly regardless of input size.
   * Included to show the reference point for linear-time engines on catastrophic patterns.
   */
  @Benchmark
  public boolean re2jCatastrophic(Blackhole bh) {
    re2jCatastrophicMatcher.reset();
    boolean found = re2jCatastrophicMatcher.find();
    if (found) bh.consume(re2jCatastrophicMatcher.start());
    return found;
  }
}
