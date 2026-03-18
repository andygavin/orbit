package com.orbital.prog;

import java.util.List;

/**
 * Result of a match operation.
 */
public class MatchResult {

    private final boolean matches;
    private final int start;
    private final int end;
    private final List<String> groups;
    private final String output;
    private final long instructionsExecuted;
    private final long bytesScanned;
    private final int backtrackCount;

    public MatchResult(
        boolean matches,
        int start,
        int end,
        List<String> groups,
        String output,
        long instructionsExecuted,
        long bytesScanned,
        int backtrackCount
    ) {
        this.matches = matches;
        this.start = start;
        this.end = end;
        this.groups = groups;
        this.output = output;
        this.instructionsExecuted = instructionsExecuted;
        this.bytesScanned = bytesScanned;
        this.backtrackCount = backtrackCount;
    }

    public boolean matches() {
        return matches;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public List<String> groups() {
        return groups;
    }

    public String output() {
        return output;
    }

    public long instructionsExecuted() {
        return instructionsExecuted;
    }

    public long bytesScanned() {
        return bytesScanned;
    }

    public int backtrackCount() {
        return backtrackCount;
    }
}