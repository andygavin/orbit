package com.orbit.util;

/**
 * Describes which execution engine is safe for a compiled pattern.
 *
 * <p>The meta-engine assigns a hint during compilation based on static analysis of the pattern's
 * HIR. At match time it selects the fastest engine that satisfies the hint. Callers can read the
 * hint from {@link com.orbit.api.Pattern#engineHint()} for diagnostic purposes.
 *
 * <ul>
 *   <li>{@link #ONE_PASS_SAFE} — the pattern is deterministic and has at most one active thread
 *       at every position; the one-pass DFA can track captures inline without PikeVM fallback.</li>
 *   <li>{@link #DFA_SAFE} — the pattern contains no backreferences, lookaround, or balancing
 *       groups; the lazy DFA can execute it in O(n) time. Captures require a PikeVM sub-run
 *       over the matched substring.</li>
 *   <li>{@link #PIKEVM_ONLY} — the pattern requires capture tracking (named/numbered groups)
 *       or uses moderate transducer output; the PikeVM is used.</li>
 *   <li>{@link #NEEDS_BACKTRACKER} — the pattern uses balancing groups, possessive quantifiers,
 *       or contextual transducer rules that require backtracking; the bounded backtrack engine
 *       is used with a configurable backtrack budget.</li>
 *   <li>{@link #GRAMMAR_RULE} — reserved for recursive grammar productions (not yet
 *       implemented).</li>
 * </ul>
 */
public enum EngineHint {
    ONE_PASS_SAFE,
    DFA_SAFE,
    PIKEVM_ONLY,
    NEEDS_BACKTRACKER,
    GRAMMAR_RULE
}
