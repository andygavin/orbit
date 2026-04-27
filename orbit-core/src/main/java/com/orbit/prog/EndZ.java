package com.orbit.prog;

/**
 * {@code \Z} anchor assertion — end of input or before the final line terminator only.
 *
 * <p>Passes when {@code pos == to} (end of input), or when {@code pos} is immediately before the
 * single trailing line terminator of the input. The set of recognised terminators depends on
 * {@code unixLines} and {@code perlNewlines}:
 * <ul>
 *   <li>When {@code unixLines} is {@code true}, only {@code '\n'} is recognised.
 *   <li>When {@code perlNewlines} is {@code true}, {@code '\n'} and {@code '\r'} are recognised,
 *       including the two-character CRLF ({@code \r\n}) sequence as a single unit.
 *   <li>Otherwise, {@code '\n'}, {@code '\r'}, {@code '\u0085'}, {@code '\u2028'}, and
 *       {@code '\u2029'} are all recognised, including a trailing CRLF pair.
 * </ul>
 *
 * <p>Unlike {@link EndLine}, {@code EndZ} does <em>not</em> match before embedded line
 * terminators — only before the final one. It is not affected by the MULTILINE flag.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param next         the program counter of the next instruction on success
 * @param unixLines    whether only {@code '\n'} counts as the final line terminator (UNIX_LINES flag)
 * @param perlNewlines whether Perl newline semantics are active (PERL_NEWLINES flag)
 */
public record EndZ(int next, boolean unixLines, boolean perlNewlines) implements Instr {}
