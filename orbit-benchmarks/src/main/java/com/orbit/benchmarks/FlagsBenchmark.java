package com.orbit.benchmarks;

import com.orbit.util.PatternFlag;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark: Orbit vs JDK {@code java.util.regex} for patterns compiled with different
 * {@link PatternFlag} combinations.
 *
 * <p>Measures how flag combinations (case-insensitivity, multiline anchors, dotall, unix-lines)
 * affect engine selection and match throughput.  Each scenario pairs an equivalent Orbit flag
 * set with the corresponding JDK {@code Pattern} flag bitmask.
 *
 * <p>Matchers are compiled once per trial in {@code @Setup} and {@code reset()} is called at
 * the start of every benchmark invocation so that only match execution is timed.
 *
 * <p>Instances are <em>not</em> thread-safe. The benchmark is run with {@code Scope.Benchmark},
 * which provides one instance per benchmark fork.
 *
 * <p>Run:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar FlagsBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class FlagsBenchmark {

  private static final String MULTILINE_INPUT =
      "foo bar\nbaz qux\nalpha beta\ndelta gamma\nepsilon zeta\n"
          + "eta theta\niota kappa\nlambda mu\nnu xi\nomicron pi";

  @Param({
    "ci_literal",
    "ci_word_class",
    "multiline_start",
    "multiline_end",
    "dotall",
    "dotall_greedy",
    "unix_lines_dot",
    "ci_multiline"
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
    PatternFlag[] orbitFlags;
    int jdkFlags;

    switch (scenario) {
      case "ci_literal" -> {
        pattern = "hello world";
        input = "Hello World, HELLO WORLD, hello world";
        orbitFlags = new PatternFlag[]{PatternFlag.CASE_INSENSITIVE};
        jdkFlags = java.util.regex.Pattern.CASE_INSENSITIVE;
      }
      case "ci_word_class" -> {
        pattern = "\\bJava\\b";
        input = "Java java JAVA javaScript";
        orbitFlags = new PatternFlag[]{PatternFlag.CASE_INSENSITIVE};
        jdkFlags = java.util.regex.Pattern.CASE_INSENSITIVE;
      }
      case "multiline_start" -> {
        pattern = "^\\w+";
        input = MULTILINE_INPUT;
        orbitFlags = new PatternFlag[]{PatternFlag.MULTILINE};
        jdkFlags = java.util.regex.Pattern.MULTILINE;
      }
      case "multiline_end" -> {
        pattern = "\\w+$";
        input = MULTILINE_INPUT;
        orbitFlags = new PatternFlag[]{PatternFlag.MULTILINE};
        jdkFlags = java.util.regex.Pattern.MULTILINE;
      }
      case "dotall" -> {
        pattern = "<body>.*</body>";
        input = "<body>\nline1\nline2\nline3\n</body>";
        orbitFlags = new PatternFlag[]{PatternFlag.DOTALL};
        jdkFlags = java.util.regex.Pattern.DOTALL;
      }
      case "dotall_greedy" -> {
        pattern = "<[^>]*>.*?</[^>]*>";
        input = "<div>\n  <p>text</p>\n  <span>more</span>\n</div>";
        orbitFlags = new PatternFlag[]{PatternFlag.DOTALL};
        jdkFlags = java.util.regex.Pattern.DOTALL;
      }
      case "unix_lines_dot" -> {
        pattern = "a.b";
        input = "a\rb a\nb a\u0085b a\u2028b";
        orbitFlags = new PatternFlag[]{PatternFlag.UNIX_LINES};
        jdkFlags = java.util.regex.Pattern.UNIX_LINES;
      }
      case "ci_multiline" -> {
        pattern = "^error";
        input = "Info: ok\nError: bad\nERROR: worse\nWARNING: meh";
        orbitFlags = new PatternFlag[]{PatternFlag.CASE_INSENSITIVE, PatternFlag.MULTILINE};
        jdkFlags = java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE;
      }
      default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
    }

    orbitMatcher =
        com.orbit.api.Pattern.compile(pattern, orbitFlags).matcher(input);
    jdkMatcher =
        java.util.regex.Pattern.compile(pattern, jdkFlags).matcher(input);
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
   * Orbit: attempt a full-input match and consume the result.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return {@code true} if the entire input matches the pattern
   */
  @Benchmark
  public boolean orbitMatches(Blackhole bh) {
    boolean result = orbitMatcher.reset().matches();
    bh.consume(result);
    return result;
  }

  /**
   * JDK {@code java.util.regex}: attempt a full-input match and consume the result.
   *
   * @param bh JMH blackhole that prevents dead-code elimination
   * @return {@code true} if the entire input matches the pattern
   */
  @Benchmark
  public boolean jdkMatches(Blackhole bh) {
    jdkMatcher.reset();
    boolean result = jdkMatcher.matches();
    bh.consume(result);
    return result;
  }
}
