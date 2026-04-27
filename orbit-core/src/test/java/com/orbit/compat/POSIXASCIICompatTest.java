package com.orbit.compat;

import com.orbit.api.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive POSIX character-class compatibility tests for the ASCII range (U+0000–U+007F).
 *
 * <p>For every code point in 0x00–0x7F this class asserts that Orbit's {@code \p{...}} patterns
 * and POSIX bracket {@code [[:...:]] } patterns match exactly the code points that Orbit's
 * {@code UnicodeProperties.isPosixMatch} implementation selects.
 *
 * <p><strong>Important:</strong> Orbit's {@code \p{Lower}}, {@code \p{Punct}}, etc. are always
 * Unicode-aware (they use {@link Character} methods rather than the C POSIX ctype table),
 * even without {@code PatternFlag.UNICODE}. In the ASCII range this coincides with the C POSIX
 * predicates for most classes but diverges for {@code Punct}, {@code Graph}, {@code Print}, and
 * {@code Space} — see individual test Javadoc for details.
 *
 * <p>This is a port of the ASCII-subset assertions from {@code unicodeClassesTest()} in
 * {@code jdk-tests/RegExTest.java}, adapted to Orbit's actual semantics.
 *
 * <p>Instances are not thread-safe.
 */
class POSIXASCIICompatTest {

  // ---------------------------------------------------------------------------
  // Expected-value predicates — derived from UnicodeProperties.isPosixMatch
  // ---------------------------------------------------------------------------

  /**
   * Expected-value predicates for Orbit's POSIX class implementation, restricted to the ASCII
   * range. These mirror the logic in {@code UnicodeProperties.isPosixMatch} rather than the C
   * POSIX ctype table.
   *
   * <p>Where Orbit's implementation coincides with the C POSIX ctype table in the ASCII range
   * ({@code Lower}, {@code Upper}, {@code Alpha}, {@code Digit}, {@code Alnum}, {@code Blank},
   * {@code Cntrl}, {@code XDigit}, {@code Word}) the inlined {@link PosixAscii} helper is used.
   * Where they diverge ({@code Punct}, {@code Graph}, {@code Print}, {@code Space}) the Unicode
   * predicate is used instead — see each test's Javadoc.
   */
  private static final class Expected {

    private Expected() {}

    /** {@code \p{Lower}}: ASCII lowercase letters a–z. Coincides with C POSIX in ASCII range. */
    static boolean lower(int cp) {
      return Character.isLowerCase(cp);
    }

    /** {@code \p{Upper}}: ASCII uppercase letters A–Z. Coincides with C POSIX in ASCII range. */
    static boolean upper(int cp) {
      return Character.isUpperCase(cp);
    }

    /**
     * {@code \p{Alpha}}: {@link Character#isLetter(int)}.
     *
     * <p>Coincides with C POSIX in the ASCII range (only a–z and A–Z are letters).
     */
    static boolean alpha(int cp) {
      return Character.isLetter(cp);
    }

    /** {@code \p{Digit}}: 0–9. Coincides with C POSIX in ASCII range. */
    static boolean digit(int cp) {
      return Character.isDigit(cp);
    }

    /** {@code \p{Alnum}}: letters and digits. Coincides with C POSIX in ASCII range. */
    static boolean alnum(int cp) {
      return Character.isLetterOrDigit(cp);
    }

    /**
     * {@code \p{Punct}}: Unicode punctuation categories (Pc, Pd, Ps, Pe, Pi, Pf, Po).
     *
     * <p><em>Diverges from C POSIX in ASCII range</em>: C POSIX punct includes {@code $},
     * {@code @}, {@code ^}, {@code `} (currency/symbol chars) while Orbit uses Unicode general
     * categories and excludes them. C POSIX also classifies {@code _} as punct+word, whereas
     * Orbit classifies {@code _} as connector punctuation (Pc), which IS in Orbit's punct set.
     */
    static boolean punct(int cp) {
      int type = Character.getType(cp);
      return type == Character.CONNECTOR_PUNCTUATION
          || type == Character.DASH_PUNCTUATION
          || type == Character.START_PUNCTUATION
          || type == Character.END_PUNCTUATION
          || type == Character.INITIAL_QUOTE_PUNCTUATION
          || type == Character.FINAL_QUOTE_PUNCTUATION
          || type == Character.OTHER_PUNCTUATION;
    }

