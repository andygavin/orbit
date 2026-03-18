package com.orbital.benchmarks;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PerformanceBenchmark {

    private Pattern orbitPattern;
    private Pattern javaPattern;
    private String input;

    @Param({"literal", "group", "quantifier", "alternation"})
    private String patternType;

    @Setup(Level.Trial)
    public void setup() {
        switch (patternType) {
            case "literal":
                orbitPattern = Pattern.compile("hello");
                javaPattern = java.util.regex.Pattern.compile("hello");
                input = "hello world hello again";
                break;
            case "group":
                orbitPattern = Pattern.compile("(\d{3})-(\d{3})-(\d{4})");
                javaPattern = java.util.regex.Pattern.compile("(\d{3})-(\d{3})-(\d{4})");
                input = "123-456-7890";
                break;
            case "quantifier":
                orbitPattern = Pattern.compile("a+");
                javaPattern = java.util.regex.Pattern.compile("a+");
                input = "aaaa bbbb";
                break;
            case "alternation":
                orbitPattern = Pattern.compile("cat|dog|bird");
                javaPattern = java.util.regex.Pattern.compile("cat|dog|bird");
                input = "cat dog bird";
                break;
        }
    }

    @Benchmark
    public boolean orbitMatches() {
        Matcher matcher = orbitPattern.matcher(input);
        return matcher.matches();
    }

    @Benchmark
    public boolean javaMatches() {
        java.util.regex.Matcher matcher = javaPattern.matcher(input);
        return matcher.matches();
    }

    @Benchmark
    public boolean orbitFind() {
        Matcher matcher = orbitPattern.matcher(input);
        return matcher.find();
    }

    @Benchmark
    public boolean javaFind() {
        java.util.regex.Matcher matcher = javaPattern.matcher(input);
        return matcher.find();
    }

    @Benchmark
    public int orbitFindCount() {
        Matcher matcher = orbitPattern.matcher(input);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Benchmark
    public int javaFindCount() {
        java.util.regex.Matcher matcher = javaPattern.matcher(input);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Benchmark
    public String orbitReplaceAll() {
        Matcher matcher = orbitPattern.matcher(input);
        return matcher.replaceAll("replacement");
    }

    @Benchmark
    public String javaReplaceAll() {
        java.util.regex.Matcher matcher = javaPattern.matcher(input);
        return matcher.replaceAll("replacement");
    }

    @Benchmark
    public String[] orbitSplit() {
        return orbitPattern.split(input);
    }

    @Benchmark
    public String[] javaSplit() {
        return javaPattern.split(input);
    }

    @Benchmark
    public void orbitCompile() {
        Pattern.compile("hello");
    }

    @Benchmark
    public void javaCompile() {
        java.util.regex.Pattern.compile("hello");
    }
}