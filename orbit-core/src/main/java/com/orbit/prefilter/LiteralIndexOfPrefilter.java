package com.orbital.prefilter;

/**
 * Prefilter using simple String.indexOf for literal search.
 */
public record LiteralIndexOfPrefilter(String literal) implements Prefilter {

    @Override
    public int findFirst(String input, int from, int to) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }
        if (from < 0 || to < 0 || from > to) {
            throw new IllegalArgumentException("Invalid range: from=" + from + ", to=" + to);
        }

        int index = input.indexOf(literal, from);
        return (index >= 0 && index <= to - literal.length()) ? index : -1;
    }

    @Override
    public boolean isTrivial() {
        return false;
    }
}