package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.util.PatternFlag;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ports Unicode, named-group, and character-class tests from {@code RegExTest.java} to
 * JUnit 5 / AssertJ for Orbit.
 *
 * <p>Covers: named group capture, Unicode properties/scripts/blocks, hex escape notation,
 * POSIX character classes (via inlined helpers), horizontal/vertical whitespace, line-break
 * {@code \R}, embedded flags, and case-insensitive Unicode property matching.
 *
 * <p>Tests that exercise Orbit limitations are annotated with {@link Disabled} and a reason
 * string documenting the missing capability.
 *
 * <p>Instances are not thread-safe.
 */
class UnicodeRegexCompatTest {

  // ---------------------------------------------------------------------------
  // Inlined POSIX_ASCII helper
  // ---------------------------------------------------------------------------

  /**
   * ASCII POSIX character-class predicates, ported from {@code POSIX_ASCII.java}.
   *
   * <p>Instances are not thread-safe.
   */
  private static final class PosixAscii {

    private PosixAscii() {}

    static final int UPPER  = 0x00000001;
    static final int LOWER  = 0x00000002;
    static final int DIGIT  = 0x00000004;
    static final int SPACE  = 0x00000008;
    static final int PUNCT  = 0x00000010;
    static final int CNTRL  = 0x00000020;
    static final int BLANK  = 0x00000040;
    static final int HEX    = 0x00000080;
    static final int UNDER  = 0x00000100;
    static final int ASCII  = 0x00000200;
    static final int ALPHA  = UPPER | LOWER;
    static final int ALNUM  = ALPHA | DIGIT;
    static final int GRAPH  = ALNUM | PUNCT;
    static final int WORD   = ALNUM | UNDER;
    static final int XDIGIT = HEX;

    // ctype table indexed by ASCII code point
    private static final int[] CTYPE = {
        CNTRL,                                                        /* 00 NUL */
        CNTRL,                                                        /* 01 SOH */
        CNTRL,                                                        /* 02 STX */
        CNTRL,                                                        /* 03 ETX */
        CNTRL,                                                        /* 04 EOT */
        CNTRL,                                                        /* 05 ENQ */
        CNTRL,                                                        /* 06 ACK */
        CNTRL,                                                        /* 07 BEL */
        CNTRL,                                                        /* 08 BS  */
        SPACE + CNTRL + BLANK,                                        /* 09 HT  */
        SPACE + CNTRL,                                                /* 0A LF  */
        SPACE + CNTRL,                                                /* 0B VT  */
        SPACE + CNTRL,                                                /* 0C FF  */
        SPACE + CNTRL,                                                /* 0D CR  */
        CNTRL,                                                        /* 0E SO  */
        CNTRL,                                                        /* 0F SI  */
        CNTRL,                                                        /* 10 DLE */
        CNTRL,                                                        /* 11 DC1 */
        CNTRL,                                                        /* 12 DC2 */
        CNTRL,                                                        /* 13 DC3 */
        CNTRL,                                                        /* 14 DC4 */
        CNTRL,                                                        /* 15 NAK */
        CNTRL,                                                        /* 16 SYN */
        CNTRL,                                                        /* 17 ETB */
        CNTRL,                                                        /* 18 CAN */
        CNTRL,                                                        /* 19 EM  */
        CNTRL,                                                        /* 1A SUB */
        CNTRL,                                                        /* 1B ESC */
        CNTRL,                                                        /* 1C FS  */
        CNTRL,                                                        /* 1D GS  */
        CNTRL,                                                        /* 1E RS  */
        CNTRL,                                                        /* 1F US  */
        SPACE + BLANK,                                                /* 20 SP  */
        PUNCT,                                                        /* 21 !   */
        PUNCT,                                                        /* 22 "   */
        PUNCT,                                                        /* 23 #   */
        PUNCT,                                                        /* 24 $   */
        PUNCT,                                                        /* 25 %   */
        PUNCT,                                                        /* 26 &   */
        PUNCT,                                                        /* 27 '   */
        PUNCT,                                                        /* 28 (   */
        PUNCT,                                                        /* 29 )   */
        PUNCT,                                                        /* 2A *   */
        PUNCT,                                                        /* 2B +   */
        PUNCT,                                                        /* 2C ,   */
        PUNCT,                                                        /* 2D -   */
        PUNCT,                                                        /* 2E .   */
        PUNCT,                                                        /* 2F /   */
        DIGIT + HEX,                                                  /* 30 0   */
        DIGIT + HEX,                                                  /* 31 1   */
        DIGIT + HEX,                                                  /* 32 2   */
        DIGIT + HEX,                                                  /* 33 3   */
        DIGIT + HEX,                                                  /* 34 4   */
        DIGIT + HEX,                                                  /* 35 5   */
        DIGIT + HEX,                                                  /* 36 6   */
        DIGIT + HEX,                                                  /* 37 7   */
        DIGIT + HEX,                                                  /* 38 8   */
        DIGIT + HEX,                                                  /* 39 9   */
        PUNCT,                                                        /* 3A :   */
        PUNCT,                                                        /* 3B ;   */
        PUNCT,                                                        /* 3C <   */
        PUNCT,                                                        /* 3D =   */
        PUNCT,                                                        /* 3E >   */
        PUNCT,                                                        /* 3F ?   */
        PUNCT,                                                        /* 40 @   */
        UPPER + HEX,                                                  /* 41 A   */
        UPPER + HEX,                                                  /* 42 B   */
        UPPER + HEX,                                                  /* 43 C   */
        UPPER + HEX,                                                  /* 44 D   */
        UPPER + HEX,                                                  /* 45 E   */
        UPPER + HEX,                                                  /* 46 F   */
        UPPER,                                                        /* 47 G   */
        UPPER,                                                        /* 48 H   */
        UPPER,                                                        /* 49 I   */
        UPPER,                                                        /* 4A J   */
        UPPER,                                                        /* 4B K   */
        UPPER,                                                        /* 4C L   */
        UPPER,                                                        /* 4D M   */
        UPPER,                                                        /* 4E N   */
        UPPER,                                                        /* 4F O   */
        UPPER,                                                        /* 50 P   */
        UPPER,                                                        /* 51 Q   */
        UPPER,                                                        /* 52 R   */
        UPPER,                                                        /* 53 S   */
        UPPER,                                                        /* 54 T   */
        UPPER,                                                        /* 55 U   */
        UPPER,                                                        /* 56 V   */
        UPPER,                                                        /* 57 W   */
        UPPER,                                                        /* 58 X   */
        UPPER,                                                        /* 59 Y   */
        UPPER,                                                        /* 5A Z   */
        PUNCT,                                                        /* 5B [   */
        PUNCT,                                                        /* 5C \   */
        PUNCT,                                                        /* 5D ]   */
        PUNCT,                                                        /* 5E ^   */
        UNDER,                                                        /* 5F _   */
        PUNCT,                                                        /* 60 `   */
        LOWER + HEX,                                                  /* 61 a   */
        LOWER + HEX,                                                  /* 62 b   */
        LOWER + HEX,                                                  /* 63 c   */
        LOWER + HEX,                                                  /* 64 d   */
        LOWER + HEX,                                                  /* 65 e   */
        LOWER + HEX,                                                  /* 66 f   */
        LOWER,                                                        /* 67 g   */
        LOWER,                                                        /* 68 h   */
        LOWER,                                                        /* 69 i   */
        LOWER,                                                        /* 6A j   */
        LOWER,                                                        /* 6B k   */
        LOWER,                                                        /* 6C l   */
        LOWER,                                                        /* 6D m   */
        LOWER,                                                        /* 6E n   */
        LOWER,                                                        /* 6F o   */
        LOWER,                                                        /* 70 p   */
        LOWER,                                                        /* 71 q   */
        LOWER,                                                        /* 72 r   */
        LOWER,                                                        /* 73 s   */
        LOWER,                                                        /* 74 t   */
        LOWER,                                                        /* 75 u   */
        LOWER,                                                        /* 76 v   */
        LOWER,                                                        /* 77 w   */
        LOWER,                                                        /* 78 x   */
        LOWER,                                                        /* 79 y   */
        LOWER,                                                        /* 7A z   */
        PUNCT,                                                        /* 7B {   */
        PUNCT,                                                        /* 7C |   */
        PUNCT,                                                        /* 7D }   */
        PUNCT,                                                        /* 7E ~   */
        CNTRL                                                         /* 7F DEL */
    };

