package com.orbit.benchmarks;

import com.orbit.api.Pattern;
import com.orbit.util.PatternFlag;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompilationBenchmark {

    @Param({"hello", "\\d{3}-\\d{3}-\\d{4}", "(a|b|c)+", "\\w+@\\w+\\.com"})
    private String regex;

    private PatternFlag[] flags = new PatternFlag[0];

    @Benchmark
    public Pattern compile() {
        return Pattern.compile(regex, flags);
    }

    @Benchmark
    public void compileStatic() {
        Pattern.compile(regex, flags);
    }

    @Benchmark
    public void compileStaticCached() {
        // Force cache to be populated
        Pattern.compile(regex, flags);
        Pattern.compile(regex, flags);
    }
}