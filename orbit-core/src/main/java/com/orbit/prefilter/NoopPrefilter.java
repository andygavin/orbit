package com.orbital.prefilter;

/**
 * No-op prefilter that always returns -1.
 */
public record NoopPrefilter() implements Prefilter {

    public static final NoopPrefilter INSTANCE = new NoopPrefilter();

    @Override
    public int findFirst(String input, int from, int to) {
        return -1;
    }

    @Override
    public boolean isTrivial() {
        return true;
    }
}