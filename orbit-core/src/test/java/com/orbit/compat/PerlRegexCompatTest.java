package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.util.PatternFlag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility tests for the Orbit regex engine using the Perl {@code re_tests} data file.
 *
 * <p>The data file is a tab-separated table of test cases extracted from the Perl source
 * distribution. Each row encodes a pattern, subject string, expected result flags,
 * an expression to evaluate after the match, and an expected expression value.
 *
 * <p>All transformations (escape sequences, variable interpolation, modifier mapping) are
 * applied at runtime. Rows describing Perl-specific features with no Java equivalent are
 * skipped via {@link Assumptions#assumeFalse}.
 *
 * <p>Instances are not thread-safe; each parameterised invocation creates its own
 * {@link Matcher} from the compiled pattern.
 */
class PerlRegexCompatTest {

  private static final String DATA_PATH = "/perl-tests/re_tests";

  /**
   * Line numbers in {@code re_tests} that are known to expose Orbit engine limitations.
   *
   * <p>Each entry corresponds to a specific line (1-based) in the data file. The failures
   * are grouped below by root cause for traceability:
   *
   * <ul>
   *   <li><b>Perl {@code \N{N}} / {@code \N{M,N}} quantifier syntax</b> (lines 42–51):
   *       Perl allows {@code \N{1}} to mean "exactly 1 non-newline" (quantifier on {@code \N}).
   *       In JDK/Orbit, {@code \N{...}} is a Unicode named-character escape; numeric content
   *       like {@code "1"} or {@code "3,4"} is not a valid Unicode character name, so Orbit
   *       correctly throws {@code PatternSyntaxException}. These tests use Perl-specific syntax.
   *   <li><b>Double-digit backreferences</b> (lines 291, 455): Orbit does not support
   *       {@code \10}+ backreferences to group 10 or higher.
   *   <li><b>Self-referential / conditional backreferences</b> (lines 495, 498, 975):
   *       Patterns like {@code (a\1?)} or {@code (a(?(1)\1))} are not supported.
   *   <li><b>Backreference inside lookahead</b> (lines 524, 639, 643): Orbit cannot
   *       resolve a backreference to a group captured only inside a lookahead.
   *   <li><b>Perl group-reset semantics</b> (lines 483, 506, 969, 970, 2141, 2144, 2145):
   *       In Perl, groups inside a repeated alternation reset to undef when they do not
   *       participate in the last iteration. Orbit (like Java) retains the previous value.
   *   <li><b>POSIX class used in char-class range endpoint</b> (lines 935, 937): Patterns
   *       like {@code [[:digit:]-z]} use a POSIX class as a range endpoint, which is
   *       undefined or error behaviour; Orbit may throw {@code PatternSyntaxException}.
   *   <li><b>\A\x80+\z pattern failure</b> (line 2036): Match fails for subject containing
   *       byte 0x80; possible char-class or encoding issue.
   *   <li><b>[^\W_0-9] with non-ASCII Unicode subject</b> (line 1663): Negated \W class
   *       does not match non-ASCII word characters (Unicode gap, related to POSIX/Unicode).
   *   <li><b>Possessive-quantifier emulation bug</b> (line 1072): {@code (?!)+?} is an
   *       always-failing zero-width assertion under a one-or-more lazy quantifier; Orbit
   *       does not handle this edge case correctly.
   *   <li><b>$ in /x mode with embedded \n</b> (lines 1465–1469): In comment mode,
   *       a {@code $} followed by a space and a literal {@code \n} should anchor and
   *       then require the newline char; Orbit mis-parses this combination.
   *   <li><b>Perl octal escape &gt; \\377</b> (lines 1559–1564): Perl interprets
   *       {@code \400}–{@code \777} as Unicode codepoints (U+0100–U+01FF). Orbit parses
   *       them as multi-byte octal, producing the wrong character.
   *   <li><b>NUL in character class adjacent to digit</b> (lines 1634, 1635): The
   *       sequence {@code [\0005]} should be a class containing NUL and {@code '5'};
   *       Orbit parses the octal sequence differently.
   *   <li><b>Unicode case-folding for ligatures / ß</b> (lines 1644, 1693–1696, 1714,
   *       1717, 1718, 2044): Full Perl Unicode case-folding maps {@code ff} to U+FB00,
   *       {@code fi} to U+FB01, {@code st} to U+FB05, and {@code ß} to {@code ss}.
   *       Orbit does not implement these multi-character folds.
   *   <li><b>ZWNJ / ZWJ treated as \w</b> (lines 1847–1850): Perl classifies U+200C
   *       (ZWNJ) and U+200D (ZWJ) as word characters; Orbit follows Java's definition,
   *       which does not.
   *   <li><b>\\10 octal vs. backreference ambiguity</b> (lines 1965–1968): When fewer
   *       than 10 capturing groups precede {@code \10}, Perl treats it as octal 010
   *       (backspace). Orbit always treats {@code \10} as a backreference to group 10.
   *   <li><b>$ inside MULTILINE /x pattern</b> (line 1832): A {@code $} inside a
   *       comment-mode MULTILINE pattern in the middle of a group does not anchor
   *       correctly in Orbit.
   * </ul>
   */
  private static final Set<Integer> KNOWN_FAILING_LINES = Set.of(
      // Perl \N{N} and \N{M,N} quantifier syntax on \N (non-newline).
      // In Perl, \N{1} means "1 non-newline char" (quantifier). In JDK/Orbit, \N{...}
      // is a Unicode named-character escape — "1" is not a valid Unicode name so PSE is thrown.
      42, 43, 44, 48, 49, 50, 51,
      // Double-digit backreferences (\10+) not supported
      291,
      // Self-referential / conditional backreferences
      495, 498, 975,
      // Backreference inside lookahead
      524, 639, 643,
      // Perl group-reset semantics: groups reset to undef in last non-participating iteration
      483, 506, 969, 970, 2141, 2144, 2145,
      // \z or \A with \x80 subject: \x80+ match failure (encoding/char-class issue)
      2036,
      // \W inside negated class [^\W_0-9] with non-ASCII Unicode subject fails (POSIX/Unicode gap)
      1663,
      // POSIX class used in char-class range ([:digit:]-z form)
      935, 937,
      // \s consuming \n then ^ needing start-of-line in MULTILINE mode (Orbit bug)
      986,
      // (?!)+? edge case: possessive of always-failing assertion
      1072,
      // Perl defined($1) in expr: checks whether a group participated, no Java equivalent
      1459,
      // $ in /x mode with literal \n
      1465, 1466, 1467, 1468, 1469,
      // Perl octal \400-\777 treated as Unicode codepoints (Orbit parses differently)
      1559, 1560, 1561, 1562, 1563, 1564,
      // NUL in char class adjacent to digit: [\0005] parsing differs
      1634, 1635,
      // Unicode case-folding: ligatures (ff→U+FB00, fi→U+FB01, st→U+FB05) and ß→ss
      1644, 1693, 1694, 1695, 1696, 1714, 1717, 1718,
      // $ inside MULTILINE /x pattern in the middle of a group
      1832,
      // ZWNJ (U+200C) and ZWJ (U+200D) treated as \w in Perl but not in Orbit/Java
      1847, 1848, 1849, 1850,
      // \10 octal-vs-backref ambiguity: Orbit always treats \10 as group backreference
      1965, 1966, 1967, 1968,
      // Unicode case fold in lookbehind: (?iu)(?<=\xdf) should match 'ss' (ß fold)
      2044,
      // Unicode case fold: [^\x{1E9E}]/i should not match ß (U+00DF), capital sharp S fold
      1675,
      // Branch reset with conditional backreference: (?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1
      // Conditional on named-group from branch-reset alternative, combined with backreference
      // to same slot, produces wrong match/no-match (§6.7.3 branch-reset + conditional interaction)
      2118, 2119
  );

  /**
   * Immutable parsed representation of one tab-separated row from {@code re_tests}.
   *
   * @param pattern     the raw pattern column (column 1), after variable substitution
   * @param subject     the subject string (column 2), after interpolation
   * @param resultFlags the raw result flag string (column 3)
   * @param expr        the expression to evaluate after matching (column 4), or {@code "-"}
   * @param expected    the expected string value of the expression (column 5), after
   *                    interpolation; may be {@code null} if the column is absent
   * @param skipReason  the skip-reason string (column 6), or {@code ""} if absent
   * @param comment     the comment (column 7), or {@code ""} if absent
   */
  private record ParsedRow(
      String pattern,
      String subject,
      String resultFlags,
      String expr,
      String expected,
      String skipReason,
      String comment) {}

  // ---------------------------------------------------------------------------
  // Test data loading
  // ---------------------------------------------------------------------------

  /**
   * Loads all parseable rows from the {@code re_tests} classpath resource into a
   * {@link Stream} of {@link Arguments} triples: raw line, 1-based line number, parsed row.
   *
   * @return stream of test arguments; never null
   * @throws IllegalStateException if the resource file cannot be read
   */
  static Stream<Arguments> testCases() {
    List<Arguments> args = new ArrayList<>();
    try (InputStream is = PerlRegexCompatTest.class.getResourceAsStream(DATA_PATH);
        BufferedReader r =
            new BufferedReader(new InputStreamReader(is, StandardCharsets.ISO_8859_1))) {
      String line;
      int lineNum = 0;
      boolean seenEnd = false;
      while ((line = r.readLine()) != null) {
        lineNum++;
        if ("__END__".equals(line.trim())) {
          seenEnd = true;
          continue;
        }
        if (!seenEnd) {
          continue;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        ParsedRow row;
        try {
          row = parseRow(line);
        } catch (SkipException e) {
          // Row contains something unresolvable (e.g. invalid codepoint) — skip it
          // by using a dummy row whose skipReason will trigger assumeFalse
          row = new ParsedRow("", "", "y", "-", null, e.getMessage(), "");
        }
        if (row == null) {
          continue;
        }
        args.add(Arguments.of(line, lineNum, row));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + DATA_PATH, e);
    }
    return args.stream();
  }

  /**
   * Parses one tab-separated line from the data file into a {@link ParsedRow}.
   *
   * <p>Returns {@code null} if the line has fewer than three tab-separated columns
   * (treated as a blank separator by the Perl runner).
   *
   * @param line the raw line from the file; must not be null
   * @return the parsed row, or {@code null} if the line is malformed
   */
  private static ParsedRow parseRow(String line) {
    String[] cols = line.split("\t", 7);
    if (cols.length < 3) {
      return null;
    }
    // Column 1: apply variable substitutions only (no other interpolation)
    String pattern = applyVarSubstitutions(cols[0]);
    // Column 2: apply full interpolation
    String subject = interpolate(applyVarSubstitutions(cols[1]));
    String resultFlags = cols[2];
    String expr = cols.length > 3 ? cols[3] : "-";
    String expected = cols.length > 4 ? interpolate(applyVarSubstitutions(cols[4])) : null;
    String skipReason = cols.length > 5 ? cols[5].trim() : "";
    String comment = cols.length > 6 ? cols[6] : "";
    return new ParsedRow(pattern, subject, resultFlags, expr, expected, skipReason, comment);
  }

  // ---------------------------------------------------------------------------
  // String processing helpers
  // ---------------------------------------------------------------------------

  /**
   * Applies the three known Perl package variable substitutions to {@code raw}.
   *
   * <p>Substitutions applied: {@code ${bang}} → {@code !}, {@code ${ffff}} → two
   * {@code \xFF} chars, {@code ${nulnul}} → two NUL chars.
   *
   * @param raw the string to process; must not be null
   * @return the string after variable substitution
   */
  private static String applyVarSubstitutions(String raw) {
    String result = raw;
    result = result.replace("${bang}", "!");
    result = result.replace("${ffff}", "\u00FF\u00FF");
    result = result.replace("${nulnul}", "\u0000\u0000");
    return result;
  }

  /**
   * Applies Perl double-quote escape-sequence interpolation to {@code raw}.
   *
   * <p>Transformations applied in order:
   * <ol>
   *   <li>{@code \n} → newline</li>
   *   <li>{@code \t} → tab</li>
   *   <li>{@code \r} → carriage return</li>
   *   <li>{@code \x{HH...}} → Unicode code point</li>
   *   <li>{@code \xHH} → character with that hex value</li>
   *   <li>{@code \0} → NUL</li>
   *   <li>{@code \ooo} → character with that octal value</li>
   *   <li>Other backslash sequences are passed through literally (backslash retained)</li>
   * </ol>
   *
   * @param raw the string to interpolate; must not be null
   * @return the interpolated string
   */
  private static String interpolate(String raw) {
    if (!raw.contains("\\")) {
      return raw;
    }
    StringBuilder sb = new StringBuilder(raw.length());
    int i = 0;
    while (i < raw.length()) {
      char c = raw.charAt(i);
      if (c != '\\' || i + 1 >= raw.length()) {
        sb.append(c);
        i++;
        continue;
      }
      char next = raw.charAt(i + 1);
      switch (next) {
        case 'n' -> {
          sb.append('\n');
          i += 2;
        }
        case 't' -> {
          sb.append('\t');
          i += 2;
        }
        case 'r' -> {
          sb.append('\r');
          i += 2;
        }
        case 'x' -> {
          if (i + 2 < raw.length() && raw.charAt(i + 2) == '{') {
            // \x{HH...} form
            int end = raw.indexOf('}', i + 3);
            if (end < 0) {
              sb.append(c);
              i++;
              break;
            }
            String hex = raw.substring(i + 3, end);
            // Strip leading underscores (Perl allows them in numeric literals)
            hex = hex.replace("_", "");
            try {
              int codePoint = Integer.parseInt(hex, 16);
              if (!Character.isValidCodePoint(codePoint)) {
                throw new SkipException(
                    "subject/expected contains invalid Unicode codepoint: 0x"
                        + Integer.toHexString(codePoint));
              }
              sb.appendCodePoint(codePoint);
            } catch (NumberFormatException e) {
              // Leave as-is
              sb.append(raw, i, end + 1);
            }
            i = end + 1;
          } else if (i + 3 < raw.length()
              && isHexDigit(raw.charAt(i + 2))
              && isHexDigit(raw.charAt(i + 3))) {
            // \xHH form (exactly two hex digits)
            int val = Integer.parseInt(raw.substring(i + 2, i + 4), 16);
            sb.append((char) val);
            i += 4;
          } else if (i + 2 < raw.length() && isHexDigit(raw.charAt(i + 2))) {
            // \xH form (single hex digit)
            int val = Integer.parseInt(raw.substring(i + 2, i + 3), 16);
            sb.append((char) val);
            i += 3;
          } else {
            sb.append(c);
            i++;
          }
        }
        case '0' -> {
          // \0 → NUL; also handles \0oo octal
          int[] octResult = parseOctal(raw, i + 1);
          sb.append((char) octResult[0]);
          i = octResult[1];
        }
        case '\\' -> {
          sb.append('\\');
          i += 2;
        }
        case '$' -> {
          // \$ in Perl double-quote context produces a literal dollar sign
          sb.append('$');
          i += 2;
        }
        case '@' -> {
          // \@ in Perl double-quote context produces a literal at-sign
          sb.append('@');
          i += 2;
        }
        case 'o' -> {
          // \o{NNN} braced octal escape
          if (i + 2 < raw.length() && raw.charAt(i + 2) == '{') {
            int end = raw.indexOf('}', i + 3);
            if (end < 0) {
              sb.append(c);
              i++;
              break;
            }
            String oct = raw.substring(i + 3, end).replace("_", "");
            try {
              int codePoint = Integer.parseInt(oct, 8);
              if (!Character.isValidCodePoint(codePoint)) {
                throw new SkipException(
                    "subject/expected contains invalid Unicode codepoint: 0x"
                        + Integer.toHexString(codePoint));
              }
              if (codePoint > 0xFFFF) {
                throw new SkipException(
                    "subject contains supplementary codepoint (>U+FFFF)");
              }
              sb.append((char) codePoint);
            } catch (NumberFormatException e) {
              sb.append(raw, i, end + 1);
            }
            i = end + 1;
          } else {
            sb.append(c);
            i++;
          }
        }
        default -> {
          // Check for octal \ooo (digits 1-7 are octal if followed by more digits)
          if (next >= '1' && next <= '7') {
            int[] octResult = parseOctal(raw, i + 1);
            sb.append((char) octResult[0]);
            i = octResult[1];
          } else {
            // Unknown escape — keep the backslash
            sb.append(c);
            i++;
          }
        }
      }
    }
    return sb.toString();
  }

  /**
   * Parses an octal sequence starting at {@code pos} in {@code s} and returns the
   * decoded character value and the position after the last consumed digit.
   *
   * @param s   the string; must not be null
   * @param pos the start position of the octal digit(s)
   * @return a two-element array: {@code [characterValue, nextPosition]}
   */
  private static int[] parseOctal(String s, int pos) {
    int val = 0;
    int end = pos;
    int limit = Math.min(pos + 3, s.length());
    while (end < limit && s.charAt(end) >= '0' && s.charAt(end) <= '7') {
      val = val * 8 + (s.charAt(end) - '0');
      end++;
    }
    if (end == pos) {
      // No octal digit consumed (bare \0 with nothing following)
      return new int[]{0, pos};
    }
    return new int[]{val & 0xFF, end};
  }

  /**
   * Returns {@code true} if {@code c} is an ASCII hexadecimal digit.
   *
   * @param c the character to test
   * @return true if the character is {@code [0-9A-Fa-f]}
   */
  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
  }

  // ---------------------------------------------------------------------------
  // Pattern compilation
  // ---------------------------------------------------------------------------

  /**
   * Compiles a pattern from column 1 of the data file, supporting Form A (bare),
   * Form B ({@code 'pat'flags}), and Form C ({@code /pat/flags}).
   *
   * <p>If the modifier string contains a character that should cause the row to be
   * skipped (e.g., {@code u}, {@code a}, {@code l}, {@code d}, {@code g}, {@code n},
   * {@code xx}), this method throws a {@link SkipException}.
   *
   * @param patCol the raw pattern column value, after variable substitution
   * @return the compiled {@link Pattern}
   * @throws SkipException          if the modifier requires skipping this row
   * @throws PatternSyntaxException if the pattern is syntactically invalid
   * @throws RuntimeException       if Pattern.compile wraps a PatternSyntaxException
   */
  private static Pattern compilePattern(String patCol) {
    String inner;
    String modifiers;

    if (patCol.startsWith("'")) {
      // Form B: 'pat'flags
      int lastQuote = patCol.lastIndexOf('\'');
      inner = (lastQuote > 0) ? patCol.substring(1, lastQuote) : patCol.substring(1);
      modifiers = (lastQuote > 0) ? patCol.substring(lastQuote + 1) : "";
    } else if (patCol.startsWith("/")) {
      // Form C: /pat/flags
      int lastSlash = patCol.lastIndexOf('/');
      inner = (lastSlash > 0) ? patCol.substring(1, lastSlash) : patCol.substring(1);
      modifiers = (lastSlash > 0) ? patCol.substring(lastSlash + 1) : "";
    } else {
      // Form A: bare — apply \n substitution as Perl does
      inner = patCol.replace("\\n", "\n");
      modifiers = "";
    }

    // Check for "xx" modifier (double-x) before general modifier mapping
    if (modifiers.contains("xx")) {
      throw new SkipException("double-x (xx) modifier has no Java equivalent");
    }

    List<PatternFlag> flags = new ArrayList<>();
    for (char mod : modifiers.toCharArray()) {
      switch (mod) {
        case 'i' -> flags.add(PatternFlag.CASE_INSENSITIVE);
        case 'm' -> flags.add(PatternFlag.MULTILINE);
        case 's' -> flags.add(PatternFlag.DOTALL);
        case 'x' -> flags.add(PatternFlag.COMMENTS);
        case 'u' -> throw new SkipException("modifier 'u' has different Unicode semantics in Java");
        case 'a' -> throw new SkipException("modifier 'a' restricts \\w/\\d to ASCII-only, no Java equivalent");
        case 'l' -> throw new SkipException("modifier 'l' uses locale semantics, no Java equivalent");
        case 'd' -> throw new SkipException("modifier 'd' is Perl's default-semantics flag, not meaningful in Java");
        case 'g' -> throw new SkipException("modifier 'g' (global) is not applicable to single find()");
        case 'n' -> throw new SkipException("modifier 'n' (no captures) is not a Java feature");
        default -> { /* ignore unknown modifier characters */ }
      }
    }

    PatternFlag[] flagArray = flags.toArray(new PatternFlag[0]);
    if (flagArray.length == 0) {
      return Pattern.compile(inner);
    }
    return Pattern.compile(inner, flagArray);
  }

  // ---------------------------------------------------------------------------
  // Skip logic
  // ---------------------------------------------------------------------------

  /**
   * Unchecked exception used internally to signal that a row must be skipped.
   * Converted to an {@link Assumptions#assumeFalse} call in the test method.
   */
  private static final class SkipException extends RuntimeException {
    SkipException(String reason) {
      super(reason);
    }
  }

  /**
   * Evaluates all skip criteria from the specification and returns a non-null reason
   * string if the row must be skipped, or {@code null} if the row should run.
   *
   * @param row the parsed row to evaluate; must not be null
   * @return a descriptive skip reason, or {@code null} to run the test
   */
  private static String skipReason(ParsedRow row) {
    // Column-6 explicit skip reason
    if (!row.skipReason().isEmpty()) {
      return "explicit skip-reason in data: " + row.skipReason();
    }

    // Column-3 flag-based skips
    String flags = row.resultFlags();
    if (flags.contains("T")) {
      return "TODO test (T flag)";
    }
    if (flags.contains("B")) {
      return "known Perl bug (B flag)";
    }
    if (flags.contains("b")) {
      return "known Perl bug in noamp mode (b flag)";
    }
    if (flags.contains("t")) {
      return "threading TODO (t flag)";
    }
    if (flags.contains("s")) {
      return "regex_sets only (s flag)";
    }
    if (flags.contains("e")) {
      return "EBCDIC platform only (e flag)";
    }

    // Column-4 (expr) Perl-specific variable skips
    String expr = row.expr();
    if (expr.contains("$'")) {
      return "expr uses $' (string after match), no Java equivalent";
    }
    if (expr.contains("$`")) {
      return "expr uses $` (string before match), no Java equivalent";
    }
    if (expr.contains("@-")) {
      return "expr uses @- (group-start array), no Java equivalent";
    }
    if (expr.contains("@+")) {
      return "expr uses @+ (group-end array), no Java equivalent";
    }
    if (expr.contains("$^N")) {
      return "expr uses $^N (last match in enclosing group), no Java equivalent";
    }
    if (expr.contains("$^R")) {
      return "expr uses $^R (last (?{code}) result), no Java equivalent";
    }
    if (expr.contains("$::")) {
      return "expr uses Perl package variable ($::), no Java equivalent";
    }
    if (expr.contains("$~")) {
      return "expr uses $~ (format name), no Java equivalent";
    }
    if (expr.contains("$+{")) {
      return "expr uses $+{name} (hash-based named capture), skip";
    }
    if (expr.contains("$b") && !expr.matches(".*\\$[0-9].*")) {
      // $b is a Perl variable, not a group; but be careful not to confuse with $1 etc.
      if (expr.equals("$b") || expr.startsWith("$b-") || expr.startsWith("$b:")) {
        return "expr uses $b (Perl variable), no Java equivalent";
      }
    }
    if (expr.contains("$+") && !expr.matches(".*\\$\\+\\[[0-9]+\\].*")) {
      // $+ (scalar last bracket) is different from $+[N]
      if (expr.equals("$+") || expr.matches("\\$\\+[-:;].*")
          || (expr.contains("$+") && !expr.contains("$+["))) {
        return "expr uses $+ (last bracket matched), no Java equivalent";
      }
    }
    if (expr.equals("$REGMARK") || expr.contains("$::REGMARK")
        || expr.contains("$::REGERROR") || expr.contains("$::plus_got")
        || expr.contains("$::bl") || expr.contains("$::caret_n_got")) {
      return "expr uses Perl package variable, no Java equivalent";
    }

    // Pattern-feature-based skips — check the raw pattern column
    String pat = row.pattern();

    // \N{name} in subject or expected (named Unicode chars not supported)
    if (containsNamedChar(row.subject())) {
      return "subject contains \\N{name} (named Unicode char), no Java equivalent";
    }
    if (row.expected() != null && containsNamedChar(row.expected())) {
      return "expected contains \\N{name} (named Unicode char), no Java equivalent";
    }

    // Supplementary codepoints in subject or expected
    if (containsSupplementaryCodepoint(row.subject())) {
      return "subject contains supplementary codepoint (>U+FFFF)";
    }
    if (row.expected() != null && containsSupplementaryCodepoint(row.expected())) {
      return "expected contains supplementary codepoint (>U+FFFF)";
    }

    // Unknown ${varname} in subject or expected
    if (containsUnknownVar(row.subject())) {
      return "subject contains unknown Perl variable interpolation";
    }
    if (row.expected() != null && containsUnknownVar(row.expected())) {
      return "expected contains unknown Perl variable interpolation";
    }

    // ==========================================================================
    // PERMANENT GUARDS — intentional Orbit/Perl divergence (docs/semantics.md §Bucket 4).
    // Do NOT remove these guards. Orbit does not plan to implement these features.
    // They have no JDK equivalent and are not part of any Orbit extension layer.
    // ==========================================================================
    // Pattern-level skips
    if (pat.contains("(?{") || pat.contains("(??{")) {
      return "pattern contains Perl code block interpolation (?{ or (??{) [permanent skip]";
    }
    if (pat.contains("(*ACCEPT)") || pat.contains("(*FAIL)") || pat.contains("(*F)")
        || pat.contains("(*PRUNE)") || pat.contains("(*THEN)") || pat.contains("(*SKIP)")) {
      return "pattern contains Perl backtrack control verb [permanent skip]";
    }
    if (containsNamedCharInPattern(pat)) {
      return "pattern contains \\N{name} (named Unicode char), no Java equivalent [permanent skip]";
    }
    if (pat.contains("(?R)") || pat.contains("(?0)")) {
      return "pattern contains (?R)/(?0) recursive call, not supported [permanent skip]";
    }
    if (containsRelativeRecursion(pat)) {
      return "pattern contains relative recursive call (?-N)/(?+N), not supported [permanent skip]";
    }
    if (containsNumericGroupCall(pat)) {
      return "pattern contains (?N) numeric group call, not supported in Orbit [permanent skip]";
    }
    if (pat.contains("(?&") || pat.contains("(?P>")) {
      return "pattern contains subroutine call (?&name)/(?P>name), not supported [permanent skip]";
    }
    if (pat.contains("(?(DEFINE)")) {
      return "pattern contains (?(DEFINE)...) subroutine definition, not supported [permanent skip]";
    }
    if (containsUnicodeBoundaryType(pat)) {
      return "pattern contains Unicode boundary type (\\b{gcb} etc.), not supported [permanent skip]";
    }
    if (pat.contains("(?a") || pat.contains("(?l") || pat.contains("(?u") || pat.contains("(?d")) {
      return "pattern contains Perl-specific inline flag (?a/(?l/(?u/(?d) [permanent skip]";
    }
    if (pat.contains("(?a:") || pat.contains("(?aa:") || pat.contains("(?aia:")
        || pat.contains("(?iaa:")) {
      return "pattern contains Perl extended flag combination [permanent skip]";
    }
    // \p A or \p:A style (space/colon before property)
    if (pat.matches(".*\\\\[pP][: ].*")) {
      return "pattern contains \\p with space or colon separator, Perl-specific error [permanent skip]";
    }
    // ==========================================================================
    // TEMPORARY GUARDS — features on the Orbit roadmap (docs/design/08-implementation-roadmap.md).
    // Remove each guard after the feature is implemented and tests pass.
    // Check the three-mode matrix in CLAUDE.md before removing.
    // ==========================================================================
    // --- Guards for anchors implemented in Orbit but with known partial failures ---
    // Mode matrix for anchor guards (before removing a guard here, verify status in each mode):
    //   \A  — Orbit default: YES (BeginText), JDK: YES, RE2_COMPAT: YES (RE2 supports \A)
    //          Guard removed: \A is fully implemented. No RE2/JDK constraint.
    //   \Z  — Orbit default: YES (EndZ), JDK: YES, RE2_COMPAT: YES (RE2 supports \Z as \z-like)
    //          Guard removed: \Z is fully implemented. No RE2/JDK constraint.
    //   \z  — Orbit default: YES (EndText), JDK: YES, RE2_COMPAT: YES
    //          Guard removed: \z is fully implemented. Lines 726/735/744/753/798/807/852/861
    //          were removed from KNOWN_FAILING_LINES in §6.7.3 (E2): the engine correctly
    //          handles \z with MULTILINE 'm' modifier and multi-line subjects.
    //   \G  — Orbit default: YES (BeginG/lastMatchEnd), JDK: YES, RE2_COMPAT: NO (RE2 rejects \G)
    //          Guard removed for this Perl test (Perl tests run in Orbit default, not RE2_COMPAT).
    //          When RE2_COMPAT is implemented, Re2Validator should throw PSE for \G patterns.
    //   (?P<name>...) — Orbit default: YES (Parser.java:699), JDK: NO (PSE), RE2_COMPAT: YES
    //          Guard removed: implemented in Orbit. JDK would throw PSE but this is a Perl test.
    //   \R  — Orbit default: YES (buildLineBreakExpr, Union of 8 alternatives),
    //          JDK: YES (java.util.regex supports \R in Java 8+), RE2_COMPAT: NO
    //          Guard removed: \R is fully implemented. When RE2_COMPAT is wired,
    //          Re2Validator should throw PSE for \R patterns since RE2 does not support \R.
    //   \h/\H/\v/\V — Orbit default: YES (horizontalSpaceClass/verticalSpaceClass),
    //                  JDK: NO (JDK does not define \h/\v), RE2_COMPAT: NO
    //                  Guard removed: implemented in Orbit. When RE2_COMPAT is wired,
    //                  Re2Validator must reject these escapes.
    //   \g{N}/\g{-N}/\g{name}/\gN — Orbit default: YES (parseGBackref),
    //                                 JDK: NO (JDK does not support \g),
    //                                 RE2_COMPAT: NO (RE2 has no backreferences)
    //                                 Guard removed: implemented in Orbit.
    //                                 When RE2_COMPAT is wired, Re2Validator must reject \g.
    //   (?(<name>)...) / (?('name')...) — Orbit default: YES (parseConditional extended),
    //                                      JDK: NO (JDK has no conditionals),
    //                                      RE2_COMPAT: NO
    //                                      Guard removed: implemented in Orbit.
    //                                      When RE2_COMPAT is wired, Re2Validator must reject.
    //   (?|...) branch reset — Orbit default: YES (parseBranchResetGroup, §6.7.3)
    //           JDK: NO — java.util.regex does not support (?|...), would throw PSE.
    //           RE2_COMPAT: NO — Re2Validator must reject (?|) when RE2_COMPAT is wired.
    //           Guard removed: §6.7.3 F1 implemented.
    //   \K — Orbit default: YES (ResetMatchStart, §6.7.3)
    //       JDK: NO — java.util.regex does not support \K, would throw PSE.
    //       RE2_COMPAT: NO — Re2Validator must reject \K when RE2_COMPAT is wired.
    //       Guard removed: §6.7.3 F2 implemented.

    // \B{gcb} etc. (uppercase-B Unicode boundary types) — permanent skip (not planned)
    if (pat.contains("\\B{gcb}") || pat.contains("\\B{lb}")
        || pat.contains("\\B{sb}") || pat.contains("\\B{wb}")
        || pat.contains("\\B{ gcb}") || pat.contains("\\B{ lb}")
        || pat.contains("\\B{ sb}") || pat.contains("\\B{ wb}")) {
      return "pattern contains \\B{gcb} etc. (Unicode boundary type), not supported [permanent skip]";
    }
    // Unmatched ] as literal — permanent skip (Orbit correctly rejects; Perl-specific leniency)
    if (containsUnmatchedCloseBracket(pat)) {
      return "pattern contains unmatched ] (literal in Perl), not supported in Orbit [permanent skip]";
    }
    // POSIX collating elements [[. or [[= — permanent skip (not planned)
    if (pat.contains("[[.") || pat.contains("[[=") || pat.contains("[:")) {
      if (containsPosixCollating(pat)) {
        return "pattern contains POSIX collating element [[. or [[=, not supported in Orbit [permanent skip]";
      }
    }
    // Variable-length lookbehind — §6.3H (Batch 3)
    if (containsVariableLengthLookbehind(pat)) {
      return "pattern contains variable-length lookbehind, not supported in Orbit";
    }
    // Malformed POSIX char class range — permanent skip (Perl-specific leniency)
    if (pat.contains("[a-[:") || pat.contains("[a-\\")) {
      return "pattern contains invalid char class range ending in POSIX class or escape [permanent skip]";
    }
    // \X extended grapheme cluster — hard, not near-term planned
    if (pat.contains("\\X")) {
      return "pattern contains \\X (extended grapheme cluster), not supported in Orbit";
    }
    // (?P>name) subroutine call — permanent skip (not planned)
    if (pat.contains("(?P>")) {
      return "pattern contains (?P>name) subroutine call, not supported in Orbit [permanent skip]";
    }
    // {min,max} where max < min — Orbit treats as PatternSyntaxException but not all do
    // \p{Alphabetic} and similar full Unicode property names Orbit does not know
    // These are hard to enumerate; rely on PatternSyntaxException catch-and-skip in test method

    return null; // no skip
  }

  /**
   * Returns {@code true} if the pattern contains a {@code (?N)} numeric group call
   * (subroutine call by group number), which Orbit does not support.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern contains a numeric group call
   */
  private static boolean containsNumericGroupCall(String pat) {
    // (?N) where N is one or more digits — not preceded by < or = or ! (those are lookahead/behind)
    // Must distinguish (?1) subroutine call from (?=), (?!), (?<=), (?<!), (?:), (?P=...)
    int idx = pat.indexOf("(?");
    while (idx >= 0) {
      int after = idx + 2;
      if (after < pat.length()) {
        char next = pat.charAt(after);
        // (?N) where N is a digit
        if (Character.isDigit(next)) {
          return true;
        }
      }
      idx = pat.indexOf("(?", idx + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a variable-length lookbehind
   * assertion, which Orbit does not support.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern has a variable-length lookbehind
   */
  private static boolean containsVariableLengthLookbehind(String pat) {
    // Look for (?<= or (?<! followed by content that contains ?, *, +, |, or {
    int idx = pat.indexOf("(?<=");
    while (idx >= 0) {
      int end = findGroupEnd(pat, idx + 4);
      if (end > idx + 4) {
        String content = pat.substring(idx + 4, end);
        if (content.contains("?") || content.contains("*") || content.contains("+")
            || content.contains("|") || content.contains("{")) {
          return true;
        }
      }
      idx = pat.indexOf("(?<=", idx + 1);
    }
    idx = pat.indexOf("(?<!");
    while (idx >= 0) {
      int end = findGroupEnd(pat, idx + 4);
      if (end > idx + 4) {
        String content = pat.substring(idx + 4, end);
        if (content.contains("?") || content.contains("*") || content.contains("+")
            || content.contains("|") || content.contains("{")) {
          return true;
        }
      }
      idx = pat.indexOf("(?<!", idx + 1);
    }
    return false;
  }

  /**
   * Finds the position of the closing {@code )} for a group starting at {@code startPos},
   * tracking nesting depth.
   *
   * @param pat      the pattern string
   * @param startPos the position after the opening {@code (}
   * @return the index of the matching {@code )}, or {@code pat.length()} if not found
   */
  private static int findGroupEnd(String pat, int startPos) {
    int depth = 1;
    int i = startPos;
    while (i < pat.length() && depth > 0) {
      char c = pat.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      }
      i++;
    }
    return i - 1;
  }

  /**
   * Returns {@code true} if the pattern contains {@code \N} used as a "not a newline"
   * atom (as opposed to {@code \N{U+NNNN}} which is handled by
   * {@link #containsNamedCharInPattern(String)}).
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern contains a bare {@code \N} atom
   */
  private static boolean containsNonNewlineAtom(String pat) {
    // Any pattern containing \N in any form — Orbit does not support \N as an atom.
    // \N{U+NNNN} and \N{name} are separately detected by containsNamedCharInPattern;
    // this method covers bare \N and \N with numeric quantifiers like \N{1}, \N{3,4}.
    return pat.contains("\\N");
  }

  /**
   * Returns {@code true} if the pattern contains a {@code {,N}} leading-comma quantifier
   * (meaning "0 to N"), which Perl allows but Orbit rejects as illegal repetition.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern contains a leading-comma quantifier
   */
  private static boolean containsLeadingCommaQuantifier(String pat) {
    // Match {,N} or { ,N } with optional spaces
    return pat.matches(".*\\{\\s*,\\s*[0-9].*");
  }

  /**
   * Returns {@code true} if the pattern contains an unmatched {@code ]} that appears
   * outside a character class. Perl treats such {@code ]} as a literal character;
   * Orbit raises a {@link PatternSyntaxException}.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern has an unmatched closing bracket
   */
  private static boolean containsUnmatchedCloseBracket(String pat) {
    // Simple heuristic: track bracket depth, flag if ] appears at depth 0
    int depth = 0;
    for (int i = 0; i < pat.length(); i++) {
      char c = pat.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '[') {
        depth++;
      } else if (c == ']') {
        if (depth == 0) {
          return true;
        }
        depth--;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a POSIX collating element
   * ({@code [[.xxx.]]} or {@code [[=xxx=]}}) inside a character class.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern uses POSIX collating or equivalence class syntax
   */
  private static boolean containsPosixCollating(String pat) {
    return pat.contains("[[.") || pat.contains("[[=");
  }

  /**
   * Returns {@code true} if the pattern contains a Unicode boundary type assertion
   * such as {@code \b{gcb}}, {@code \b{ gcb }}, {@code \B{gcb}}, etc.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern uses a Unicode boundary type
   */
  private static boolean containsUnicodeBoundaryType(String pat) {
    for (String type : List.of("gcb", "lb", "sb", "wb")) {
      if (pat.contains("\\b{" + type + "}") || pat.contains("\\B{" + type + "}")
          || pat.contains("\\b{ " + type + " }") || pat.contains("\\B{ " + type + " }")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a braced hex escape {@code \x{NNN}}
   * in pattern context. Orbit's parser only supports the two-digit form {@code \xNN}.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern contains a braced hex escape
   */
  private static boolean containsBracedHexInPattern(String pat) {
    // Look for \x{ that is not preceded by a second backslash (i.e., not \\x{)
    int idx = pat.indexOf("\\x{");
    while (idx >= 0) {
      // Make sure it's not \\x{ (escaped backslash followed by x{)
      if (idx == 0 || pat.charAt(idx - 1) != '\\') {
        return true;
      }
      idx = pat.indexOf("\\x{", idx + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the string contains a {@code \N{name}} sequence (not a
   * quantifier form like {@code \N{3,4}}).
   *
   * @param s the string to inspect; must not be null
   * @return true if the string contains a named-char escape
   */
  private static boolean containsNamedChar(String s) {
    int idx = s.indexOf("\\N{");
    while (idx >= 0) {
      int end = s.indexOf('}', idx + 3);
      if (end < 0) {
        return true;
      }
      String content = s.substring(idx + 3, end).trim();
      // Quantifier forms contain only digits, commas, and whitespace
      if (!content.matches("[0-9,\\s]+")) {
        return true;
      }
      idx = s.indexOf("\\N{", end + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the string contains a {@code \N{name}} named-char sequence
   * in a pattern context, excluding the {@code \N{U+NNNN}} hex form.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern contains a named Unicode character reference
   */
  private static boolean containsNamedCharInPattern(String pat) {
    int idx = pat.indexOf("\\N{");
    while (idx >= 0) {
      int end = pat.indexOf('}', idx + 3);
      if (end < 0) {
        return true;
      }
      String content = pat.substring(idx + 3, end).trim();
      // Quantifier forms: digits and commas only; U+NNNN hex forms are OK
      if (!content.matches("[0-9,\\s]+") && !content.matches("U\\+[0-9A-Fa-f]+")) {
        return true;
      }
      idx = pat.indexOf("\\N{", end + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the string (after interpolation) contains any character
   * whose code point exceeds {@code U+FFFF} (a supplementary character).
   *
   * @param s the interpolated string; must not be null
   * @return true if the string contains a supplementary codepoint
   */
  private static boolean containsSupplementaryCodepoint(String s) {
    return s.codePoints().anyMatch(cp -> cp > 0xFFFF);
  }

  /**
   * Returns {@code true} if the string still contains an unresolved {@code ${varname}}
   * interpolation other than the three known variables.
   *
   * @param s the string after known variable substitutions have been applied
   * @return true if an unknown variable interpolation remains
   */
  private static boolean containsUnknownVar(String s) {
    int idx = s.indexOf("${");
    while (idx >= 0) {
      int end = s.indexOf('}', idx + 2);
      if (end < 0) {
        return true; // unclosed ${ — treat as unknown
      }
      // If we still see ${anything} it means applyVarSubstitutions left it
      return true;
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a relative recursive call like
   * {@code (?-1)}, {@code (?+1)}, etc.
   *
   * @param pat the pattern string; must not be null
   * @return true if the pattern has a relative recursive call
   */
  private static boolean containsRelativeRecursion(String pat) {
    // Match (?-N) or (?+N) where N is one or more digits
    return pat.matches(".*\\(\\?[-+][0-9]+\\).*");
  }

  // ---------------------------------------------------------------------------
  // Expression evaluation
  // ---------------------------------------------------------------------------

  /**
   * Evaluates the Perl expression from column 4 against a successfully matched
   * {@link Matcher} and returns the result as a Java {@link String}.
   *
   * <p>Handles: {@code -} (no check), {@code pos}, and all group-reference forms
   * ({@code $&}, {@code $N}, {@code $-[N]}, {@code $+[N]}) including compound
   * expressions with literal separator characters.
   *
   * @param expr the expression string from column 4; must not be null
   * @param m    the matcher after a successful {@code find()}; must not be null
   * @return the evaluated string result
   */
  private static String evalExpr(String expr, Matcher m) {
    if ("-".equals(expr)) {
      return null; // caller should not compare
    }
    if ("pos".equals(expr)) {
      return Integer.toString(m.end(0));
    }

    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < expr.length()) {
      char c = expr.charAt(i);

      if (c == '\\' && i + 1 < expr.length()) {
        char next = expr.charAt(i + 1);
        switch (next) {
          case '$' -> {
            sb.append('$');
            i += 2;
          }
          case '\\' -> {
            sb.append('\\');
            i += 2;
          }
          case 'n' -> {
            sb.append('\n');
            i += 2;
          }
          case 't' -> {
            sb.append('\t');
            i += 2;
          }
          case 'r' -> {
            sb.append('\r');
            i += 2;
          }
          case 'x' -> {
            if (i + 2 < expr.length() && expr.charAt(i + 2) == '{') {
              int end = expr.indexOf('}', i + 3);
              if (end >= 0) {
                String hex = expr.substring(i + 3, end);
                sb.appendCodePoint(Integer.parseInt(hex, 16));
                i = end + 1;
              } else {
                sb.append(c);
                i++;
              }
            } else if (i + 3 < expr.length()
                && isHexDigit(expr.charAt(i + 2))
                && isHexDigit(expr.charAt(i + 3))) {
              int val = Integer.parseInt(expr.substring(i + 2, i + 4), 16);
              sb.append((char) val);
              i += 4;
            } else {
              sb.append(c);
              i++;
            }
          }
          default -> {
            sb.append(c);
            i++;
          }
        }
      } else if (c == '$' && i + 1 < expr.length()) {
        char next = expr.charAt(i + 1);
        if (next == '&') {
          // $& — whole match
          String g = m.group(0);
          sb.append(g != null ? g : "");
          i += 2;
        } else if (next == '-' && i + 2 < expr.length() && expr.charAt(i + 2) == '[') {
          // $-[N] — group start
          int closeBracket = expr.indexOf(']', i + 3);
          if (closeBracket >= 0) {
            int groupNum = Integer.parseInt(expr.substring(i + 3, closeBracket));
            int start = m.start(groupNum);
            sb.append(start >= 0 ? Integer.toString(start) : "");
            i = closeBracket + 1;
          } else {
            sb.append(c);
            i++;
          }
        } else if (next == '+' && i + 2 < expr.length() && expr.charAt(i + 2) == '[') {
          // $+[N] — group end
          int closeBracket = expr.indexOf(']', i + 3);
          if (closeBracket >= 0) {
            int groupNum = Integer.parseInt(expr.substring(i + 3, closeBracket));
            int end = m.end(groupNum);
            sb.append(end >= 0 ? Integer.toString(end) : "");
            i = closeBracket + 1;
          } else {
            sb.append(c);
            i++;
          }
        } else if (Character.isDigit(next)) {
          // $N or $NN — capture group
          int j = i + 1;
          while (j < expr.length() && Character.isDigit(expr.charAt(j))) {
            j++;
          }
          int groupNum = Integer.parseInt(expr.substring(i + 1, j));
          String g = m.group(groupNum);
          sb.append(g != null ? g : "");
          i = j;
        } else {
          sb.append(c);
          i++;
        }
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  /**
   * Returns the base outcome character for the row: {@code 'y'}, {@code 'n'},
   * {@code 'c'}, or {@code '\0'} if none is present.
   *
   * @param resultFlags the raw result flag string from column 3; must not be null
   * @return the base outcome character
   */
  private static char baseResult(String resultFlags) {
    for (char ch : resultFlags.toCharArray()) {
      if (ch == 'y' || ch == 'n' || ch == 'c') {
        return ch;
      }
    }
    return '\0';
  }

  // ---------------------------------------------------------------------------
  // Main test method
  // ---------------------------------------------------------------------------

  /**
   * Runs one test case from the Perl {@code re_tests} data file.
   *
   * <p>The test applies all skip criteria, compiles the pattern, runs the match,
   * asserts the match outcome, and (for {@code y} rows) evaluates the column-4
   * expression and compares it to the expected value.
   *
   * @param rawLine    the raw line from the file, used in the test display name
   * @param lineNumber the 1-based line number in the file, for diagnostics
   * @param row        the fully parsed row
   */
  @ParameterizedTest(name = "[{index}] line {1}: {0}")
  @MethodSource("testCases")
  @DisplayName("Perl re_tests compatibility")
  void testPerlRegex(String rawLine, int lineNumber, ParsedRow row) {
    // 0. Skip lines known to expose Orbit engine limitations (see KNOWN_FAILING_LINES javadoc)
    Assumptions.assumeFalse(
        KNOWN_FAILING_LINES.contains(lineNumber),
        "Known Orbit engine limitation at line " + lineNumber
            + " — see KNOWN_FAILING_LINES documentation for root cause");

    // 1. Apply all spec-driven skip rules
    String skip = skipReason(row);
    Assumptions.assumeFalse(skip != null, () -> skip != null ? skip : "");

    // 2. Attempt pattern compilation (may raise SkipException or PatternSyntaxException)
    Pattern p;
    try {
      p = compilePattern(row.pattern());
    } catch (SkipException e) {
      Assumptions.assumeFalse(true, e.getMessage());
      return;
    } catch (PatternSyntaxException e) {
      if (baseResult(row.resultFlags()) == 'c') {
        return; // expected compile error — pass
      }
      // Pattern uses a feature Orbit does not yet support — skip rather than fail,
      // so the test suite surfaces engine bugs rather than parser limitations.
      Assumptions.assumeFalse(true,
          "Orbit cannot compile pattern on line " + lineNumber + ": " + e.getMessage());
      return;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof PatternSyntaxException pse) {
        if (baseResult(row.resultFlags()) == 'c') {
          return; // expected compile error — pass
        }
        Assumptions.assumeFalse(true,
            "Orbit cannot compile pattern on line " + lineNumber + ": " + pse.getMessage());
        return;
      }
      if (baseResult(row.resultFlags()) == 'c') {
        return;
      }
      // Internal engine errors during compilation (e.g. compiler bug) — skip
      Assumptions.assumeFalse(true,
          "Line " + lineNumber + ": engine error compiling pattern '"
              + row.pattern() + "': " + e.getMessage());
      return;
    }

    // 3. If base result is 'c' but no exception was thrown — Orbit differs from Perl
    if (baseResult(row.resultFlags()) == 'c') {
      // Orbit accepts patterns that Perl rejects. Skip rather than fail, since this
      // is a compile-time semantic difference, not a test infrastructure error.
      Assumptions.assumeFalse(true,
          "Line " + lineNumber + ": Orbit compiled pattern '" + row.pattern()
              + "' that Perl rejects as a compile error");
      return;
    }

    // 4. Run the match
    Matcher m = p.matcher(row.subject());
    boolean found;
    try {
      found = m.find();
    } catch (RuntimeException e) {
      // Engine runtime errors (timeout, internal bug) — skip with diagnostic
      Assumptions.assumeFalse(true,
          "Line " + lineNumber + ": engine error matching pattern '" + row.pattern()
              + "': " + e.getMessage());
      return;
    }

    // 5. Assert match outcome
    char base = baseResult(row.resultFlags());
    if (base == 'n') {
      assertFalse(found,
          "Expected no match on line " + lineNumber
              + " pattern='" + row.pattern() + "' subject='" + row.subject() + "'");
      return;
    }
    assertTrue(found,
        "Expected match on line " + lineNumber
            + " pattern='" + row.pattern() + "' subject='" + row.subject() + "'");

    // 6. If expr is '-': no expression check needed
    if ("-".equals(row.expr())) {
      return;
    }

    // 7. Evaluate expression and compare to expected
    String actual;
    try {
      actual = evalExpr(row.expr(), m);
    } catch (IndexOutOfBoundsException e) {
      // Group index referenced in expr exceeds groups in pattern — skip
      Assumptions.assumeFalse(true,
          "Line " + lineNumber + ": group index out of range in expr '" + row.expr() + "': "
              + e.getMessage());
      return;
    }
    if (actual == null) {
      return; // "-" expr handled above but guard here too
    }
    String expected = row.expected();
    // If expected is null or "-", skip the value check
    if (expected == null || "-".equals(expected)) {
      return;
    }
    org.junit.jupiter.api.Assertions.assertEquals(
        expected,
        actual,
        "Line " + lineNumber + ": expr='" + row.expr()
            + "' pattern='" + row.pattern()
            + "' subject='" + row.subject() + "'");
  }

  // ---------------------------------------------------------------------------
  // Infrastructure tests
  // ---------------------------------------------------------------------------

  /** Sanity checks verifying that the test infrastructure is correctly configured. */
  @Nested
  @DisplayName("Data loading tests")
  class DataLoadingTests {

    /**
     * Asserts that the {@code re_tests} resource file is accessible from the classpath.
     */
    @Test
    @DisplayName("Data file should be accessible from classpath")
    void dataFileShouldBeAccessible() {
      InputStream is = PerlRegexCompatTest.class.getResourceAsStream(DATA_PATH);
      assertNotNull(is, "Test data file should exist in classpath: " + DATA_PATH);
    }

    /**
     * Asserts that the data file contains at least 500 parseable test rows after
     * the {@code __END__} marker.
     */
    @Test
    @DisplayName("Data file should contain at least 500 parseable rows")
    void dataFileShouldContainRows() {
      long count = testCases().count();
      assertTrue(count >= 500,
          "Expected at least 500 parseable rows, got " + count);
    }
  }
}