    /**
     * {@code \p{Graph}}: any character that is not a space separator, line/paragraph separator,
     * control, surrogate, unassigned, or ASCII whitespace control ({@code \t \n \r \f}).
     *
     * <p><em>Diverges from C POSIX in ASCII range</em>: Orbit includes {@code _} (U+005F) in
     * Graph because its Unicode type is CONNECTOR_PUNCTUATION, not a separator or control.
     * C POSIX graph is ALNUM|PUNCT which includes {@code _} only if it is in PUNCT — in the
     * companion {@link PosixAscii} table {@code _} is UNDER-only (not PUNCT), so it is not graph.
     */
    static boolean graph(int cp) {
      int type = Character.getType(cp);
      return type != Character.SPACE_SEPARATOR
          && type != Character.LINE_SEPARATOR
          && type != Character.PARAGRAPH_SEPARATOR
          && type != Character.CONTROL
          && type != Character.SURROGATE
          && type != Character.UNASSIGNED
          && cp != '\t' && cp != '\n' && cp != '\r' && cp != '\f';
    }

    /**
     * {@code \p{Print}}: any character that is not a control, surrogate, or unassigned.
     *
     * <p><em>Diverges from C POSIX in ASCII range</em>: C POSIX print is the range 0x20–0x7E
     * plus blank (tab). Orbit's Print definition excludes control characters (type {@code Cc}),
     * so tab, which has type CONTROL, is not Print in Orbit.
     */
    static boolean print(int cp) {
      int type = Character.getType(cp);
      return type != Character.CONTROL
          && type != Character.SURROGATE
          && type != Character.UNASSIGNED;
    }

    /**
     * {@code \p{Blank}}: space and tab only.
     *
     * <p>Coincides with C POSIX in ASCII range.
     */
    static boolean blank(int cp) {
      return cp == ' ' || cp == '\t';
    }

    /**
     * {@code \p{Cntrl}}: Unicode general category Control (Cc).
     *
     * <p>Coincides with C POSIX in ASCII range (0x00–0x1F and 0x7F are all Cc).
     */
    static boolean cntrl(int cp) {
      return Character.getType(cp) == Character.CONTROL;
    }

    /**
     * {@code \p{XDigit}}: hex digits via {@code Character.digit(cp, 16)}.
     *
     * <p>Coincides with C POSIX in ASCII range (0–9, A–F, a–f).
     */
    static boolean xdigit(int cp) {
      return Character.digit(cp, 16) != -1;
    }

    /**
     * {@code \p{Space}}: {@code Character.isWhitespace(cp)} plus three non-breaking spaces.
     *
     * <p><em>Diverges from C POSIX in ASCII range</em>: {@code Character.isWhitespace} treats
     * the four C0 separator controls U+001C (FS), U+001D (GS), U+001E (RS), U+001F (US) as
     * whitespace, whereas C POSIX space is only HT, LF, VT, FF, CR, and SP (0x09–0x0D, 0x20).
     */
    static boolean space(int cp) {
      return Character.isWhitespace(cp) || cp == '\u00A0' || cp == '\u2007' || cp == '\u202F';
    }

