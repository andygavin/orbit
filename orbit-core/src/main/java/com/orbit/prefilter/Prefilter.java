package com.orbital.prefilter;

/**
 * Sealed prefilter hierarchy for fast path filtering.
 */
public sealed interface Prefilter permits
    LiteralIndexOfPrefilter,
    VectorLiteralPrefilter,
    AhoCorasickPrefilter,
    NoopPrefilter {

    /**
     * Finds the first position where the prefilter might match.
     * Returns -1 if no potential match exists.
     */
    int findFirst(String input, int from, int to);

    /**
     * Returns true if this prefilter is always satisfied (no filtering needed).
     */
    default boolean isTrivial() {
        return false;
    }
}