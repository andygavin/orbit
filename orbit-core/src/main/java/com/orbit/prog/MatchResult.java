package com.orbit.prog;

import java.util.List;

/**
 * Result of a match operation.
 *
 * <p>Instances are immutable. The {@link #groupSpans()} list stores {@code int[2]} arrays
 * of the form {@code {start, end}} for each capturing group, using {@code {-1, -1}} for
 * unmatched (optional) groups. This enables {@code Matcher.start(int)} and
 * {@code Matcher.end(int)} without string reconstruction.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public class MatchResult {

    private final boolean matches;
    private final int start;
    private final int end;
    private final List<String> groups;
    /**
     * Per-group start/end positions. Each element is a two-element {@code int[]} whose
     * first element is the start index (inclusive) and second is the end index (exclusive),
     * or {@code {-1, -1}} when the group did not participate in the match.
     */
    private final List<int[]> groupSpans;
    private final String output;
    private final long instructionsExecuted;
    private final long bytesScanned;
    private final int backtrackCount;
    /**
     * Engine-level hit-end flag. {@code true} when the engine reached the end of the input
     * region during a match attempt — i.e., more input could have changed the result.
     * {@code false} when the engine determined the result definitively before reaching the
     * region end (e.g. a start-anchored pattern that failed at position 0).
     */
    private final boolean hitEnd;

    /**
     * Creates a {@code MatchResult} with full group-span information and an explicit
     * {@code hitEnd} flag.
     *
     * @param matches             whether a match was found
     * @param start               the match start index (inclusive)
     * @param end                 the match end index (exclusive)
     * @param groups              the list of captured group strings (null entries = unmatched)
     * @param groupSpans          the list of {@code int[2]} span arrays for each group
     * @param output              the transducer output string, or null
     * @param instructionsExecuted number of instructions executed
     * @param bytesScanned        number of bytes scanned
     * @param backtrackCount      number of backtracks performed
     * @param hitEnd              whether the engine reached end-of-input during the attempt
     */
    public MatchResult(
        boolean matches,
        int start,
        int end,
        List<String> groups,
        List<int[]> groupSpans,
        String output,
        long instructionsExecuted,
        long bytesScanned,
        int backtrackCount,
        boolean hitEnd
    ) {
        this.matches = matches;
        this.start = start;
        this.end = end;
        this.groups = groups;
        this.groupSpans = groupSpans;
        this.output = output;
        this.instructionsExecuted = instructionsExecuted;
        this.bytesScanned = bytesScanned;
        this.backtrackCount = backtrackCount;
        this.hitEnd = hitEnd;
    }

    /**
     * Creates a {@code MatchResult} with full group-span information.
     *
     * <p>The {@code hitEnd} field defaults to {@code false}; callers that compute precise
     * engine-level hit-end semantics should use the ten-argument constructor.
     *
     * @param matches             whether a match was found
     * @param start               the match start index (inclusive)
     * @param end                 the match end index (exclusive)
     * @param groups              the list of captured group strings (null entries = unmatched)
     * @param groupSpans          the list of {@code int[2]} span arrays for each group
     * @param output              the transducer output string, or null
     * @param instructionsExecuted number of instructions executed
     * @param bytesScanned        number of bytes scanned
     * @param backtrackCount      number of backtracks performed
     */
    public MatchResult(
        boolean matches,
        int start,
        int end,
        List<String> groups,
        List<int[]> groupSpans,
        String output,
        long instructionsExecuted,
        long bytesScanned,
        int backtrackCount
    ) {
        this(matches, start, end, groups, groupSpans, output,
            instructionsExecuted, bytesScanned, backtrackCount, false);
    }

    /**
     * Creates a {@code MatchResult} without group spans (backwards-compatible convenience
     * overload). The {@link #groupSpans()} list will be empty.
     *
     * <p>The {@code hitEnd} field defaults to {@code false}.
     *
     * @param matches             whether a match was found
     * @param start               the match start index (inclusive)
     * @param end                 the match end index (exclusive)
     * @param groups              the list of captured group strings (null entries = unmatched)
     * @param output              the transducer output string, or null
     * @param instructionsExecuted number of instructions executed
     * @param bytesScanned        number of bytes scanned
     * @param backtrackCount      number of backtracks performed
     */
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
        this(matches, start, end, groups, List.of(), output,
            instructionsExecuted, bytesScanned, backtrackCount, false);
    }

    /** Returns whether this result represents a successful match. */
    public boolean matches() {
        return matches;
    }

    /** Returns the match start index (inclusive). */
    public int start() {
        return start;
    }

    /** Returns the match end index (exclusive). */
    public int end() {
        return end;
    }

    /**
     * Returns the list of captured group strings. Null entries indicate unmatched optional
     * groups.
     *
     * @return the group list; never null
     */
    public List<String> groups() {
        return groups;
    }

    /**
     * Returns the per-group span list. Each element is a two-element {@code int[]} whose
     * entries are {@code {start, end}} (both -1 for unmatched groups).
     *
     * <p>May be empty when the {@code MatchResult} was constructed without span data.
     *
     * @return the span list; never null
     */
    public List<int[]> groupSpans() {
        return groupSpans;
    }

    /** Returns the transducer output string, or null if none. */
    public String output() {
        return output;
    }

    /** Returns the number of instructions executed during matching. */
    public long instructionsExecuted() {
        return instructionsExecuted;
    }

    /** Returns the number of bytes scanned during matching. */
    public long bytesScanned() {
        return bytesScanned;
    }

    /** Returns the number of backtracks performed during matching. */
    public int backtrackCount() {
        return backtrackCount;
    }

    /**
     * Returns whether the engine reached end-of-input during the last match attempt.
     *
     * <p>When {@code true}, more input could have changed the result. When {@code false},
     * the engine determined the result definitively before reaching the region end — for
     * example, a start-anchored pattern that failed at position 0.
     *
     * @return {@code true} if end-of-input was hit during the attempt
     */
    public boolean hitEnd() {
        return hitEnd;
    }
}
