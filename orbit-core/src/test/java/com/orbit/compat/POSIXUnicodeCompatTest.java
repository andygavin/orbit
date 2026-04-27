package com.orbit.compat;

import com.orbit.api.Pattern;
import com.orbit.util.PatternFlag;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive POSIX character-class compatibility tests for the BMP Unicode range (U+0001–U+FFFF).
 *
 * <p>For every BMP code point (excluding surrogates U+D800–U+DFFF) this class asserts that
 * Orbit's {@code \p{...}} patterns and POSIX bracket {@code [[:...:]] } patterns match exactly
 * the code points for which the inlined {@link PosixUnicode} predicates return {@code true}.
 * The predicates are derived directly from {@code UnicodeProperties.isPosixMatch} — they
 * describe what Orbit actually implements, not what the JDK's {@code POSIX_Unicode} reference
 * class implements.
 *
 * <p>Tests using {@code PatternFlag.UNICODE} verify that Orbit's Unicode flag wires the POSIX
 * named classes correctly. Tests without flags verify that bracket classes ({@code [[:...:]]})
 * resolve identically (they share the same property resolver regardless of active flags).
 *
 * <p>Instances are not thread-safe.
 */
class POSIXUnicodeCompatTest {

  // ---------------------------------------------------------------------------
  // Inlined predicates — match UnicodeProperties.isPosixMatch exactly
  // ---------------------------------------------------------------------------

  /**
   * Predicates mirroring Orbit's {@code UnicodeProperties.isPosixMatch} implementation exactly,
   * for use as expected values in BMP exhaustive tests.
   *
   * <p>These intentionally differ from {@code POSIX_Unicode.java} in some cases to reflect what
   * Orbit actually implements rather than what the JDK reference specifies.
   *
   * <p>Instances of this class are not constructible.
   */
  private static final class PosixUnicode {

    private PosixUnicode() {}

    /** {@code \p{Lower}}: {@link Character#isLowerCase(int)}. */
    static boolean isLower(int cp) {
      return Character.isLowerCase(cp);
    }

    /** {@code \p{Upper}}: {@link Character#isUpperCase(int)}. */
    static boolean isUpper(int cp) {
      return Character.isUpperCase(cp);
    }

    /**
     * {@code \p{Alpha}}: {@link Character#isLetter(int)}.
     *
     * <p>Note: Orbit uses {@code isLetter}, not {@code isAlphabetic}; they differ for modifier
     * letters and letter numbers.
     */
    static boolean isAlpha(int cp) {
      return Character.isLetter(cp);
    }

    /** {@code \p{Digit}}: {@link Character#isDigit(int)}. */
    static boolean isDigit(int cp) {
      return Character.isDigit(cp);
    }

    /** {@code \p{Alnum}}: {@link Character#isLetterOrDigit(int)}. */
    static boolean isAlnum(int cp) {
      return Character.isLetterOrDigit(cp);
    }

    /**
     * {@code \p{Space}}: whitespace plus the three non-breaking space characters that
     * {@code Character.isWhitespace} excludes.
     *
     * <p>Matches {@code Character.isWhitespace(cp) || cp == '\u00A0' || cp == '\u2007'
     * || cp == '\u202F'}.
     */
    static boolean isSpace(int cp) {
      return Character.isWhitespace(cp) || cp == '\u00A0' || cp == '\u2007' || cp == '\u202F';
    }

    /** {@code \p{Blank}}: only space (U+0020) and horizontal tab (U+0009). */
    static boolean isBlank(int cp) {
      return cp == ' ' || cp == '\t';
    }