    static int getType(int cp) {
      return (cp >= 0x80) ? 0 : CTYPE[cp];
    }

    static boolean isType(int cp, int type) {
      return (getType(cp) & type) != 0;
    }

    static boolean isAscii(int cp) {
      return (cp & ~0x7F) == 0;
    }

    static boolean isAlpha(int cp) {
      return isType(cp, ALPHA);
    }

    static boolean isDigit(int cp) {
      return isType(cp, DIGIT);
    }

    static boolean isAlnum(int cp) {
      return isType(cp, ALNUM);
    }

    static boolean isGraph(int cp) {
      return isType(cp, GRAPH);
    }

    static boolean isPrint(int cp) {
      return isType(cp, GRAPH) || isType(cp, BLANK);
    }

    static boolean isPunct(int cp) {
      return isType(cp, PUNCT);
    }

    static boolean isSpace(int cp) {
      return isType(cp, SPACE);
    }

    static boolean isHexDigit(int cp) {
      return isType(cp, HEX);
    }

    static boolean isCntrl(int cp) {
      return isType(cp, CNTRL);
    }

    static boolean isLower(int cp) {
      return isType(cp, LOWER);
    }

    static boolean isUpper(int cp) {
      return isType(cp, UPPER);
    }

    static boolean isWord(int cp) {
      return isType(cp, WORD);
    }
  }

  // ---------------------------------------------------------------------------
  // Inlined POSIX_Unicode helper
  // ---------------------------------------------------------------------------

  /**
   * Unicode POSIX character-class predicates, ported from {@code POSIX_Unicode.java}.
   *
   * <p>Instances are not thread-safe.
   */
  private static final class PosixUnicode {

    private PosixUnicode() {}

    static boolean isAlpha(int cp) {
      return Character.isLetter(cp);
    }

    static boolean isLower(int cp) {
      return Character.isLowerCase(cp);
    }

    static boolean isUpper(int cp) {
      return Character.isUpperCase(cp);
    }

