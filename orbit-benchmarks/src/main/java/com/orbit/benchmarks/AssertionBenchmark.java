package com.orbit.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark: Orbit vs JDK {@code java.util.regex} for lookahead and lookbehind assertions.
 *
 * <p>Each scenario measures match throughput for an assertion-bearing pattern and, where
 * applicable, a non-assertion equivalent, so that the overhead introduced by zero-width
 * assertions can be isolated.
 *
 * <p>Variable-length lookbehind scenarios are included: Java 21 and Orbit both support
 * variable-length lookbehind, so JDK patterns are compiled for those scenarios.
 *
 * <p>Matchers are compiled once per trial in {@code @Setup} and {@code reset()} is called at
 * the start of every benchmark invocation so that only match execution is timed.
 *
 * <p>Instances are <em>not</em> thread-safe. The benchmark is run with {@code Scope.Benchmark},
 * which provides one instance per benchmark fork.
 *
 * <p>Run:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar AssertionBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class AssertionBenchmark {

  @Param({
    "lookahead_pos",
    "lookahead_neg",
    "lookbehind_pos",
    "lookbehind_neg",
    "lookahead_no_assert",
    "lookbehind_no_assert",
    "combined",
    "multiword_lookahead",
    "var_lookbehind"
  })
  private String scenario;

  private String input;

  private com.orbit.api.Matcher orbitMatcher;
  private java.util.regex.Matcher jdkMatcher;

  /**
   * Compiles Orbit and JDK patterns for the current {@code scenario} and stores the resulting
   * matchers and input string.
   */
  @Setup(Level.Trial)
  public void setup() {
    String pattern;

    switch (scenario) {
      case "lookahead_pos" -> {
        pattern = "\\w+(?=\\.)";
        input = "end of sentence. another one. last.";
      }
      case "lookahead_neg" -> {
        pattern = "\\d+(?!\\.\\d)";
        input = "3.14 42 2.718 100 0.5";
      }
      case "lookbehind_pos" -> {
        pattern = "(?<=@)\\w+";
        input = "user@example.com admin@host.org info@test.net";
      }
      case "lookbehind_neg" -> {
        pattern = "(?<!\\d)\\d{3}";
        input = "abc123 456def 12 789 1000";
      }
      case "lookahead_no_assert" -> {
        // Non-assertion equivalent of lookahead_pos — measures assertion overhead
        pattern = "\\w+[.]";
        input = "end of sentence. another one. last.";
      }
      case "lookbehind_no_assert" -> {
        // Group capture as non-assertion equivalent of lookbehind_pos
        pattern = "@(\\w+)";
        input = "user@example.com admin@host.org info@test.net";
      }
      case "combined" -> {
        pattern = "(?<=\\s)\\w+(?=\\s)";
        input = " hello world foo bar baz ";
      }
      case "multiword_lookahead" -> {
        pattern = "\\b\\w+\\b(?=\\s+\\w+\\s+\\w+)";
        input =
            "the quick brown fox jumps over the lazy dog "
                + "the quick brown fox jumps over the lazy dog "
                + "the quick brown";
      }
      case "var_lookbehind" -> {
        // Variable-length lookbehind — supported by Java 21+ and Orbit
        pattern = "(?<=\\bfoo\\s{1,3})bar";
        input = "foo bar foobar foo  bar foo   bar";
      }
      default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
    }

    orbitMatcher = com.orbit.api.Pattern.compile(pattern).matcher(input);
    jdkMatcher = java.util.regex.Pattern.compile(pattern).matcher(input);
  }

  /**
   * Orbit: find all matches in the input and consume each start position.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return number of matches found
   */
  @Benchmark
  public int orbitFindAll(Blackhole bh) {
    orbitMatcher.reset();
    int count = 0;
    while (orbitMatcher.find()) {
      bh.consume(orbitMatcher.start());
      count++;
    }
    return count;
  }

  /**
   * JDK {@code java.util.regex}: find all matches in the input and consume each start position.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return number of matches found
   */
  @Benchmark
  public int jdkFindAll(Blackhole bh) {
    jdkMatcher.reset();
    int count = 0;
    while (jdkMatcher.find()) {
      bh.consume(jdkMatcher.start());
      count++;
    }
    return count;
  }

  /**
   * Orbit: find the first match in the input and consume the start position.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return {@code true} if a match was found
   */
  @Benchmark
  public boolean orbitFindFirst(Blackhole bh) {
    orbitMatcher.reset();
    boolean found = orbitMatcher.find();
    if (found) {
      bh.consume(orbitMatcher.start());
    }
    return found;
  }

  /**
   * JDK {@code java.util.regex}: find the first match in the input and consume the start position.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return {@code true} if a match was found
   */
  @Benchmark
  public boolean jdkFindFirst(Blackhole bh) {
    jdkMatcher.reset();
    boolean found = jdkMatcher.find();
    if (found) {
      bh.consume(jdkMatcher.start());
    }
    return found;
  }
}
