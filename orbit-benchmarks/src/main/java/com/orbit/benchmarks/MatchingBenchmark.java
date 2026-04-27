package com.orbit.benchmarks;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MatchingBenchmark {

    private Pattern pattern;
    private String input;

    @Param({"literal", "quantifier", "group", "alternation"})
    private String patternType;

    @Setup(Level.Trial)
    public void setup() {
        switch (patternType) {
            case "literal":
                pattern = Pattern.compile("hello");
                input = "hello world hello again";
                break;
            case "quantifier":
                pattern = Pattern.compile("a+");
                input = "aaaa bbbb aaaa";
                break;
            case "group":
                pattern = Pattern.compile("(\\d{3})-(\\d{3})-(\\d{4})");
                input = "123-456-7890 and 987-654-3210";
                break;
            case "alternation":
                pattern = Pattern.compile("cat|dog|bird");
                input = "cat dog bird cat dog";
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

    @Benchmark
    public String[] split() {
        return pattern.split(input);
    }
}