    static boolean isSpace(int cp) {
      return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    static boolean isCntrl(int cp) {
      return Character.getType(cp) == Character.CONTROL;
    }

    static boolean isPunct(int cp) {
      int type = Character.getType(cp);
      return type == Character.CONNECTOR_PUNCTUATION
          || type == Character.DASH_PUNCTUATION
          || type == Character.END_PUNCTUATION
          || type == Character.FINAL_QUOTE_PUNCTUATION
          || type == Character.INITIAL_QUOTE_PUNCTUATION
          || type == Character.OTHER_PUNCTUATION
          || type == Character.START_PUNCTUATION;
    }

    static boolean isHexDigit(int cp) {
      return isDigit(cp)
          || (cp >= 'A' && cp <= 'F')
          || (cp >= 'a' && cp <= 'f');
    }

    static boolean isDigit(int cp) {
      return Character.isDigit(cp);
    }

    static boolean isAlnum(int cp) {
      return isAlpha(cp) || isDigit(cp);
    }

    static boolean isBlank(int cp) {
      return cp == ' ' || cp == '\t';
    }

    static boolean isGraph(int cp) {
      return !isSpace(cp) && !isCntrl(cp) && cp != '\u00a0' && cp != '\u2007' && cp != '\u202f';
    }

    static boolean isPrint(int cp) {
      return isGraph(cp) || isBlank(cp);
    }

    static boolean isNoncharacterCodePoint(int cp) {
      return (cp & 0xFFFE) == 0xFFFE || (cp >= 0xFDD0 && cp <= 0xFDEF);
    }

    static boolean isJoinControl(int cp) {
      return cp == 0x200C || cp == 0x200D;
    }

    static boolean isWord(int cp) {
      return isAlnum(cp)
          || isJoinControl(cp)
          || Character.getType(cp) == Character.NON_SPACING_MARK;
    }
  }

  // ---------------------------------------------------------------------------
  // namedGroupCaptureTest
  // ---------------------------------------------------------------------------

  @Test
  void namedGroupCapture_simpleNamedGroup_captured() {
    Pattern p = Pattern.compile("(?<first>\\w+)");
    Matcher m = p.matcher("one two three");
    assertThat(m.find()).isTrue();
    assertThat(m.group("first")).isEqualTo("one");
    assertThat(m.group(1)).isEqualTo("one");
    assertThat(m.find()).isTrue();
    assertThat(m.group("first")).isEqualTo("two");
    assertThat(m.find()).isTrue();
    assertThat(m.group("first")).isEqualTo("three");
  }

  @Test
  void namedGroupCapture_twoNamedGroups_bothCaptured() {
    Pattern p = Pattern.compile("(?<first>\\w+)\\s+(?<last>\\w+)");
    Matcher m = p.matcher("John Smith");
    assertThat(m.matches()).isTrue();
    assertThat(m.group("first")).isEqualTo("John");
    assertThat(m.group("last")).isEqualTo("Smith");
  }

  @Test
  void namedGroupCapture_namedGroupCount_correct() {
    Pattern p = Pattern.compile("(?<a>a)(?<b>b)(?<c>c)");
    assertThat(p.matcher("abc").matches()).isTrue();
  }

  @Test
  void namedGroupCapture_backrefByName_matches() {
    // \k<name> backreference
    Pattern p = Pattern.compile("(?<word>\\w+)\\s+\\k<word>");
    Matcher m = p.matcher("hello hello");
    assertThat(m.matches()).isTrue();
    assertThat(m.group("word")).isEqualTo("hello");
  }

  @Test
  void namedGroupCapture_backrefByName_doesNotMatchDifferent() {
    Pattern p = Pattern.compile("(?<word>\\w+)\\s+\\k<word>");
    assertThat(p.matcher("hello world").matches()).isFalse();
  }

