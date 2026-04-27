package com.orbit.util;

/**
 * Compilation flags for {@link com.orbit.api.Pattern}.
 *
 * <h2>JDK-equivalent flags</h2>
 * <p>The following flags mirror {@link java.util.regex.Pattern} semantics exactly and can be
 * used as a drop-in replacement:
 * <ul>
 *   <li>{@link #CASE_INSENSITIVE} — case-insensitive matching (ASCII range by default;
 *       combine with {@link #UNICODE_CASE} for full Unicode folding)</li>
 *   <li>{@link #MULTILINE} — {@code ^} and {@code $} match at every line boundary</li>
 *   <li>{@link #DOTALL} — {@code .} matches every character including line terminators</li>
 *   <li>{@link #UNICODE_CASE} — enables Unicode case folding for {@link #CASE_INSENSITIVE};
 *       includes Turkish dotless-i, long-s, Kelvin sign, and Ångström sign</li>
 *   <li>{@link #CANON_EQ} — accepted for API compatibility; canonical equivalence is not
 *       enforced at match time</li>
 *   <li>{@link #UNIX_LINES} — restricts line-terminator recognition to {@code \n} only;
 *       affects {@code .}, {@code ^}, {@code $}, and {@code \Z}</li>
 *   <li>{@link #LITERAL} — treats the entire pattern as a literal string</li>
 *   <li>{@link #COMMENTS} — ignores unescaped whitespace and {@code #}-to-end-of-line
 *       comments in the pattern</li>
 * </ul>
 *
 * <h2>Orbit extension flags</h2>
 * <ul>
 *   <li>{@link #UNICODE} — activates full Unicode-aware character classes: {@code \w},
 *       {@code \d}, {@code \s}, and {@code \b} use Unicode properties; POSIX classes
 *       ({@code \p{Alpha}} etc.) use Unicode categories; case folding is extended.
 *       Equivalent to JDK's {@code UNICODE_CHARACTER_CLASS}.</li>
 *   <li>{@link #PERL_NEWLINES} — dot excludes {@code \n} only (not the full JDK set of
 *       six line terminators); {@code \r} and {@code \r\n} remain valid anchor positions.
 *       Matches Perl's default newline semantics.</li>
 *   <li>{@link #RE2_COMPAT} — restricts patterns to the RE2 subset. Backreferences,
 *       lookahead, lookbehind, possessive quantifiers, and atomic groups throw
 *       {@link com.orbit.parse.PatternSyntaxException} at compile time. Dot and
 *       anchors use {@code \n}-only semantics. Guarantees O(n) matching.</li>
 *   <li>{@link #NO_PREFILTER} — disables the literal-string prefilter; useful for
 *       benchmarking engine throughput without prefilter influence.</li>
 *   <li>{@link #STREAMING} — reserved; not yet implemented.</li>
 * </ul>
 */
public enum PatternFlag {
    CASE_INSENSITIVE, MULTILINE, DOTALL, UNICODE_CASE,
    CANON_EQ, UNIX_LINES, LITERAL, COMMENTS,
    RE2_COMPAT, UNICODE, PERL_NEWLINES, STREAMING, NO_PREFILTER
}