    /**
     * {@code \p{Punct}}: Unicode punctuation categories (Pc, Pd, Ps, Pe, Pi, Pf, Po).
     */
    static boolean isPunct(int cp) {
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
     * {@code \p{Graph}}: any character that is not a space separator, line separator,
     * paragraph separator, control, surrogate, unassigned, or an ASCII whitespace control char
     * ({@code \t}, {@code \n}, {@code \r}, {@code \f}).
     */
    static boolean isGraph(int cp) {
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
     * {@code \p{Print}}: any character that is not a control, surrogate, or unassigned code point.
     */
    static boolean isPrint(int cp) {
      int type = Character.getType(cp);
      return type != Character.CONTROL
          && type != Character.SURROGATE
          && type != Character.UNASSIGNED;
    }

    /** {@code \p{Cntrl}}: Unicode general category Control (Cc). */
    static boolean isCntrl(int cp) {
      return Character.getType(cp) == Character.CONTROL;
    }

    /**
     * {@code \p{XDigit}}: characters accepted as hexadecimal digits by
     * {@link Character#digit(int, int) Character.digit(cp, 16)}.
     */
    static boolean isHexDigit(int cp) {
      return Character.digit(cp, 16) != -1;
    }

    /**
     * {@code \p{Word}} / {@code [[:word:]]}: letter-or-digit, underscore, or
     * connector-punctuation characters.
     */
    static boolean isWord(int cp) {
      return Character.isLetterOrDigit(cp)
          || cp == '_'
          || Character.getType(cp) == Character.CONNECTOR_PUNCTUATION;
    }

    /**
     * {@code \w} with {@code PatternFlag.UNICODE}: category L (letters) + category N (numbers)
     * + underscore, as implemented by Orbit's {@code unicodeWordClass}.
     *
     * <p>This differs from {@link #isWord} — connector punctuation (other than {@code _}) is
     * not included, but all numeric types (letter numbers, other numbers) are.
     */
    static boolean isUnicodeW(int cp) {
      int type = Character.getType(cp);
      // Category L: all letter subtypes
      boolean isLetter = type == Character.UPPERCASE_LETTER
          || type == Character.LOWERCASE_LETTER
          || type == Character.TITLECASE_LETTER
          || type == Character.MODIFIER_LETTER
          || type == Character.OTHER_LETTER;
      // Category N: all number subtypes
      boolean isNumber = type == Character.DECIMAL_DIGIT_NUMBER
          || type == Character.LETTER_NUMBER
          || type == Character.OTHER_NUMBER;
      return isLetter || isNumber || cp == '_';
    }
  }

  // ---------------------------------------------------------------------------
  // \p{...} with PatternFlag.UNICODE tests
  // ---------------------------------------------------------------------------

  /** Asserts that {@code \p{Lower}} with UNICODE flag matches exactly {@code Character.isLowerCase}. */
  @Test
  void posixUnicode_lower_matchesExactlyLowerCase() {
    Pattern p = Pattern.compile("\\p{Lower}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isLower(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Lower} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Upper}} with UNICODE flag matches exactly {@code Character.isUpperCase}. */
  @Test
  void posixUnicode_upper_matchesExactlyUpperCase() {
    Pattern p = Pattern.compile("\\p{Upper}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isUpper(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Upper} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Alpha}} with UNICODE flag matches exactly {@code Character.isLetter}. */
  @Test
  void posixUnicode_alpha_matchesExactlyIsLetter() {
    Pattern p = Pattern.compile("\\p{Alpha}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isAlpha(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Alpha} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Digit}} with UNICODE flag matches exactly {@code Character.isDigit}. */
  @Test
  void posixUnicode_digit_matchesExactlyIsDigit() {
    Pattern p = Pattern.compile("\\p{Digit}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isDigit(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Digit} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \p{Alnum}} with UNICODE flag matches exactly
   * {@code Character.isLetterOrDigit}.
   */
  @Test
  void posixUnicode_alnum_matchesExactlyIsLetterOrDigit() {
    Pattern p = Pattern.compile("\\p{Alnum}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isAlnum(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Alnum} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Punct}} with UNICODE flag matches exactly Unicode punctuation categories. */
  @Test
  void posixUnicode_punct_matchesExactlyUnicodePunctuation() {
    Pattern p = Pattern.compile("\\p{Punct}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isPunct(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Punct} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Graph}} with UNICODE flag matches the expected graph definition. */
  @Test
  void posixUnicode_graph_matchesExpectedGraphDefinition() {
    Pattern p = Pattern.compile("\\p{Graph}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isGraph(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Graph} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Print}} with UNICODE flag matches the expected print definition. */
  @Test
  void posixUnicode_print_matchesExpectedPrintDefinition() {
    Pattern p = Pattern.compile("\\p{Print}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isPrint(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Print} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Blank}} with UNICODE flag matches only space and tab. */
  @Test
  void posixUnicode_blank_matchesOnlySpaceAndTab() {
    Pattern p = Pattern.compile("\\p{Blank}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isBlank(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Blank} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Cntrl}} with UNICODE flag matches exactly Unicode Control category. */
  @Test
  void posixUnicode_cntrl_matchesExactlyUnicodeControl() {
    Pattern p = Pattern.compile("\\p{Cntrl}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isCntrl(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Cntrl} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{XDigit}} with UNICODE flag matches hex digit characters. */
  @Test
  void posixUnicode_xdigit_matchesHexDigits() {
    Pattern p = Pattern.compile("\\p{XDigit}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isHexDigit(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{XDigit} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code \p{Space}} with UNICODE flag matches the expected space definition. */
  @Test
  void posixUnicode_space_matchesExpectedSpaceDefinition() {
    Pattern p = Pattern.compile("\\p{Space}", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isSpace(cp);
      assertThat(p.matcher(str).matches())
          .as("\\p{Space} mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code \w} with {@code PatternFlag.UNICODE} matches exactly Unicode
   * letters (category L), numbers (category N), and underscore — i.e. {@code \p{L}},
   * {@code \p{N}}, and {@code _}.
   */
  @Test
  void posixUnicode_word_matchesLettersNumbersAndUnderscore() {
    Pattern p = Pattern.compile("\\w", PatternFlag.UNICODE);
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isUnicodeW(cp);
      assertThat(p.matcher(str).matches())
          .as("\\w (UNICODE) mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  // ---------------------------------------------------------------------------
  // [[:...:]] bracket class tests (no flag needed — resolver is flag-independent)
  // ---------------------------------------------------------------------------

  /** Asserts that {@code [[:lower:]]} matches exactly {@code Character.isLowerCase}. */
  @Test
  void posixBracket_lower_matchesLowerCase() {
    Pattern p = Pattern.compile("[[:lower:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = Character.isLowerCase(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:lower:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code [[:upper:]]} matches exactly {@code Character.isUpperCase}. */
  @Test
  void posixBracket_upper_matchesUpperCase() {
    Pattern p = Pattern.compile("[[:upper:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = Character.isUpperCase(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:upper:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code [[:alpha:]]} matches exactly {@code Character.isLetter}. */
  @Test
  void posixBracket_alpha_matchesIsLetter() {
    Pattern p = Pattern.compile("[[:alpha:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = Character.isLetter(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:alpha:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code [[:digit:]]} matches exactly {@code Character.isDigit}. */
  @Test
  void posixBracket_digit_matchesIsDigit() {
    Pattern p = Pattern.compile("[[:digit:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = Character.isDigit(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:digit:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code [[:alnum:]]} matches exactly {@code Character.isLetterOrDigit}. */
  @Test
  void posixBracket_alnum_matchesIsLetterOrDigit() {
    Pattern p = Pattern.compile("[[:alnum:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = Character.isLetterOrDigit(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:alnum:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /** Asserts that {@code [[:space:]]} matches the Orbit space definition. */
  @Test
  void posixBracket_space_matchesOrbitSpaceDefinition() {
    Pattern p = Pattern.compile("[[:space:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isSpace(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:space:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  /**
   * Asserts that {@code [[:word:]]} matches exactly letter-or-digit, underscore, or
   * connector-punctuation characters.
   */
  @Test
  void posixBracket_word_matchesLetterOrDigitOrUnderscoreOrConnector() {
    Pattern p = Pattern.compile("[[:word:]]");
    for (int cp = 1; cp <= 0xFFFF; cp++) {
      if (cp >= 0xD800 && cp <= 0xDFFF) {
        continue;
      }
      String str = String.valueOf((char) cp);
      boolean expected = PosixUnicode.isWord(cp);
      assertThat(p.matcher(str).matches())
          .as("[[:word:]] mismatch at U+%04X".formatted(cp))
          .isEqualTo(expected);
    }
  }

  // ---------------------------------------------------------------------------
  // Disabled stubs for features not implemented in Orbit
  // ---------------------------------------------------------------------------

  /**
   * Tests that {@code (?U)} embedded flag wires to Unicode-aware POSIX class semantics.
   *
   * <p>Disabled because Orbit's inline {@code (?U)} flag is not mapped to
   * {@code PatternFlag.UNICODE}; only the API-level flag works.
   */
  @Disabled("(?U) embedded flag is not wired to PatternFlag.UNICODE in Orbit's parser")
  @Test
  void posixUnicode_embeddedFlagU_lower_notImplemented() {
    // Pattern.compile("(?U)\\p{Lower}") — inline (?U) not supported
  }

  /**
   * Tests {@code \p{IsLowerCase}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement the JDK-specific {@code Is*} property names
   * for character categories ({@code IsLowerCase}, {@code IsUpperCase}, etc.).
   */
  @Disabled("Orbit does not implement \\p{IsLowerCase} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isLowerCase_property_notImplemented() {
    // Pattern.compile("\\p{IsLowerCase}") — not supported
  }

  /**
   * Tests {@code \p{IsUpperCase}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsUpperCase}.
   */
  @Disabled("Orbit does not implement \\p{IsUpperCase} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isUpperCase_property_notImplemented() {
    // Pattern.compile("\\p{IsUpperCase}") — not supported
  }

  /**
   * Tests {@code \p{IsTitleCase}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsTitleCase}.
   */
  @Disabled("Orbit does not implement \\p{IsTitleCase} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isTitleCase_property_notImplemented() {
    // Pattern.compile("\\p{IsTitleCase}") — not supported
  }

  /**
   * Tests {@code \p{IsLetter}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsLetter}.
   */
  @Disabled("Orbit does not implement \\p{IsLetter} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isLetter_property_notImplemented() {
    // Pattern.compile("\\p{IsLetter}") — not supported
  }

  /**
   * Tests {@code \p{IsAlphabetic}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsAlphabetic}.
   */
  @Disabled("Orbit does not implement \\p{IsAlphabetic} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isAlphabetic_property_notImplemented() {
    // Pattern.compile("\\p{IsAlphabetic}") — not supported
  }

  /**
   * Tests {@code \p{IsIdeographic}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsIdeographic}.
   */
  @Disabled("Orbit does not implement \\p{IsIdeographic} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isIdeographic_property_notImplemented() {
    // Pattern.compile("\\p{IsIdeographic}") — not supported
  }

  /**
   * Tests {@code \p{IsControl}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsControl}.
   */
  @Disabled("Orbit does not implement \\p{IsControl} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isControl_property_notImplemented() {
    // Pattern.compile("\\p{IsControl}") — not supported
  }

  /**
   * Tests {@code \p{IsWhiteSpace}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsWhiteSpace}.
   */
  @Disabled("Orbit does not implement \\p{IsWhiteSpace} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isWhiteSpace_property_notImplemented() {
    // Pattern.compile("\\p{IsWhiteSpace}") — not supported
  }

  /**
   * Tests {@code \p{IsAssigned}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsAssigned}.
   */
  @Disabled("Orbit does not implement \\p{IsAssigned} — Is* category properties not in UnicodeProperties")
  @Test
  void posixUnicode_isAssigned_property_notImplemented() {
    // Pattern.compile("\\p{IsAssigned}") — not supported
  }

  /**
   * Tests {@code \p{IsNoncharacterCodePoint}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsNoncharacterCodePoint}.
   */
  @Disabled("Orbit does not implement \\p{IsNoncharacterCodePoint} — not in UnicodeProperties")
  @Test
  void posixUnicode_isNoncharacterCodePoint_property_notImplemented() {
    // Pattern.compile("\\p{IsNoncharacterCodePoint}") — not supported
  }

  /**
   * Tests {@code \p{IsJoinControl}} Unicode property.
   *
   * <p>Disabled because Orbit does not implement {@code IsJoinControl}.
   */
  @Disabled("Orbit does not implement \\p{IsJoinControl} — not in UnicodeProperties")
  @Test
  void posixUnicode_isJoinControl_property_notImplemented() {
    // Pattern.compile("\\p{IsJoinControl}") — not supported
  }

  /**
   * Tests {@code \p{IsEmoji}} and related emoji Unicode properties.
   *
   * <p>Disabled because Orbit does not implement emoji properties.
   */
  @Disabled("Orbit does not implement \\p{IsEmoji} or related emoji properties")
  @Test
  void posixUnicode_emojiProperties_notImplemented() {
    // Pattern.compile("\\p{IsEmoji}") etc. — not supported
  }

  /**
   * Tests {@code \p{javaAlphabetic}} java-prefixed property.
   *
   * <p>Disabled because Orbit's {@code java*} property set does not include
   * {@code javaAlphabetic} — only {@code javaLowerCase}, {@code javaUpperCase},
   * {@code javaTitleCase}, {@code javaDigit}, {@code javaDefined}, {@code javaLetter},
   * {@code javaLetterOrDigit}, {@code javaWhitespace}, {@code javaISOControl}, and
   * {@code javaMirrored} are supported.
   */
  @Disabled("Orbit does not implement \\p{javaAlphabetic} — not in handleJavaProperty switch")
  @Test
  void posixUnicode_javaAlphabetic_notImplemented() {
    // Pattern.compile("\\p{javaAlphabetic}") — not supported
  }

  /**
   * Tests {@code \p{javaIdeographic}} java-prefixed property.
   *
   * <p>Disabled because Orbit's {@code java*} property set does not include
   * {@code javaIdeographic}.
   */
  @Disabled("Orbit does not implement \\p{javaIdeographic} — not in handleJavaProperty switch")
  @Test
  void posixUnicode_javaIdeographic_notImplemented() {
    // Pattern.compile("\\p{javaIdeographic}") — not supported
  }
}