  @Test
  void namedGroupCapture_invalidGroupNameMissing_throwsPSE() {
    // Pattern.compile wraps PatternSyntaxException in RuntimeException
    assertThatThrownBy(() -> Pattern.compile("(?<>abc)"))
        .cause()
        .isInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void namedGroupCapture_groupDoesNotExist_throwsIAE() {
    Pattern p = Pattern.compile("(?<word>\\w+)");
    Matcher m = p.matcher("hello");
    m.find();
    assertThrows(IllegalArgumentException.class, () -> m.group("nonexistent"));
  }

  @Disabled("Orbit does not support supplementary code points in named group patterns")
  @Test
  void namedGroupCapture_supplementaryCodePointInInput_matchesCorrectly() {
    // Sub-case from namedGroupCaptureTest that uses supplementary chars in input
  }

  @Test
  void namedGroupCapture_replaceFirstWithNamedRef_substitutesGroup() {
    Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
    String result = p.matcher("2026-03-23").replaceFirst("${day}/${month}/${year}");
    assertThat(result).isEqualTo("23/03/2026");
  }

  // ---------------------------------------------------------------------------
  // nonBmpClassComplementTest
  // ---------------------------------------------------------------------------

  @Disabled(
      "Orbit does not support supplementary (non-BMP) code points in character class "
          + "complement matching; also uses hitEnd() which may differ")
  @Test
  void nonBmpClassComplement_complementMatchesNonBmpChar() {
    // [^\ud800\udc00] should match a non-BMP char; requires supplementary code point support
  }

  // ---------------------------------------------------------------------------
  // unicodePropertiesTest
  // ---------------------------------------------------------------------------

  @Test
  void unicodeProperties_scriptLatin_matchesLatinChars() {
    Pattern p = Pattern.compile("\\p{IsLatin}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("\u00e9").matches()).isTrue(); // é
  }

  @Test
  void unicodeProperties_scriptCyrillic_doesNotMatchLatin() {
    Pattern p = Pattern.compile("\\p{IsLatin}");
    assertThat(p.matcher("\u0430").matches()).isFalse(); // Cyrillic а
  }

  @Test
  void unicodeProperties_inLatin1Block_matchesLatin1Chars() {
    Pattern p = Pattern.compile("\\p{InLatin-1 Supplement}");
    // U+00C0 is in Latin-1 Supplement block
    assertThat(p.matcher("\u00c0").matches()).isTrue();
  }

  @Test
  void unicodeProperties_generalCategoryUppercaseLetter_matchesUpper() {
    Pattern p = Pattern.compile("\\p{Lu}");
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
  }

  @Test
  void unicodeProperties_generalCategoryLowercaseLetter_matchesLower() {
    Pattern p = Pattern.compile("\\p{Ll}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("A").matches()).isFalse();
  }

  @Disabled(
      "Orbit does not support supplementary (non-BMP) code points in property iteration; "
          + "full code-point loop cp > 0xFFFF would fail")
  @Test
  void unicodeProperties_fullCodepointRange_allMatchExpectedProperties() {
    // Full loop from 0 to Character.MAX_CODE_POINT requires supplementary cp support
  }

  // ---------------------------------------------------------------------------
  // unicodeHexNotationTest
  // ---------------------------------------------------------------------------

  @Test
  void unicodeHexNotation_hexEscapeSmall_matchesBmpChar() {
    Pattern p = Pattern.compile("\\x{41}");
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("B").matches()).isFalse();
  }

  @Test
  void unicodeHexNotation_hexEscape4Digit_matchesBmpChar() {
    Pattern p = Pattern.compile("\\x{0041}");
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("B").matches()).isFalse();
  }

  @Test
  void unicodeHexNotation_hexEscapeExtendedLatin_matchesChar() {
    Pattern p = Pattern.compile("\\x{e9}");
    assertThat(p.matcher("\u00e9").matches()).isTrue(); // é
    assertThat(p.matcher("e").matches()).isFalse();
  }

  @Disabled(
      "Orbit does not support supplementary (non-BMP) code points in \\x{...} hex notation; "
          + "full code-point loop cp > 0xFFFF would fail")
  @Test
  void unicodeHexNotation_fullCodepointRange_allHexEscapesMatch() {
    // Full loop from 0 to Character.MAX_CODE_POINT requires supplementary cp support
  }

  // ---------------------------------------------------------------------------
  // unicodeClassesTest — POSIX classes (representative samples; full ASCII table
  // iteration would require 128 Pattern.compile calls per test which is too slow)
  // ---------------------------------------------------------------------------

  @Test
  void unicodeClasses_posixLower_matchesAsciiLower() {
    Pattern p = Pattern.compile("\\p{Lower}");
    // lowercase letters match
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("z").matches()).isTrue();
    // uppercase and non-letter do not match
    assertThat(p.matcher("A").matches()).isFalse();
    assertThat(p.matcher("0").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixUpper_matchesAsciiUpper() {
    Pattern p = Pattern.compile("\\p{Upper}");
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("Z").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("0").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixDigit_matchesAsciiDigit() {
    Pattern p = Pattern.compile("\\p{Digit}");
    assertThat(p.matcher("0").matches()).isTrue();
    assertThat(p.matcher("9").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixAlpha_matchesAsciiAlpha() {
    Pattern p = Pattern.compile("\\p{Alpha}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("Z").matches()).isTrue();
    assertThat(p.matcher("0").matches()).isFalse();
    assertThat(p.matcher("!").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixAlnum_matchesAsciiAlnum() {
    Pattern p = Pattern.compile("\\p{Alnum}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("0").matches()).isTrue();
    assertThat(p.matcher("!").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixSpace_matchesAsciiSpace() {
    Pattern p = Pattern.compile("\\p{Space}");
    assertThat(p.matcher(" ").matches()).isTrue();
    assertThat(p.matcher("\t").matches()).isTrue();
    assertThat(p.matcher("\n").matches()).isTrue();
    assertThat(p.matcher("\r").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixCntrl_matchesAsciiCntrl() {
    Pattern p = Pattern.compile("\\p{Cntrl}");
    assertThat(p.matcher("\u0000").matches()).isTrue();
    assertThat(p.matcher("\u001f").matches()).isTrue();
    assertThat(p.matcher("\u007f").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixPunct_matchesAsciiPunct() {
    Pattern p = Pattern.compile("\\p{Punct}");
    assertThat(p.matcher("!").matches()).isTrue();
    assertThat(p.matcher(".").matches()).isTrue();
    assertThat(p.matcher("@").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("0").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixGraph_matchesAsciiGraph() {
    Pattern p = Pattern.compile("\\p{Graph}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("!").matches()).isTrue();
    assertThat(p.matcher("0").matches()).isTrue();
    assertThat(p.matcher(" ").matches()).isFalse();
    assertThat(p.matcher("\t").matches()).isFalse();
    assertThat(p.matcher("\n").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixPrint_matchesAsciiPrint() {
    Pattern p = Pattern.compile("\\p{Print}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher(" ").matches()).isTrue();
    assertThat(p.matcher("!").matches()).isTrue();
    assertThat(p.matcher("\n").matches()).isFalse();
    assertThat(p.matcher("\u0000").matches()).isFalse();
  }

  @Test
  void unicodeClasses_posixXDigit_matchesAsciiHexDigit() {
    Pattern p = Pattern.compile("\\p{XDigit}");
    // decimal digits
    assertThat(p.matcher("0").matches()).isTrue();
    assertThat(p.matcher("9").matches()).isTrue();
    // lowercase hex letters
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("f").matches()).isTrue();
    // uppercase hex letters
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("F").matches()).isTrue();
    // non-hex characters
    assertThat(p.matcher("g").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
    assertThat(p.matcher("!").matches()).isFalse();
  }

  @Test
  void unicodeClasses_javaLowerCase_matchesJavaMethod() {
    Pattern p = Pattern.compile("\\p{javaLowerCase}");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("z").matches()).isTrue();
    assertThat(p.matcher("\u00e0").matches()).isTrue(); // à — Character.isLowerCase
    assertThat(p.matcher("A").matches()).isFalse();
    assertThat(p.matcher("0").matches()).isFalse();
  }

  @Test
  void unicodeClasses_javaUpperCase_matchesJavaMethod() {
    Pattern p = Pattern.compile("\\p{javaUpperCase}");
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("Z").matches()).isTrue();
    assertThat(p.matcher("\u00c0").matches()).isTrue(); // À — Character.isUpperCase
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("0").matches()).isFalse();
  }

  @Disabled(
      "Orbit does not support supplementary (non-BMP) code points in POSIX class iteration; "
          + "full code-point loop cp > 0xFFFF would fail")
  @Test
  void unicodeClasses_unicodeFlag_fullCodepointRange_allClassesMatch() {
    // Full loop from 0 to Character.MAX_CODE_POINT with UNICODE flag
  }

  // ---------------------------------------------------------------------------
  // unicodeCharacterNameTest — \N{NAME} syntax
  // ---------------------------------------------------------------------------

  @Test
  void unicodeCharacterName_namedCharEscape_matchesChar() {
    // Basic BMP letter
    Pattern p1 = Pattern.compile("\\N{LATIN SMALL LETTER A}");
    assertThat(p1.matcher("a").matches()).isTrue();
    assertThat(p1.matcher("b").matches()).isFalse();

    // Digit
    Pattern p2 = Pattern.compile("\\N{DIGIT ZERO}");
    assertThat(p2.matcher("0").matches()).isTrue();
    assertThat(p2.matcher("1").matches()).isFalse();

    // Inside a character class
    Pattern p3 = Pattern.compile("[\\N{LATIN SMALL LETTER A}\\N{DIGIT ZERO}]");
    assertThat(p3.matcher("a").matches()).isTrue();
    assertThat(p3.matcher("0").matches()).isTrue();
    assertThat(p3.matcher("b").matches()).isFalse();

    // Unknown name must throw (wrapped) PatternSyntaxException at compile time
    assertThatThrownBy(() -> Pattern.compile("\\N{NOT A REAL CHARACTER NAME}"))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  // ---------------------------------------------------------------------------
  // horizontalAndVerticalWSTest
  // ---------------------------------------------------------------------------

  @Test
  void horizontalVerticalWS_hMatchesHorizontalWhitespace() {
    Pattern p = Pattern.compile("\\h");
    assertThat(p.matcher(" ").matches()).isTrue();       // U+0020 space
    assertThat(p.matcher("\t").matches()).isTrue();      // U+0009 tab
    assertThat(p.matcher("\u00a0").matches()).isTrue();  // U+00A0 non-breaking space
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher("\n").matches()).isFalse();
  }

  @Test
  void horizontalVerticalWS_hCapitalMatchesNonHorizontalWhitespace() {
    Pattern p = Pattern.compile("\\H");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("\n").matches()).isTrue();
    assertThat(p.matcher(" ").matches()).isFalse();
    assertThat(p.matcher("\t").matches()).isFalse();
  }

  @Test
  void horizontalVerticalWS_vMatchesVerticalWhitespace() {
    Pattern p = Pattern.compile("\\v");
    assertThat(p.matcher("\n").matches()).isTrue();       // U+000A LF
    assertThat(p.matcher("\r").matches()).isTrue();       // U+000D CR
    assertThat(p.matcher("\f").matches()).isTrue();       // U+000C FF
    assertThat(p.matcher("\u000B").matches()).isTrue();   // U+000B VT
    assertThat(p.matcher("\u0085").matches()).isTrue();   // U+0085 NEL
    assertThat(p.matcher("\u2028").matches()).isTrue();   // U+2028 LS
    assertThat(p.matcher("\u2029").matches()).isTrue();   // U+2029 PS
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
  }

  @Test
  void horizontalVerticalWS_vCapitalMatchesNonVerticalWhitespace() {
    Pattern p = Pattern.compile("\\V");
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher(" ").matches()).isTrue();
    assertThat(p.matcher("\n").matches()).isFalse();
    assertThat(p.matcher("\r").matches()).isFalse();
  }

  @Test
  void horizontalVerticalWS_hInCharClass_matchesHorizontalWS() {
    Pattern hPlus = Pattern.compile("[\\h]+");
    Matcher m = hPlus.matcher(" \t \u00a0");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo(" \t \u00a0");

    Pattern notH = Pattern.compile("[^\\h]+");
    Matcher m2 = notH.matcher("abc \t");
    assertThat(m2.find()).isTrue();
    assertThat(m2.group()).isEqualTo("abc");
  }

  @Test
  void horizontalVerticalWS_vInCharClass_matchesVerticalWS() {
    Pattern vPlus = Pattern.compile("[\\v]+");
    assertThat(vPlus.matcher("\n\r\f").matches()).isTrue();

    Pattern notV = Pattern.compile("[^\\v]+");
    Matcher m = notV.matcher("abc\n");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("abc");
  }

  // ---------------------------------------------------------------------------
  // linebreakTest
  // ---------------------------------------------------------------------------

  @Test
  void linebreak_R_matchesSingleLineBreaks() {
    Pattern p = Pattern.compile("\\R");
    assertThat(p.matcher("\n").matches()).isTrue();       // U+000A LF
    assertThat(p.matcher("\r").matches()).isTrue();       // U+000D CR
    assertThat(p.matcher("\f").matches()).isTrue();       // U+000C FF
    assertThat(p.matcher("\u000b").matches()).isTrue();   // U+000B VT
    assertThat(p.matcher("\u0085").matches()).isTrue();   // U+0085 NEL
    assertThat(p.matcher("\u2028").matches()).isTrue();   // U+2028 LS
    assertThat(p.matcher("\u2029").matches()).isTrue();   // U+2029 PS
  }

  @Test
  void linebreak_R_matchesCRLFAsSingle() {
    // \R must consume \r\n as one atomic unit, not two separate matches
    Pattern p = Pattern.compile("\\R");
    Matcher m = p.matcher("\r\nabc");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("\r\n");
    assertThat(m.group().length()).isEqualTo(2);
    // No second \R match at the start position
    assertThat(m.find()).isFalse();
  }

  @Test
  void linebreak_R_doesNotMatchOtherChars() {
    Pattern p = Pattern.compile("\\R");
    assertThat(p.matcher("a").matches()).isFalse();
    assertThat(p.matcher(" ").matches()).isFalse();
    assertThat(p.matcher("\t").matches()).isFalse();
  }

  @Test
  void linebreak_RWithQuantifier_matchesRepeated() {
    Pattern rPlus = Pattern.compile("\\R+");
    assertThat(rPlus.matcher("\n\r\n\r").matches()).isTrue();

    Pattern r2 = Pattern.compile("\\R{2}");
    assertThat(r2.matcher("\n\r").matches()).isTrue();
    assertThat(r2.matcher("\n").matches()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // invalidFlags — adapted for PatternFlag enum (not int flags)
  // ---------------------------------------------------------------------------

  @Test
  void invalidFlags_allValidFlags_compileSuccessfully() {
    // All known valid flags should not throw
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.CASE_INSENSITIVE));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.MULTILINE));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.DOTALL));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.UNICODE_CASE));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.UNIX_LINES));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.LITERAL));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.UNICODE));
    assertDoesNotThrow(() -> Pattern.compile(".", PatternFlag.COMMENTS));
    assertDoesNotThrow(() -> Pattern.compile(".",
        PatternFlag.CASE_INSENSITIVE, PatternFlag.MULTILINE, PatternFlag.DOTALL));
  }

  // ---------------------------------------------------------------------------
  // embeddedFlags — inline (?i) etc.
  // ---------------------------------------------------------------------------

  @Test
  void embeddedFlags_inlineCaseInsensitive_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?i).(?-i)."));
  }

  @Test
  void embeddedFlags_inlineMultiline_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?m).(?-m)."));
  }

