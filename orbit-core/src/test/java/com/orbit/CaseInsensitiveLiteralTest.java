package com.orbit;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.util.PatternFlag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the case-insensitive literal encoding fix (§6.7.4 Fix E1 / E2).
 *
 * <p>Verifies that {@code CASE_INSENSITIVE} patterns use a disjoint-branch Split encoding
 * rather than a contiguous {@code CharMatch(lo, hi)} range for ASCII letters. The contiguous
 * range {@code [A..a]} (65–97) would match 33 characters instead of the intended 2, causing
 * false positives for characters such as {@code [} (91) and {@code _} (95).
 *
 * <p>Instances are not thread-safe; each test creates its own {@link Matcher}.
 */
class CaseInsensitiveLiteralTest {

  // ---------------------------------------------------------------------------
  // No-match cases — characters between 'A' and 'a' that are NOT the letter
  // ---------------------------------------------------------------------------

  /** Pattern {@code abc/i} must not match {@code XBC}: {@code X}=88 is between A=65 and a=97. */
  @Test
  void ci_literal_uppercase_noMatch() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("XBC").find()).isFalse();
  }

  /** Pattern {@code abc/i} must not match {@code AXC}: {@code X}=88 between A and a. */
  @Test
  void ci_literal_middleChar_noMatch() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("AXC").find()).isFalse();
  }

  /** Pattern {@code abc/i} must not match {@code ABX}: {@code X}=88 between A and a. */
  @Test
  void ci_literal_lastChar_noMatch() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("ABX").find()).isFalse();
  }

  /** Pattern {@code a/i} must not match {@code [}: {@code [}=91 is between A=65 and a=97. */
  @Test
  void ci_literal_symbolNotMatched() {
    Pattern p = Pattern.compile("a", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("[").find()).isFalse();
  }

  /** Pattern {@code a/i} must not match {@code _}: {@code _}=95 is between A=65 and a=97. */
  @Test
  void ci_literal_symbolNotMatched2() {
    Pattern p = Pattern.compile("a", PatternFlag.CASE_INSENSITIVE);
    assertThat(p.matcher("_").find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Match cases — the correct upper and lower forms must still match
  // ---------------------------------------------------------------------------

  /** Pattern {@code abc/i} matches uppercase {@code ABC}; group(0) must equal {@code ABC}. */
  @Test
  void ci_literal_exact_uppercase_match() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("ABC");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("ABC");
  }

  /** Pattern {@code abc/i} matches lowercase {@code abc}; group(0) must equal {@code abc}. */
  @Test
  void ci_literal_exact_lowercase_match() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("abc");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("abc");
  }

  /** Pattern {@code abc/i} matches mixed-case {@code AbC}; group(0) must equal {@code AbC}. */
  @Test
  void ci_literal_mixed_match() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("AbC");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("AbC");
  }

  // ---------------------------------------------------------------------------
  // Compound patterns — quantifiers, alternation, dot
  // ---------------------------------------------------------------------------

  /**
   * Pattern {@code a.*?c/i} on {@code AXYZC}: lazy dot should match; group(0) = {@code AXYZC}.
   */
  @Test
  void ci_lazy_dotstar() {
    Pattern p = Pattern.compile("a.*?c", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("AXYZC");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("AXYZC");
  }

  /**
   * Pattern {@code a+b+c/i} on {@code AABBABC}: leftmost match must be {@code ABC}.
   * Confirms greedy quantifiers work correctly with the fixed encoding.
   */
  @Test
  void ci_greedy_quantifier() {
    Pattern p = Pattern.compile("a+b+c", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("AABBABC");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("ABC");
  }

  /**
   * Pattern {@code (a+|b)*} with {@code /i} on {@code AB}: match group(0) = {@code AB},
   * group(1) = {@code B}.
   */
  @Test
  void ci_alternation_star() {
    Pattern p = Pattern.compile("(a+|b)*", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("AB");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("AB");
    assertThat(m.group(1)).isEqualTo("B");
  }

  /**
   * Pattern {@code a1b/i} on {@code A1B}: non-alphabetic {@code 1} has no case partner;
   * must still match. group(0) = {@code A1B}.
   */
  @Test
  void ci_nonAlpha_unchanged() {
    Pattern p = Pattern.compile("a1b", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("A1B");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("A1B");
  }

  // ---------------------------------------------------------------------------
  // Unicode CI path (Fix E2) — CASE_INSENSITIVE + UNICODE_CASE
  // ---------------------------------------------------------------------------

  /**
   * Pattern {@code abc} with both {@code CASE_INSENSITIVE} and {@code UNICODE_CASE} on
   * {@code ABC}: the Unicode folding path must also use the disjoint-branch encoding.
   * group(0) must equal {@code ABC}.
   */
  @Test
  void ci_unicode_and_ascii_noRange() {
    Pattern p = Pattern.compile(
        "abc", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
    Matcher m = p.matcher("ABC");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("ABC");
  }

  /**
   * Pattern {@code a} with both {@code CASE_INSENSITIVE} and {@code UNICODE_CASE} on {@code [}
   * ({@code [}=91, between A=65 and a=97): must not match.
   */
  @Test
  void ci_unicode_symbol_noMatch() {
    Pattern p = Pattern.compile(
        "a", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
    assertThat(p.matcher("[").find()).isFalse();
  }
}