    /**
     * {@code \w} (ASCII mode): letter, digit, or underscore.
     *
     * <p>Coincides with C POSIX in ASCII range.
     */
    static boolean word(int cp) {
      return Character.isLetterOrDigit(cp) || cp == '_';
    }
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * Asserts that {@code \p{Lower}} and {@code [[:lower:]] } match exactly ASCII lowercase.
   *
   * <p>Coincides with C POSIX in the ASCII range: only {@code a}–{@code z}.
   */
  @Test
  void posixAscii_lower_matchesExactlyAsciiLowercase() {
    Pattern p = Pattern.compile("\\p{Lower}");
    Pattern bracket = Pattern.compile("[[:lower:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.lower(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Lower} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:lower:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Upper}} and {@code [[:upper:]] } match exactly ASCII uppercase.
   *
   * <p>Coincides with C POSIX in the ASCII range: only {@code A}–{@code Z}.
   */
  @Test
  void posixAscii_upper_matchesExactlyAsciiUppercase() {
    Pattern p = Pattern.compile("\\p{Upper}");
    Pattern bracket = Pattern.compile("[[:upper:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.upper(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Upper} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:upper:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Alpha}} and {@code [[:alpha:]] } match exactly ASCII letters.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_alpha_matchesExactlyAsciiAlpha() {
    Pattern p = Pattern.compile("\\p{Alpha}");
    Pattern bracket = Pattern.compile("[[:alpha:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.alpha(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Alpha} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:alpha:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Digit}} and {@code [[:digit:]] } match exactly ASCII digits.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_digit_matchesExactlyAsciiDigit() {
    Pattern p = Pattern.compile("\\p{Digit}");
    Pattern bracket = Pattern.compile("[[:digit:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.digit(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Digit} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:digit:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Alnum}} and {@code [[:alnum:]] } match exactly ASCII letters and digits.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_alnum_matchesExactlyAsciiAlnum() {
    Pattern p = Pattern.compile("\\p{Alnum}");
    Pattern bracket = Pattern.compile("[[:alnum:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.alnum(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Alnum} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:alnum:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Punct}} and {@code [[:punct:]] } match Unicode punctuation categories
   * in the ASCII range.
   *
   * <p><em>Diverges from C POSIX</em>: Orbit uses Unicode general categories, so {@code $}
   * (CURRENCY_SYMBOL), {@code @} (OTHER_SYMBOL-adjacent), and {@code ^} are not punct, while
   * {@code _} (CONNECTOR_PUNCTUATION) is.
   */
  @Test
  void posixAscii_punct_matchesUnicodePunctInAsciiRange() {
    Pattern p = Pattern.compile("\\p{Punct}");
    Pattern bracket = Pattern.compile("[[:punct:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.punct(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Punct} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:punct:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Graph}} and {@code [[:graph:]] } match the Orbit graph definition in
   * the ASCII range.
   *
   * <p><em>Diverges from C POSIX</em>: underscore ({@code _}, U+005F) is CONNECTOR_PUNCTUATION
   * in Unicode and is therefore Graph in Orbit, whereas in C POSIX it is excluded from Graph
   * because the companion ctype table marks it only as UNDER.
   */
  @Test
  void posixAscii_graph_matchesOrbitGraphInAsciiRange() {
    Pattern p = Pattern.compile("\\p{Graph}");
    Pattern bracket = Pattern.compile("[[:graph:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.graph(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Graph} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:graph:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Print}} and {@code [[:print:]] } match the Orbit print definition in
   * the ASCII range.
   *
   * <p><em>Diverges from C POSIX</em>: Orbit Print excludes any character of type CONTROL.
   * C POSIX print includes the tab character (U+0009); Orbit does not because tab is Cc.
   */
  @Test
  void posixAscii_print_matchesOrbitPrintInAsciiRange() {
    Pattern p = Pattern.compile("\\p{Print}");
    Pattern bracket = Pattern.compile("[[:print:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.print(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Print} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:print:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Blank}} and {@code [[:blank:]] } match only space and tab.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_blank_matchesExactlyAsciiBlank() {
    Pattern p = Pattern.compile("\\p{Blank}");
    Pattern bracket = Pattern.compile("[[:blank:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.blank(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Blank} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:blank:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Cntrl}} and {@code [[:cntrl:]] } match exactly ASCII control chars.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_cntrl_matchesExactlyAsciiCntrl() {
    Pattern p = Pattern.compile("\\p{Cntrl}");
    Pattern bracket = Pattern.compile("[[:cntrl:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.cntrl(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Cntrl} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:cntrl:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{XDigit}} and {@code [[:xdigit:]] } match exactly ASCII hex digits.
   *
   * <p>Coincides with C POSIX in the ASCII range (0–9, A–F, a–f).
   */
  @Test
  void posixAscii_xdigit_matchesExactlyAsciiHexDigit() {
    Pattern p = Pattern.compile("\\p{XDigit}");
    Pattern bracket = Pattern.compile("[[:xdigit:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.xdigit(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{XDigit} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:xdigit:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Space}} and {@code [[:space:]] } match the Orbit space definition in
   * the ASCII range.
   *
   * <p><em>Diverges from C POSIX</em>: Orbit space includes U+001C–U+001F (FS, GS, RS, US),
   * which {@link Character#isWhitespace(int)} considers whitespace but C POSIX does not.
   */
  @Test
  void posixAscii_space_matchesOrbitSpaceInAsciiRange() {
    Pattern p = Pattern.compile("\\p{Space}");
    Pattern bracket = Pattern.compile("[[:space:]]");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.space(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Space} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
      assertThat(bracket.matcher(str).matches())
          .as("[[:space:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \w++} (possessive) matches exactly ASCII word characters: letters,
   * digits, and underscore.
   *
   * <p>Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_word_possessive_matchesExactlyAsciiWord() {
    Pattern p = Pattern.compile("\\w++");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.word(cp);
      assertThat(p.matcher(str).matches())
          .as("\\w++ mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \b\w\b} matches exactly single-character ASCII word-character strings.
   *
   * <p>For a single-character string, the pattern matches iff the character is a word character
   * (word boundaries are present at both string edges when the character is a word char).
   * Coincides with C POSIX in the ASCII range.
   */
  @Test
  void posixAscii_wordBoundary_matchesExactlyAsciiWord() {
    Pattern p = Pattern.compile("\\b\\w\\b");
    for (int cp = 0; cp <= 0x7F; cp++) {
      String str = String.valueOf((char) cp);
      boolean expected = Expected.word(cp);
      assertThat(p.matcher(str).matches())
          .as("\\b\\w\\b mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }
}
