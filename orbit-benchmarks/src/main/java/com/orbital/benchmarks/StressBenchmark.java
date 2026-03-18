package com.orbital.benchmarks;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StressBenchmark {

    private Pattern pattern;
    private String input;

    @Param({"stress", "catastrophic"})
    private String stressType;

    @Setup(Level.Trial)
    public void setup() {
        switch (stressType) {
            case "stress":
                // Create a pattern that will cause many backtracking steps
                pattern = Pattern.compile("(a+)+");
                input = "aaaaaaaaaa";
                break;
            case "catastrophic":
                // Create a pattern that could cause catastrophic backtracking
                pattern = Pattern.compile("(a+)+b");
                input = "aaaaaaaaaa";
                break;
        }
    }

    @Benchmark
    public boolean matches() {
        Matcher matcher = pattern.matcher(input);
        return matcher.matches();
    }

    @Benchmark
    public boolean find() {
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    @Benchmark
    public int findCount() {
        Matcher matcher = pattern.matcher(input);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Benchmark
    public String replaceAll() {
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll("replacement");
    }
}