package com.orbit.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Latency benchmark: Orbit vs JDK {@code java.util.regex} vs re2j.
 *
 * <p>Measures average time (nanoseconds) for a single {@code find()} or {@code matches()}
 * call on a short, realistic input (~100–200 characters).  Complements
 * {@link ThroughputBenchmark}, which measures scan throughput on large corpora.
 *
 * <p>Matchers are created once in {@code @Setup} and {@code reset()} is called inside the
 * {@code @Benchmark} method so only the match execution is timed — not compilation or
 * {@code Matcher} object allocation.
 *
 * <p>Run:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar PerformanceBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class PerformanceBenchmark {

  @Param({"literal", "phone", "email", "alternation", "ip_address"})
  private String scenario;

  private String input;

  private com.orbit.api.Matcher   orbitMatcher;
  private java.util.regex.Matcher jdkMatcher;
  private com.google.re2j.Matcher re2jMatcher;

  @Setup(Level.Trial)
  public void setup() {
    String pattern;
    switch (scenario) {
      case "literal" -> {
        pattern = "hello";
        input   = "The quick brown fox said hello to the world, hello again.";
      }
      case "phone" -> {
        // ONE_PASS_SAFE → PrecomputedDfa
        pattern = "\\d{3}-\\d{3}-\\d{4}";
        input   = "Contact us at 800-555-1234 or 415-867-5309 for support.";
      }
      case "email" -> {
        // DFA_SAFE
        pattern = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,6}";
        input   = "Send mail to alice@example.com or support@orbit-regex.io for help.";
      }
      case "alternation" -> {
        // DFA_SAFE — alternation of literals (Aho-Corasick prefilter candidate)
        pattern = "error|warn|fatal|critical|exception";
        input   = "2026-03-23 ERROR [main] fatal exception in thread warn: critical failure";
      }
      case "ip_address" -> {
        // PIKEVM_ONLY — repeated capturing group
        pattern = "(\\d{1,3}\\.){3}\\d{1,3}";
        input   = "Client 192.168.1.42 connected from 10.0.0.1 via 172.16.254.1";
      }
      default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
    }

    orbitMatcher = com.orbit.api.Pattern.compile(pattern).matcher(input);
    jdkMatcher   = java.util.regex.Pattern.compile(pattern).matcher(input);
    re2jMatcher  = com.google.re2j.Pattern.compile(pattern).matcher(input);
  }

  /** Orbit: find the first match in a short input string. */
  @Benchmark
  public boolean orbitFind(Blackhole bh) {
    orbitMatcher.reset();
    boolean found = orbitMatcher.find();
    if (found) bh.consume(orbitMatcher.start());
    return found;
  }

  /** JDK: find the first match in a short input string. */
  @Benchmark
  public boolean jdkFind(Blackhole bh) {
    jdkMatcher.reset();
    boolean found = jdkMatcher.find();
    if (found) bh.consume(jdkMatcher.start());
    return found;
  }

  /** re2j: find the first match in a short input string. */
  @Benchmark
  public boolean re2jFind(Blackhole bh) {
    re2jMatcher.reset();
    boolean found = re2jMatcher.find();
    if (found) bh.consume(re2jMatcher.start());
    return found;
  }

  /** Orbit: count all matches in the short input. */
  @Benchmark
  public int orbitFindAll(Blackhole bh) {
    orbitMatcher.reset();
    int count = 0;
    while (orbitMatcher.find()) { bh.consume(orbitMatcher.start()); count++; }
    return count;
  }

  /** JDK: count all matches in the short input. */
  @Benchmark
  public int jdkFindAll(Blackhole bh) {
    jdkMatcher.reset();
    int count = 0;
    while (jdkMatcher.find()) { bh.consume(jdkMatcher.start()); count++; }
    return count;
  }

  /** re2j: count all matches in the short input. */
  @Benchmark
  public int re2jFindAll(Blackhole bh) {
    re2jMatcher.reset();
    int count = 0;
    while (re2jMatcher.find()) { bh.consume(re2jMatcher.start()); count++; }
    return count;
  }
}
