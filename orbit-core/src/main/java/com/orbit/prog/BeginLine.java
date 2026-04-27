package com.orbit.prog;

/**
 * Beginning of line assertion for {@code ^} in MULTILINE mode.
 *
 * <p>Passes when the current position is at the start of input ({@code pos == 0}) or immediately
 * after a line terminator. The set of recognised line terminators depends on {@code unixLines}
 * and {@code perlNewlines}:
 * <ul>
 *   <li>When {@code unixLines} is {@code true}, only {@code '\n'} is a terminator.
 *   <li>When {@code perlNewlines} is {@code true}, {@code '\n'} and {@code '\r'} are terminators,
 *       with the special rule that the position between the {@code '\r'} and {@code '\n'} of a
 *       CRLF pair does not pass (only the position after {@code '\n'} passes).
 *   <li>Otherwise, {@code '\n'}, {@code '\r'}, {@code '\u0085'}, {@code '\u2028'}, and
 *       {@code '\u2029'} are all terminators, with the CRLF-interior exception.
 * </ul>
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param next         the program counter of the next instruction on success
 * @param unixLines    whether only {@code '\n'} counts as a line terminator (UNIX_LINES flag)
 * @param perlNewlines whether Perl newline semantics are active (PERL_NEWLINES flag)
 */
public record BeginLine(int next, boolean unixLines, boolean perlNewlines) implements Instr {}
