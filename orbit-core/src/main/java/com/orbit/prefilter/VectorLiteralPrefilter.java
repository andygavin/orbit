package com.orbital.prefilter;

import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorSpecies;

import java.util.List;

/**
 * Prefilter using JDK Vector API for parallel literal search.
 */
public record VectorLiteralPrefilter(List<String> literals) implements Prefilter {

    @Override
    public int findFirst(String input, int from, int to) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }
        if (from < 0 || to < 0 || from > to) {
            throw new IllegalArgumentException("Invalid range: from=" + from + ", to=" + to);
        }

        // Simple implementation for now - would use Vector API in production
        for (String literal : literals) {
            int index = input.indexOf(literal, from);
            if (index >= 0 && index <= to - literal.length()) {
                return index;
            }
        }

        return -1;
    }

    @Override
    public boolean isTrivial() {
        return literals.isEmpty();
    }
}