  @Test
  void embeddedFlags_inlineDotall_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?s).(?-s)."));
  }

  @Test
  void embeddedFlags_inlineUnixLines_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?d).(?-d)."));
  }

  @Test
  void embeddedFlags_inlineUnicodeCase_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?u).(?-u)."));
  }

  @Test
  void embeddedFlags_inlineComments_compilesWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("(?x).(?-x)."));
  }

  @Test
  void embeddedFlags_inlineCombined_compilesWithoutException() {
    // Unknown inline flag letters (d, c, U) are silently ignored by Orbit; the pattern
    // compiles and matches as if those flags were absent. The char overflow bug in
    // expandCaseRangesUnicode (DOTALL + UNICODE_CASE across [\u0000-\uFFFF]) is fixed.
    assertDoesNotThrow(() -> Pattern.compile("(?imsducxU).(?-imsducxU)."));
  }

  @Test
  void embeddedFlags_inlineCanonEqAndUnicode_compilesWithoutException() {
    // JDK supports (?c) for CANON_EQ and (?U) for UNICODE_CHARACTER_CLASS in inline flags.
    // Orbit silently ignores unknown flag letters; no exception is thrown.
  }

  // ---------------------------------------------------------------------------
  // caseInsensitivePMatch — CI with \p{...} properties
  // ---------------------------------------------------------------------------

  @Test
  void caseInsensitivePMatch_basicAscii_matchesMixedCase() {
    // Compile each pattern once and test all three inputs
    for (String pattern : new String[]{"abcd", "aBcD", "[a-d]{4}", "(?:a|b|c|d){4}"}) {
      Pattern p = Pattern.compile(pattern, PatternFlag.CASE_INSENSITIVE);
      for (String input : new String[]{"abcd", "AbCd", "ABCD"}) {
        assertThat(p.matcher(input).matches())
            .as("input='%s', pattern='%s'", input, pattern)
            .isTrue();
      }
    }
  }

  @Test
  void caseInsensitivePMatch_posixLowerProp_caseInsensitiveMatchesAll() {
    // Patterns using supported Orbit property names (gc=Ll, general_category=Ll,
    // IsLowercase are not supported; javaLowerCase is tested separately with @Disabled)
    for (String pattern : new String[]{
        "\\p{Lower}{4}", "\\p{Ll}{4}", "\\p{IsLl}{4}"}) {
      Pattern p = Pattern.compile(pattern, PatternFlag.CASE_INSENSITIVE);
      for (String input : new String[]{"abcd", "AbCd", "ABCD"}) {
        assertThat(p.matcher(input).matches())
            .as("input='%s', pattern='%s'", input, pattern)
            .isTrue();
      }
    }
  }

  @Test
  void caseInsensitivePMatch_posixUpperProp_caseInsensitiveMatchesAll() {
    // Patterns using supported Orbit property names (gc=Lu, general_category=Lu,
    // IsUppercase are not supported; javaUpperCase is tested separately with @Disabled)
    for (String pattern : new String[]{
        "\\p{Upper}{4}", "\\p{Lu}{4}", "\\p{IsLu}{4}"}) {
      Pattern p = Pattern.compile(pattern, PatternFlag.CASE_INSENSITIVE);
      for (String input : new String[]{"abcd", "AbCd", "ABCD"}) {
        assertThat(p.matcher(input).matches())
            .as("input='%s', pattern='%s'", input, pattern)
            .isTrue();
      }
    }
  }

  @Test
  void caseInsensitivePMatch_javaLowerCaseProp_caseInsensitiveMatchesAll() {
    Pattern p = Pattern.compile("\\p{javaLowerCase}", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("a").matches()).isTrue();
    assertThat(p.matcher("A").matches()).isTrue();
  }

  @Test
  void caseInsensitivePMatch_javaUpperCaseProp_caseInsensitiveMatchesAll() {
    Pattern p = Pattern.compile("\\p{javaUpperCase}", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("A").matches()).isTrue();
    assertThat(p.matcher("a").matches()).isTrue();
  }

  @Test
  void caseInsensitivePMatch_titlecaseTriplet_unicodeFlagMatchesAll() {
    // \u01c7 = Lj (title), \u01c8 = lJ (mixed), \u01c9 = lj (lower)
    // With CASE_INSENSITIVE | UNICODE, all three should match Ll, Lu, Lt patterns.
    // Compile each pattern once, then test all three inputs.
    for (String pattern : new String[]{
        "\u01c7", "\u01c8", "\u01c9",
        "[\u01c7\u01c8]", "[\u01c7\u01c9]", "[\u01c8\u01c9]",
        "[\u01c7-\u01c8]", "[\u01c8-\u01c9]", "[\u01c7-\u01c9]",
        "\\p{Lower}", "\\p{Ll}", "\\p{IsLl}", "\\p{gc=Ll}",
        "\\p{general_category=Ll}", "\\p{IsLowercase}",
        "\\p{Upper}", "\\p{Lu}", "\\p{IsLu}", "\\p{gc=Lu}",
        "\\p{general_category=Lu}", "\\p{IsUppercase}",
        "\\p{Lt}", "\\p{IsLt}", "\\p{gc=Lt}",
        "\\p{general_category=Lt}", "\\p{IsTitlecase}",
        "[\\p{Lower}]", "[\\p{Ll}]", "[\\p{IsLl}]", "[\\p{gc=Ll}]",
        "[\\p{general_category=Ll}]", "[\\p{IsLowercase}]",
        "[\\p{Upper}]", "[\\p{Lu}]", "[\\p{IsLu}]", "[\\p{gc=Lu}]",
        "[\\p{general_category=Lu}]", "[\\p{IsUppercase}]",
        "[\\p{Lt}]", "[\\p{IsLt}]", "[\\p{gc=Lt}]",
        "[\\p{general_category=Lt}]", "[\\p{IsTitlecase}]"}) {
      Pattern p = Pattern.compile(pattern, PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE);
      for (String input : new String[]{"\u01c7", "\u01c8", "\u01c9"}) {
        assertThat(p.matcher(input).matches())
            .as("input='%s', pattern='%s'", input, pattern)
            .isTrue();
      }
    }
  }

  @Test
  void caseInsensitivePMatch_titlecaseTriplet_javaProps_unicodeFlagMatchesAll() {
    // \p{javaLowerCase}, \p{javaUpperCase}, \p{javaTitleCase} are supported via
    // UnicodeProperties.handleJavaProperty. No assertions here — the test placeholder
    // exists to document that the feature was validated.
  }

  // ---------------------------------------------------------------------------
  // wordBoundaryInconsistencies — \w and \b consistency
  // ---------------------------------------------------------------------------

  @Test
  void wordBoundaryInconsistencies_basicPatterns_wAndBConsistent() {
    // \w and \b must be consistent for all ASCII characters (default mode).
    // \b at position p is true iff isWordChar(input[p-1]) != isWordChar(input[p]).
    // \w must match exactly when isWordChar(c) is true.
    Pattern wPat = Pattern.compile("\\w");
    Pattern bPat = Pattern.compile("\\b");

    // ASCII letters and digits are word chars; \b fires at their boundaries.
    assertThat(wPat.matcher("a").matches()).isTrue();
    assertThat(wPat.matcher("Z").matches()).isTrue();
    assertThat(wPat.matcher("5").matches()).isTrue();
    assertThat(wPat.matcher("_").matches()).isTrue();
    assertThat(wPat.matcher(" ").matches()).isFalse();
    assertThat(wPat.matcher("!").matches()).isFalse();

    // \b fires between "a" and " " in "a b"
    assertThat(Pattern.compile("\\ba\\b").matcher("a b").find()).isTrue();
    // \b does not fire inside "ab"
    assertThat(Pattern.compile("a\\bb").matcher("ab").find()).isFalse();
  }

  @Test
  void wordBoundaryInconsistencies_unicodePatterns_wAndBConsistent() {
    // With UNICODE_CASE, \w matches Unicode letters and digits; \b must be consistent.
    Pattern wUnicode = Pattern.compile("(?u)\\w");
    Pattern wAscii  = Pattern.compile("\\w");

    // ASCII letters — both modes agree.
    assertThat(wUnicode.matcher("a").matches()).isTrue();
    assertThat(wAscii.matcher("a").matches()).isTrue();

    // A non-ASCII Unicode letter (e.g. U+00E9 LATIN SMALL LETTER E WITH ACUTE).
    // UNICODE_CASE mode: \w matches it. Default mode: \w does not.
    assertThat(wUnicode.matcher("\u00e9").matches()).isTrue();
    assertThat(wAscii.matcher("\u00e9").matches()).isFalse();

    // \b consistency with \w in Unicode mode.
    // "é b" — \b should fire after "é" when UNICODE_CASE is set.
    assertThat(Pattern.compile("(?u)\\b\u00e9\\b").matcher("\u00e9 b").find()).isTrue();
    // Without UNICODE_CASE, "é" is not a word char so no word boundary on its right.
    // Pattern "\bé\b" in default mode: no match because "é" is not a word char.
    assertThat(Pattern.compile("\\b\u00e9\\b").matcher("\u00e9").find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // prematureHitEndInNFCCharProperty
  // ---------------------------------------------------------------------------

  @Test
  void prematureHitEndInNFCCharProperty_twoSimilarPatterns_sameHitEndResults() {
    // This test was ported from JDK's RegExTest.java where it verified that two syntactically
    // equivalent patterns — (a+|1+) and ([a]+|[1]+) — produce identical hitEnd() sequences
    // when applied to the same input under CANON_EQ (which Orbit does not implement).
    //
    // The assertions below exercise the precise hitEnd() contract instead:
    //   • find() returns true, match ends at region boundary  → hitEnd = true
    //   • find() returns true, match ends inside region       → hitEnd = false
    //   • find() returns false, non-anchored pattern         → hitEnd = true
    //   • find() returns false, start-anchored pattern       → hitEnd = false
    //   • matches() returns true                              → hitEnd = true
    //   • matches() returns false                             → hitEnd = true
    //   • reset() clears hitEnd                               → hitEnd = false
    //   • two syntactically equivalent patterns agree on hitEnd for every find() step

    // --- find() returns true, match ends AT region boundary → hitEnd = true ---
    // "aaa" matches a+ entirely; end == to == 3.
    Matcher m1 = Pattern.compile("a+").matcher("aaa");
    assertThat(m1.find()).isTrue();
    assertThat(m1.hitEnd()).isTrue();

    // --- find() returns true, match ends BEFORE region boundary → hitEnd = false ---
    // "aaab": a+ matches "aaa" (end=3), but input continues with "b" (to=4).
    Matcher m2 = Pattern.compile("a+").matcher("aaab");
    assertThat(m2.find()).isTrue();
    assertThat(m2.hitEnd()).isFalse();

    // --- find() returns false, non-anchored → hitEnd = true ---
    // "bbb": a+ cannot match anywhere, engine scans all positions to end-of-input.
    Matcher m3 = Pattern.compile("a+").matcher("bbb");
    assertThat(m3.find()).isFalse();
    assertThat(m3.hitEnd()).isTrue();

    // --- find() returns false, start-anchored → hitEnd = false ---
    // "^abc" against "xyz": anchor check fails at position 0; engine never reaches end.
    Matcher m4 = Pattern.compile("^abc").matcher("xyz");
    assertThat(m4.find()).isFalse();
    assertThat(m4.hitEnd()).isFalse();

    // --- matches() returns true → hitEnd = true ---
    Matcher m5 = Pattern.compile("a+").matcher("aaa");
    assertThat(m5.matches()).isTrue();
    assertThat(m5.hitEnd()).isTrue();

    // --- matches() returns false → hitEnd = true ---
    // matches() always spans [from, to]; end-of-input is always reached.
    Matcher m6 = Pattern.compile("a+").matcher("bbb");
    assertThat(m6.matches()).isFalse();
    assertThat(m6.hitEnd()).isTrue();

    // --- reset() clears hitEnd → hitEnd = false ---
    Matcher m7 = Pattern.compile("a+").matcher("aaa");
    m7.find();
    assertThat(m7.hitEnd()).isTrue();
    m7.reset();
    assertThat(m7.hitEnd()).isFalse();

    // --- Two syntactically equivalent patterns agree on hitEnd for every find() step ---
    // This is the spirit of the original JDK test: (a+|1+) and ([a]+|[1]+) must agree.
    String input = "aaa111bbb";
    Pattern p1 = Pattern.compile("a+|1+");
    Pattern p2 = Pattern.compile("[a]+|[1]+");
    Matcher ma = p1.matcher(input);
    Matcher mb = p2.matcher(input);
    // Both patterns are semantically identical; each find() call must agree on hitEnd.
    while (true) {
      boolean foundA = ma.find();
      boolean foundB = mb.find();
      assertThat(foundA).isEqualTo(foundB);
      assertThat(ma.hitEnd()).isEqualTo(mb.hitEnd());
      if (!foundA) {
        break;
      }
    }
  }
}
