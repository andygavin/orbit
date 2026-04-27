package com.orbit.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Throughput benchmark: Orbit vs JDK {@code java.util.regex} vs re2j.
 *
 * <p>Each scenario runs a full {@code find()}-all loop over a large corpus (~1 MB for log
 * scenarios, 500 KB for DNA).  Matchers are created once in {@code @Setup} and {@code reset()}
 * is called at the start of each benchmark invocation, so compilation and allocation costs are
 * excluded from the measured window.
 *
 * <p>Scenarios are chosen to exercise different engine paths in Orbit:
 * <ul>
 *   <li>{@code digits}       — {@code \d+}:                  ONE_PASS_SAFE → PrecomputedDfa</li>
 *   <li>{@code quoted}       — {@code "[^"]*"}:               DFA_SAFE      → LazyDfa (prefilter on {@code "})</li>
 *   <li>{@code ip_address}   — {@code (\d{1,3}\.){3}\d{1,3}}: PIKEVM_ONLY  → PikeVm (repeated capture)</li>
 *   <li>{@code log_request}  — {@code (GET|POST|…) \S+ HTTP/…}: PIKEVM_ONLY → PikeVm (capture + char class)</li>
 *   <li>{@code dna_motif}    — {@code ACGT+AC}:               DFA_SAFE      → LazyDfa (small alphabet)</li>
 * </ul>
 *
 * <p>Run the full suite:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar ThroughputBenchmark
 * }</pre>
 *
 * <p>Run a single scenario:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar ThroughputBenchmark -p scenario=digits
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class ThroughputBenchmark {

  @Param({"digits", "quoted", "ip_address", "log_request", "dna_motif"})
  private String scenario;

  // Corpus shared across all three engine instances for the same scenario
  private String corpus;

  private com.orbit.api.Matcher            orbitMatcher;
  private java.util.regex.Matcher          jdkMatcher;
  private com.google.re2j.Matcher          re2jMatcher;

  @Setup(Level.Trial)
  public void setup() {
    String pattern;
    switch (scenario) {
      case "digits" -> {
        pattern = "\\d+";
        corpus  = CorpusGenerator.accessLog(8_000);   // ~1 MB
      }
      case "quoted" -> {
        pattern = "\"[^\"]*\"";
        corpus  = CorpusGenerator.accessLog(8_000);
      }
      case "ip_address" -> {
        pattern = "(\\d{1,3}\\.){3}\\d{1,3}";
        corpus  = CorpusGenerator.accessLog(8_000);
      }
      case "log_request" -> {
        pattern = "(GET|POST|PUT|DELETE) (\\S+) HTTP/\\d\\.\\d";
        corpus  = CorpusGenerator.accessLog(8_000);
      }
      case "dna_motif" -> {
        pattern = "ACGT+AC";
        corpus  = CorpusGenerator.dnaSequence(500_000);  // 500 KB
      }
      default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
    }

    orbitMatcher = com.orbit.api.Pattern.compile(pattern).matcher(corpus);
    jdkMatcher   = java.util.regex.Pattern.compile(pattern).matcher(corpus);
    re2jMatcher  = com.google.re2j.Pattern.compile(pattern).matcher(corpus);
  }

  /**
   * Orbit: scan the full corpus, count matches.
   *
   * <p>{@code start()} is consumed via the {@link Blackhole} to prevent the JIT from
   * eliminating the find loop as dead code, without paying String allocation costs.
   */
  @Benchmark
  public int orbit(Blackhole bh) {
    orbitMatcher.reset();
    int count = 0;
    while (orbitMatcher.find()) {
      bh.consume(orbitMatcher.start());
      count++;
    }
    return count;
  }

  /** JDK {@code java.util.regex}: scan the full corpus, count matches. */
  @Benchmark
  public int jdk(Blackhole bh) {
    jdkMatcher.reset();
    int count = 0;
    while (jdkMatcher.find()) {
      bh.consume(jdkMatcher.start());
      count++;
    }
    return count;
  }

  /** re2j: scan the full corpus, count matches. */
  @Benchmark
  public int re2j(Blackhole bh) {
    re2jMatcher.reset();
    int count = 0;
    while (re2jMatcher.find()) {
      bh.consume(re2jMatcher.start());
      count++;
    }
    return count;
  }
}
