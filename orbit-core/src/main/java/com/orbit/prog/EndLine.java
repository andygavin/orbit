package com.orbit.prog;

/**
 * End of line assertion for {@code $}.
 *
 * <p>Passes when the current position is at end of input ({@code pos == to}) or immediately
 * before a line terminator. The set of recognised terminators depends on {@code unixLines} and
 * {@code perlNewlines}:
 * <ul>
 *   <li>When {@code unixLines} is {@code true}, only {@code '\n'} counts.
 *   <li>When {@code perlNewlines} is {@code true}, {@code '\n'} and {@code '\r'} count (but
 *       the position between the {@code '\r'} and {@code '\n'} of a CRLF pair does not pass —
 *       the match point is before the {@code '\r'}, or after the full {@code '\r\n'} pair).
 *   <li>Otherwise, {@code '\n'}, {@code '\r'}, {@code '\u0085'}, {@code '\u2028'}, and
 *       {@code '\u2029'} all count, with the CRLF-interior exception.
 * </ul>
 *
 * <p>The {@code multiline} field records whether the surrounding pattern was compiled with the
 * MULTILINE flag. It does not affect the evaluation predicate; both MULTILINE and non-MULTILINE
 * {@code $} use identical semantics at the instruction level.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param next         the program counter of the next instruction on success
 * @param multiline    whether the pattern was compiled with the MULTILINE flag
 * @param unixLines    whether only {@code '\n'} counts as a line terminator (UNIX_LINES flag)
 * @param perlNewlines whether Perl newline semantics are active (PERL_NEWLINES flag)
 */
public record EndLine(int next, boolean multiline, boolean unixLines, boolean perlNewlines) implements Instr {}
