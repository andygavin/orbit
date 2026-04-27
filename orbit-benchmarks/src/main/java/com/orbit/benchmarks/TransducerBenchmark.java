package com.orbit.benchmarks;

import com.orbit.api.Transducer;
import com.orbit.util.TransducerFlag;
import org.openjdk.jmh.annotations.*;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransducerBenchmark {

    private Transducer transducer;
    private String input;

    @Param({"simple", "xml", "email", "html"})
    private String transducerType;

    @Setup(Level.Trial)
    public void setup() {
        switch (transducerType) {
            case "simple":
                transducer = Transducer.compile("a:(b)");
                input = "a a a a";
                break;
            case "xml":
                transducer = Transducer.compile("<tag>:</tag>");
                input = "<tag>content</tag>";
                break;
            case "email":
                transducer = Transducer.compile("\\w+@(\\w+\\.\\w+):[\\1]");
                input = "user@example.com";
                break;
            case "html":
                transducer = Transducer.compile("<b>:</b>");
                input = "<b>bold</b> text";
                break;
        }
    }

    @Benchmark
    public String applyUp() {
        return transducer.applyUp(input);
    }

    @Benchmark
    public String applyDown() {
        return transducer.applyDown(input);
    }

    @Benchmark
    public List<com.orbit.api.Token> tokenize() {
        return transducer.tokenize(input);
    }

    @Benchmark
    public Iterator<com.orbit.api.Token> tokenizeIterator() {
        return transducer.tokenizeIterator(new java.io.StringReader(input));
    }

    @Benchmark
    public java.util.stream.Stream<com.orbit.api.Token> tokenizeStream() {
        return transducer.tokenizeStream(new java.io.StringReader(input));
    }
}