package com.orbital.prefilter;

import java.util.List;

/**
 * Aho-Corasick multi-pattern string matching algorithm.
 */
public record AhoCorasickPrefilter(List<String> literals, boolean nfaMode) implements Prefilter {

    @Override
    public int findFirst(String input, int from, int to) {
        // Simple implementation for now
        for (int i = from; i <= to; i++) {
            for (String literal : literals) {
                if (i + literal.length() <= to &&
                    input.regionMatches(i, literal, 0, literal.length())) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean isTrivial() {
        return literals.isEmpty();
    }
}