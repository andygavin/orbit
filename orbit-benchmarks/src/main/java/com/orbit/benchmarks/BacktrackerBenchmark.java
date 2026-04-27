package com.orbit.benchmarks;

import com.orbit.api.Pattern;
import com.orbit.engine.engines.BoundedBacktrackEngine;
import com.orbit.engine.engines.PikeVmEngine;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Latency benchmark: {@link BoundedBacktrackEngine} vs {@link PikeVmEngine} vs JDK for patterns
 * that require backtracking semantics (backreferences, possessive quantifiers).
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code backref_short}    — {@code (\w+)\s+\1} on an 11-char input; BBE expected to win</li>
 *   <li>{@code backref_sentence} — {@code (\w+)\s+\1} on a realistic sentence with a repeated word</li>
 *   <li>{@code backref_long}     — {@code (\w+)\s+\1} on ~10 KB of text; PikeVM expected to tie or win</li>
 *   <li>{@code possessive}       — {@code \w++\s++\w++} on a short phrase; exercises PossessiveSplit/AtomicCommit</li>
 * </ul>
 *
 * <p>Run:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar BacktrackerBenchmark
 * }</pre>
 *
 * <p>Run a single scenario:
 * <pre>{@code
 *   java --enable-preview -jar orbit-benchmarks.jar BacktrackerBenchmark -p scenario=backref_short
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules", "jdk.incubator.vector"})
public class BacktrackerBenchmark {

    @Param({"backref_short", "backref_sentence", "backref_long", "possessive"})
    private String scenario;

    private Prog prog;
    private String input;

    private BoundedBacktrackEngine boundedBacktracker;
    private PikeVmEngine pikeVm;
    private java.util.regex.Pattern jdkPattern;

    @Setup(Level.Trial)
    public void setup() {
        String pattern;
        switch (scenario) {
            case "backref_short" -> {
                // Canonical backreference: matches a repeated word like "hello hello".
                // Short input fits in L1 cache — BBE expected to win.
                pattern = "(\\w+)\\s+\\1";
                input   = "hello hello";
            }
            case "backref_sentence" -> {
                // Realistic duplicate-word detection in a sentence.
                // Match is near the end, requiring scan through non-matching prefixes.
                pattern = "(\\w+)\\s+\\1";
                input   = "The quick brown fox jumped over the the lazy dog";
            }
            case "backref_long" -> {
                // Same pattern on ~10 KB of access-log text.
                // Match is seeded at the end; PikeVM expected to tie or win due to corpus size.
                pattern = "(\\w+)\\s+\\1";
                String corpus = CorpusGenerator.accessLog(80);  // ~10 KB
                // Append a guaranteed match so both engines find the same result.
                input = corpus + " test test";
            }
            case "possessive" -> {
                // Possessive quantifiers — exercises PossessiveSplit / AtomicCommit path in BBE.
                // JDK does not support possessive quantifiers; jdkPattern is set to null.
                pattern = "\\w++\\s++\\w++";
                input   = "hello world";
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }

        prog              = Pattern.compile(pattern).prog();
        boundedBacktracker = new BoundedBacktrackEngine();
        pikeVm             = new PikeVmEngine();
        if (!scenario.equals("possessive")) {
            jdkPattern = java.util.regex.Pattern.compile(pattern);
        }
    }

    /** BoundedBacktrackEngine: find the first match. */
    @Benchmark
    public boolean boundedBacktrack(Blackhole bh) {
        MatchResult r = boundedBacktracker.execute(prog, input, 0, input.length(), 0, true, false, 0);
        bh.consume(r.start());
        return r.matches();
    }

    /** PikeVmEngine: find the first match. */
    @Benchmark
    public boolean pikeVm(Blackhole bh) {
        MatchResult r = pikeVm.execute(prog, input, 0, input.length(), 0, true, false, 0);
        bh.consume(r.start());
        return r.matches();
    }

    /**
     * JDK {@code java.util.regex}: find the first match.
     * Skipped for the {@code possessive} scenario (JDK syntax differs).
     */
    @Benchmark
    public boolean jdk(Blackhole bh) {
        if (jdkPattern == null) {
            bh.consume(0);
            return false;
        }
        java.util.regex.Matcher m = jdkPattern.matcher(input);
        boolean found = m.find();
        if (found) bh.consume(m.start());
        return found;
    }
}
