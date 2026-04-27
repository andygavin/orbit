package com.orbit.benchmarks;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import com.orbit.util.EngineHint;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EngineBenchmark {

    private Pattern pattern;
    private String input;
    private EngineHint engineHint;

    @Param({"literal", "group", "quantifier", "alternation"})
    private String patternType;

    @Setup(Level.Trial)
    public void setup() {
        switch (patternType) {
            case "literal":
                pattern = Pattern.compile("hello");
                input = "hello world hello again";
                engineHint = EngineHint.DFA_SAFE;
                break;
            case "group":
                pattern = Pattern.compile("(\\d{3})-(\\d{3})-(\\d{4})");
                input = "123-456-7890";
                engineHint = EngineHint.DFA_SAFE;
                break;
            case "quantifier":
                pattern = Pattern.compile("a+");
                input = "aaaa bbbb";
                engineHint = EngineHint.DFA_SAFE;
                break;
            case "alternation":
                pattern = Pattern.compile("cat|dog|bird");
                input = "cat dog bird";
                engineHint = EngineHint.DFA_SAFE;
                break;
        }
    }

    @Benchmark
    public void engineSelection() {
        com.orbit.engine.MetaEngine.getEngine(engineHint);
    }

    @Benchmark
    public void executeEngine() {
        com.orbit.engine.Engine engine = com.orbit.engine.MetaEngine.getEngine(engineHint);
        engine.execute(pattern.prog(), input, 0, input.length(), 0, true, false, 0);
    }

    @Benchmark
    public void fullPipeline() {
        com.orbit.engine.MetaEngine.execute(pattern.prog(), input, 0, input.length());
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
}