package com.orbit.parse;

import com.orbit.util.SourceSpan;
import java.util.OptionalDouble;

/**
 * Transducer pair: input -> output with optional weight.
 * Output is validated to be acyclic during analysis.
 */
public record Pair(Expr input, Expr output, OptionalDouble weight) implements Expr {
    public Pair {
        if (input == null) {
            throw new NullPointerException("Input expression cannot be null");
        }
        if (output == null) {
            throw new NullPointerException("Output expression cannot be null");
        }
        if (weight.isPresent() && weight.getAsDouble() < 0) {
            throw new IllegalArgumentException("Weight cannot be negative");
        }
    }

    @Override
    public SourceSpan span() {
        return SourceSpan.combine(input.span(), output.span());
    }
}