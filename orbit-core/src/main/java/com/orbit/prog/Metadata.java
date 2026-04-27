package com.orbit.prog;

import com.orbit.util.EngineHint;
import com.orbit.prefilter.Prefilter;

import java.util.Map;
import java.util.Objects;

/**
 * Metadata about a compiled program.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record Metadata(
    EngineHint hint,
    Prefilter prefilter,
    int groupCount,
    int maxOutputLength,
    boolean isWeighted,
    boolean isTransducer,
    Map<String, Integer> groupNames,
    boolean startAnchored,
    boolean endAnchored,
    boolean unicodeCase,
    boolean outputPrecedesInput
) {
    /**
     * Creates a {@code Metadata} instance with all fields.
     *
     * @param hint            the engine selection hint; must not be null
     * @param prefilter       the prefilter; must not be null
     * @param groupCount      the number of capturing groups; must be >= 0
     * @param maxOutputLength the maximum output length; must be >= 0
     * @param isWeighted      whether the pattern is weighted
     * @param isTransducer    whether the pattern is a transducer
     * @param groupNames      map from group name to 1-based group index; must not be null
     * @param startAnchored   whether the pattern is anchored to the start of input
     * @param endAnchored     whether the pattern is anchored to the end of input
     * @param unicodeCase     whether the pattern was compiled with the {@code UNICODE_CASE} flag
     */
    public Metadata {
        if (hint == null) {
            throw new NullPointerException("Hint cannot be null");
        }
        if (prefilter == null) {
            throw new NullPointerException("Prefilter cannot be null");
        }
        if (groupCount < 0) {
            throw new IllegalArgumentException("Group count must be >= 0");
        }
        if (maxOutputLength < 0) {
            throw new IllegalArgumentException("Max output length must be >= 0");
        }
        Objects.requireNonNull(groupNames, "groupNames must not be null");
        groupNames = Map.copyOf(groupNames);
    }

    /**
     * Creates a {@code Metadata} instance with group names but without anchor flags (defaults
     * both to {@code false}).
     *
     * @param hint            the engine selection hint; must not be null
     * @param prefilter       the prefilter; must not be null
     * @param groupCount      the number of capturing groups; must be >= 0
     * @param maxOutputLength the maximum output length; must be >= 0
     * @param isWeighted      whether the pattern is weighted
     * @param isTransducer    whether the pattern is a transducer
     * @param groupNames      map from group name to 1-based group index; must not be null
     */
    public Metadata(
        EngineHint hint,
        Prefilter prefilter,
        int groupCount,
        int maxOutputLength,
        boolean isWeighted,
        boolean isTransducer,
        Map<String, Integer> groupNames
    ) {
        this(hint, prefilter, groupCount, maxOutputLength, isWeighted, isTransducer, groupNames,
            false, false, false, false);
    }

    /**
     * Creates a {@code Metadata} instance without group names (backwards-compatible convenience
     * overload). The {@link #groupNames} map will be empty and both anchor flags default to
     * {@code false}.
     *
     * @param hint            the engine selection hint; must not be null
     * @param prefilter       the prefilter; must not be null
     * @param groupCount      the number of capturing groups; must be >= 0
     * @param maxOutputLength the maximum output length; must be >= 0
     * @param isWeighted      whether the pattern is weighted
     * @param isTransducer    whether the pattern is a transducer
     */
    public Metadata(
        EngineHint hint,
        Prefilter prefilter,
        int groupCount,
        int maxOutputLength,
        boolean isWeighted,
        boolean isTransducer
    ) {
        this(hint, prefilter, groupCount, maxOutputLength, isWeighted, isTransducer, Map.of(),
            false, false, false, false);
    }
}
