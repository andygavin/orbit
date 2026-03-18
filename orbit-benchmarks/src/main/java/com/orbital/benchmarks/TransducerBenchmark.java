package com.orbital.benchmarks;

import com.orbital.api.Transducer;
import com.orbital.util.TransducerFlag;
import org.openjdk.jmh.annotations.*;
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
    public List<com.orbital.api.Token> tokenize() {
        return transducer.tokenize(input);
    }

    @Benchmark
    public Iterator<com.orbital.api.Token> tokenizeIterator() {
        return transducer.tokenizeIterator(new java.io.StringReader(input));
    }

    @Benchmark
    public java.util.stream.Stream<com.orbital.api.Token> tokenizeStream() {
        return transducer.tokenizeStream(new java.io.StringReader(input));
    }
}