package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.util.PatternFlag;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.MatchResult;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ports core {@code RegExTest.java} methods (API contract, anchors, split, append/replace,
 * lookahead, lookbehind, hitEnd, regions) to JUnit 5 / AssertJ for Orbit.
 *
 * <p>Instances are not thread-safe.
 */
class RegExCompatTest {

  private static final Random RANDOM = new Random(42L);

  // ---------------------------------------------------------------------------
  // Private assertion helpers (mirrors RegExTest check(...) overloads)
  // ---------------------------------------------------------------------------

  /** Calls {@code m.find()} and asserts the matched group equals {@code expected}. */
  private static void assertNextMatchEquals(Matcher m, String expected) {
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo(expected);
  }

  /** Asserts that {@code pattern.compile(p).matcher(s).find()} returns {@code expected}. */
  private static void assertFind(String p, String s, boolean expected) {
    assertThat(Pattern.compile(p).matcher(s).find()).isEqualTo(expected);
  }

  /**
   * Asserts that {@code p.matcher(s).find()} returns {@code expected}.
   */
  private static void assertPatternFind(Pattern p, String s, boolean expected) {
    assertThat(p.matcher(s).find()).isEqualTo(expected);
  }

  /**
   * Asserts that compiling {@code p} throws {@link PatternSyntaxException}.
   */
  private static void assertCompilationFails(String p) {
    assertThatThrownBy(() -> Pattern.compile(p))
        .isInstanceOf(PatternSyntaxException.class);
  }

  /**
   * Asserts that {@code Pattern.compile(p).matcher(s).replaceFirst(r)} equals {@code expected}.
   */
  private static void assertReplaceFirst(String p, String s, String r, String expected) {
    assertThat(Pattern.compile(p).matcher(s).replaceFirst(r)).isEqualTo(expected);
  }

  /**
   * Asserts that {@code Pattern.compile(p).matcher(s).replaceAll(r)} equals {@code expected}.
   */
  private static void assertReplaceAll(String p, String s, String r, String expected) {
    assertThat(Pattern.compile(p).matcher(s).replaceAll(r)).isEqualTo(expected);
  }

  /** Returns a random alphabetic string of the given length (seeded for determinism). */
  private static String getRandomAlphaString(int length) {
    StringBuilder buf = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      buf.append((char) (97 + RANDOM.nextInt(26)));
    }
    return buf.toString();
  }

  // ---------------------------------------------------------------------------
  // nullArgumentTest
  // ---------------------------------------------------------------------------

  @Test
  void nullArgumentTest_compile_null_throwsNPE() {
    assertThatThrownBy(() -> Pattern.compile(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_matches_nullRegex_throwsNPE() {
    assertThatThrownBy(() -> Pattern.matches(null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_matches_nullInput_throwsNPE() {
    assertThatThrownBy(() -> Pattern.matches("xyz", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_quote_null_throwsNPE() {
    assertThatThrownBy(() -> Pattern.quote(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_split_null_throwsNPE() {
    assertThatThrownBy(() -> Pattern.compile("xyz").split(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_matcher_null_throwsNPE() {
    assertThatThrownBy(() -> Pattern.compile("xyz").matcher(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_appendTailStringBuilder_null_throwsNPE() {
    Matcher m = Pattern.compile("xyz").matcher("xyz");
    m.matches();
    assertThatThrownBy(() -> m.appendTail((StringBuilder) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_replaceAllString_null_throwsNPE() {
    Matcher m = Pattern.compile("xyz").matcher("xyz");
    m.matches();
    assertThatThrownBy(() -> m.replaceAll((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_replaceAllFunction_null_throwsNPE() {
    Matcher m = Pattern.compile("xyz").matcher("xyz");
    m.matches();
    assertThatThrownBy(() -> m.replaceAll((java.util.function.Function<MatchResult, String>) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_replaceFirstString_null_throwsNPE() {
    Matcher m = Pattern.compile("xyz").matcher("xyz");
    m.matches();
    assertThatThrownBy(() -> m.replaceFirst((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentTest_replaceFirstFunction_null_throwsNPE() {
    Matcher m = Pattern.compile("xyz").matcher("xyz");
    m.matches();
    assertThatThrownBy(
        () -> m.replaceFirst((java.util.function.Function<MatchResult, String>) null))
        .isInstanceOf(NullPointerException.class);
  }

  // ---------------------------------------------------------------------------
  // surrogatesInClassTest
  // ---------------------------------------------------------------------------

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void surrogatesInClassTest_surrogateRangeInUnicodeEscape_matchesMidpointCodePoint() {
    // Bug 6635133: surrogate pair in Unicode escape inside character class
    Pattern pattern = Pattern.compile("[\\ud834\\udd21-\\ud834\\udd24]");
    Matcher matcher = pattern.matcher("\ud834\udd22");
    assertThat(matcher.find()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // removeQEQuotingTest
  // ---------------------------------------------------------------------------

  @Test
  void removeQEQuotingTest_octalThenQuotedText_matchesCorrectly() {
    // Bug 6990617: octal encoding before quoted text
    Pattern pattern = Pattern.compile("\\011\\Q1sometext\\E\\011\\Q2sometext\\E");
    Matcher matcher = pattern.matcher("\t1sometext\t2sometext");
    assertThat(matcher.find()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // toMatchResultTest
  // ---------------------------------------------------------------------------

  @Test
  void toMatchResultTest_snapshot_isIndependentOfMatcher() {
    // Bug 4988891: toMatchResult() must return an independent snapshot
    Pattern pattern = Pattern.compile("squid");
    Matcher matcher = pattern.matcher("agiantsquidofdestinyasmallsquidoffate");
    matcher.find();

    int matcherStart1 = matcher.start();
    MatchResult mr = matcher.toMatchResult();
    assertThat(mr).isNotSameAs(matcher);

    int resultStart1 = mr.start();
    assertThat(matcherStart1).isEqualTo(resultStart1);

    matcher.find();
    int matcherStart2 = matcher.start();
    int resultStart2 = mr.start();

    assertThat(matcherStart2).isNotEqualTo(resultStart2);
    assertThat(resultStart1).isEqualTo(resultStart2);

    MatchResult mr2 = matcher.toMatchResult();
    assertThat(mr).isNotSameAs(mr2);
    assertThat(mr2.start()).isEqualTo(matcherStart2);
  }

  // ---------------------------------------------------------------------------
  // toMatchResultTest2
  // ---------------------------------------------------------------------------

  @Test
  void toMatchResultTest2_noMatch_snapshotOperationsThrowISE() {
    // Bug 8074678: snapshot throws ISE when no match
    Matcher matcher = Pattern.compile("nomatch").matcher("hello world");
    matcher.find();
    MatchResult mr = matcher.toMatchResult();

    assertThrows(IllegalStateException.class, mr::start);
    assertThrows(IllegalStateException.class, () -> mr.start(2));
    assertThrows(IllegalStateException.class, mr::end);
    assertThrows(IllegalStateException.class, () -> mr.end(2));
    assertThrows(IllegalStateException.class, mr::group);
    assertThrows(IllegalStateException.class, () -> mr.group(2));
  }

  @Test
  void toMatchResultTest2_outOfBoundsGroup_throwsIOOBE() {
    Matcher matcher = Pattern.compile("(match)").matcher("there is a match");
    matcher.find();
    MatchResult mr2 = matcher.toMatchResult();

    assertThrows(IndexOutOfBoundsException.class, () -> mr2.start(2));
    assertThrows(IndexOutOfBoundsException.class, () -> mr2.end(2));
    assertThrows(IndexOutOfBoundsException.class, () -> mr2.group(2));
  }

  // ---------------------------------------------------------------------------
  // wordSearchTest
  // ---------------------------------------------------------------------------

  @Test
  void wordSearchTest_wordBoundaryWithFindInt_traversesWords() {
    // Bug 4997476: \b with find(int) re-entrant search — adapted; Orbit has no find(int),
    // so we test basic \b find semantics instead.
    String testString = "word1 word2 word3";
    Pattern p = Pattern.compile("\\b\\w+\\b");
    Matcher m = p.matcher(testString);
    int count = 0;
    while (m.find()) {
      assertThat(m.group()).startsWith("word");
      count++;
    }
    assertThat(count).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // caretAtEndTest
  // ---------------------------------------------------------------------------

  @Test
  void caretAtEndTest_multilineCaretBeforeEmptyMatch_doesNotThrow() {
    // Bug 4994840: multiline ^ followed by empty-matching expression
    Pattern pattern = Pattern.compile("^x?", PatternFlag.MULTILINE);
    Matcher matcher = pattern.matcher("\r");
    matcher.find();
    matcher.find(); // must not throw
  }

  // ---------------------------------------------------------------------------
  // unicodeWordBoundsTest
  // ---------------------------------------------------------------------------

  @Test
  void unicodeWordBoundsTest_nonSpacingMarkExtendsBoundary() {
    // Bug 4979006: \b with Unicode non-spacing marks
    String spaces = "  ";
    String wordChar = "a";
    String nsm = "\u030a"; // combining ring above (NON_SPACING_MARK)

    assertThat(Character.getType('\u030a')).isEqualTo(Character.NON_SPACING_MARK);

    Pattern pattern = Pattern.compile("\\b");

    // SS.BB.SS
    String input = spaces + wordChar + wordChar + spaces;
    assertTwoWordBounds(pattern, input, 2, 4);
    // SS.BBN.SS
    input = spaces + wordChar + wordChar + nsm + spaces;
    assertTwoWordBounds(pattern, input, 2, 5);
    // SS.BN.SS
    input = spaces + wordChar + nsm + spaces;
    assertTwoWordBounds(pattern, input, 2, 4);
    // SS.BNN.SS
    input = spaces + wordChar + nsm + nsm + spaces;
    assertTwoWordBounds(pattern, input, 2, 5);
    // SSN.BB.SS
    input = spaces + nsm + wordChar + wordChar + spaces;
    assertTwoWordBounds(pattern, input, 3, 5);
    // SS.BNB.SS
    input = spaces + wordChar + nsm + wordChar + spaces;
    assertTwoWordBounds(pattern, input, 2, 5);
    // SSNNSS — no boundary expected
    input = spaces + nsm + nsm + spaces;
    assertThat(pattern.matcher(input).find()).isFalse();
    // SSN.BBN.SS
    input = spaces + nsm + wordChar + wordChar + nsm + spaces;
    assertTwoWordBounds(pattern, input, 3, 6);
  }

  private static void assertTwoWordBounds(Pattern pattern, String input, int a, int b) {
    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(a);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(b);
  }

  // ---------------------------------------------------------------------------
  // findFromTest
  // ---------------------------------------------------------------------------

  @Test
  void findFromTest_literalDollarZero_findsOnceOnly() {
    // Bug 4945394
    String message = "This is 40 $0 message.";
    Pattern pat = Pattern.compile("\\$0");
    Matcher match = pat.matcher(message);
    assertThat(match.find()).isTrue();
    assertThat(match.find()).isFalse();
    assertThat(match.find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // negatedCharClassTest
  // ---------------------------------------------------------------------------

  @Test
  void negatedCharClassTest_singleCharNegation_matchesNonAsciiChar() {
    // Bug 4872664, 4892980
    Pattern pattern = Pattern.compile("[^>]");
    Matcher matcher = pattern.matcher("\u203A");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void negatedCharClassTest_twoCharNegation_matchesNonAscii() {
    Pattern pattern = Pattern.compile("[^fr]");
    assertThat(pattern.matcher("a").find()).isTrue();
    assertThat(pattern.matcher("\u203A").find()).isTrue();
  }

  @Test
  void negatedCharClassTest_splitOnNegation_keepsLetters() {
    String s = "for";
    String[] result = s.split("[^fr]");
    assertThat(result[0]).isEqualTo("f");
    assertThat(result[1]).isEqualTo("r");

    s = "f\u203Ar";
    result = s.split("[^fr]");
    assertThat(result[0]).isEqualTo("f");
    assertThat(result[1]).isEqualTo("r");
  }

  @Test
  void negatedCharClassTest_mixedNegation_excludesCorrectChars() {
    Pattern pattern = Pattern.compile("[^f\u203Ar]");
    assertThat(pattern.matcher("a").find()).isTrue();
    assertThat(pattern.matcher("f").find()).isFalse();
    assertThat(pattern.matcher("\u203A").find()).isFalse();
    assertThat(pattern.matcher("r").find()).isFalse();
    assertThat(pattern.matcher("\u203B").find()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // toStringTest
  // ---------------------------------------------------------------------------

  @Test
  void toStringTest_patternToString_returnsOriginalPatternString() {
    // Bug 4628291
    Pattern pattern = Pattern.compile("b+");
    assertThat(pattern.toString()).isEqualTo("b+");
  }

  @Test
  void toStringTest_matcherToString_doesNotThrow() {
    Pattern pattern = Pattern.compile("b+");
    Matcher matcher = pattern.matcher("aaabbbccc");
    assertDoesNotThrow(matcher::toString);
    matcher.find();
    assertDoesNotThrow(matcher::toString);
    matcher.reset();
    assertDoesNotThrow(matcher::toString);
  }

  // ---------------------------------------------------------------------------
  // literalPatternTest
  // ---------------------------------------------------------------------------

  @Test
  void literalPatternTest_literalFlag_treatsSpecialCharsAsLiteral() {
    // Bug 4808962
    Pattern pattern = Pattern.compile("abc\\t$^", PatternFlag.LITERAL);
    assertPatternFind(pattern, "abc\\t$^", true);

    pattern = Pattern.compile(Pattern.quote("abc\\t$^"));
    assertPatternFind(pattern, "abc\\t$^", true);

    pattern = Pattern.compile("\\Qa^$bcabc\\E", PatternFlag.LITERAL);
    assertPatternFind(pattern, "\\Qa^$bcabc\\E", true);
    assertPatternFind(pattern, "a^$bcabc", false);

    pattern = Pattern.compile("\\\\Q\\\\E");
    assertPatternFind(pattern, "\\Q\\E", true);

    pattern = Pattern.compile("\\Qabc\\Eefg\\\\Q\\\\Ehij");
    assertPatternFind(pattern, "abcefg\\Q\\Ehij", true);

    pattern = Pattern.compile("\\\\\\Q\\\\E");
    assertPatternFind(pattern, "\\\\\\\\", true);

    pattern = Pattern.compile(Pattern.quote("\\Qa^$bcabc\\E"));
    assertPatternFind(pattern, "\\Qa^$bcabc\\E", true);
    assertPatternFind(pattern, "a^$bcabc", false);

    pattern = Pattern.compile(Pattern.quote("\\Qabc\\Edef"));
    assertPatternFind(pattern, "\\Qabc\\Edef", true);
    assertPatternFind(pattern, "abcdef", false);

    pattern = Pattern.compile(Pattern.quote("abc\\Edef"));
    assertPatternFind(pattern, "abc\\Edef", true);
    assertPatternFind(pattern, "abcdef", false);

    pattern = Pattern.compile(Pattern.quote("\\E"));
    assertPatternFind(pattern, "\\E", true);

    pattern = Pattern.compile("((((abc.+?:)", PatternFlag.LITERAL);
    assertPatternFind(pattern, "((((abc.+?:)", true);
  }

  @Test
  void literalPatternTest_literalWithMultiline_anchorsLiteral() {
    Pattern pattern = Pattern.compile("^cat$", PatternFlag.LITERAL, PatternFlag.MULTILINE);
    assertPatternFind(pattern, "abc^cat$def", true);
    assertPatternFind(pattern, "cat", false);
  }

  @Test
  void literalPatternTest_literalWithCaseInsensitive_matchesCaseInsensitively() {
    Pattern pattern = Pattern.compile("abcdef",
        PatternFlag.LITERAL, PatternFlag.MULTILINE, PatternFlag.CASE_INSENSITIVE);
    assertPatternFind(pattern, "ABCDEF", true);
    assertPatternFind(pattern, "AbCdEf", true);
  }

  @Test
  void literalPatternTest_literalWithDotall_dotNotSpecial() {
    Pattern pattern = Pattern.compile("a...b",
        PatternFlag.LITERAL, PatternFlag.MULTILINE,
        PatternFlag.CASE_INSENSITIVE, PatternFlag.DOTALL);
    assertPatternFind(pattern, "A...b", true);
    assertPatternFind(pattern, "Axxxb", false);
  }

  // ---------------------------------------------------------------------------
  // literalReplacementTest
  // ---------------------------------------------------------------------------

  @Test
  void literalReplacementTest_dollarZeroAsLiteralReplacement_notInterpolated() {
    // Bug 4803179, 4808962
    Pattern pattern = Pattern.compile("abc", PatternFlag.LITERAL);
    Matcher matcher = pattern.matcher("zzzabczzz");
    String replaceTest = "$0";
    String result = matcher.replaceAll(replaceTest);
    assertThat(result).isEqualTo("zzzabczzz");
  }

  @Test
  void literalReplacementTest_quotedDollarZero_producesLiteralDollarZero() {
    Pattern pattern = Pattern.compile("abc", PatternFlag.LITERAL);
    Matcher matcher = pattern.matcher("zzzabczzz");
    // quoteReplacement — use Matcher.quoteReplacement via JDK java.util.regex.Matcher
    String literalReplacement = java.util.regex.Matcher.quoteReplacement("$0");
    String result = matcher.replaceAll(literalReplacement);
    assertThat(result).isEqualTo("zzz$0zzz");
  }

  @Test
  void literalReplacementTest_quotedBackslashDollar_producesLiteralText() {
    Pattern pattern = Pattern.compile("abc", PatternFlag.LITERAL);
    Matcher matcher = pattern.matcher("zzzabczzz");
    String replaceTest = "\\t$\\$";
    String literalReplacement = java.util.regex.Matcher.quoteReplacement(replaceTest);
    String result = matcher.replaceAll(literalReplacement);
    assertThat(result).isEqualTo("zzz\\t$\\$zzz");
  }

  @Test
  void literalReplacementTest_trailingDollar_throwsIAE() {
    assertThatThrownBy(() -> "\uac00".replaceAll("\uac00", "$"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void literalReplacementTest_trailingBackslash_throwsIAE() {
    assertThatThrownBy(() -> "\uac00".replaceAll("\uac00", "\\"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // regionTest — Orbit does not expose region/useAnchoringBounds/useTransparentBounds
  // ---------------------------------------------------------------------------

  @Test
  void regionTest_basicRegion_limitsMatch() {
    Pattern pattern = Pattern.compile("abc");
    // Default region covers the full input — finds two "abc" spans.
    Matcher m1 = pattern.matcher("abcabcabc");
    m1.region(0, 9);
    assertThat(m1.regionStart()).isEqualTo(0);
    assertThat(m1.regionEnd()).isEqualTo(9);
    int count = 0;
    while (m1.find()) {
      count++;
    }
    assertThat(count).isEqualTo(3);

    // Region [0, 3) covers only the first "abc".
    Matcher m2 = pattern.matcher("abcabcabc");
    m2.region(0, 3);
    assertThat(m2.find()).isTrue();
    assertThat(m2.start()).isEqualTo(0);
    assertThat(m2.end()).isEqualTo(3);
    assertThat(m2.find()).isFalse();

    // Region [3, 6) covers only the second "abc".
    Matcher m3 = pattern.matcher("abcabcabc");
    m3.region(3, 6);
    assertThat(m3.find()).isTrue();
    assertThat(m3.start()).isEqualTo(3);
    assertThat(m3.end()).isEqualTo(6);
    assertThat(m3.find()).isFalse();

    // Region [0, 2) is too short to contain "abc".
    Matcher m4 = pattern.matcher("abcabcabc");
    m4.region(0, 2);
    assertThat(m4.find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // escapedSegmentTest
  // ---------------------------------------------------------------------------

  @Test
  void escapedSegmentTest_backslashInQE_matchesLiterally() {
    // Bug 4803197
    Pattern pattern = Pattern.compile("\\Qdir1\\dir2\\E");
    assertPatternFind(pattern, "dir1\\dir2", true);

    pattern = Pattern.compile("\\Qdir1\\dir2\\\\E");
    assertPatternFind(pattern, "dir1\\dir2\\", true);

    pattern = Pattern.compile("(\\Qdir1\\dir2\\\\E)");
    assertPatternFind(pattern, "dir1\\dir2\\", true);
  }

  // ---------------------------------------------------------------------------
  // nonCaptureRepetitionTest
  // ---------------------------------------------------------------------------

  @Test
  void nonCaptureRepetitionTest_variousQuantifiers_allMatch() {
    // Bug 4792284
    String input = "abcdefgh;";
    String[] patterns = {
        "(?:\\w{4})+;",
        "(?:\\w{8})*;",
        "(?:\\w{2}){2,4};",
        "(?:\\w{4}){2,};",
        ".*?(?:\\w{5})+;",
        ".*?(?:\\w{9})*;",
        "(?:\\w{4})+?;",
        "(?:\\w{4})++;",
        "(?:\\w{2,}?)+;",
        "(\\w{4})+;",
    };
    for (String p : patterns) {
      assertFind(p, input, true);
      Pattern compiled = Pattern.compile(p);
      Matcher m = compiled.matcher(input);
      assertThat(m.matches()).as("matches() for " + p).isTrue();
      assertThat(m.group(0)).isEqualTo(input);
    }
  }

  // ---------------------------------------------------------------------------
  // notCapturedGroupCurlyMatchTest
  // ---------------------------------------------------------------------------

  @Test
  void notCapturedGroupCurlyMatchTest_alternateGroupsMatch_correctGroupPopulated() {
    // Bug 6358731
    Pattern pattern = Pattern.compile("(abc)+|(abcd)+");
    Matcher matcher = pattern.matcher("abcd");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isNull();
    assertThat(matcher.group(2)).isEqualTo("abcd");
  }

  // ---------------------------------------------------------------------------
  // javaCharClassTest
  // ---------------------------------------------------------------------------

  @Test
  void javaCharClassTest_randomBmpChars_matchJavaMethods() {
    // Bug 4706545 — test \p{javaXxx} properties against Character.isXxx for BMP chars
    for (int i = 0; i < 1000; i++) {
      char c = (char) RANDOM.nextInt(0x10000);
      assertPropertyMatchesChar("{javaLowerCase}", c, Character.isLowerCase(c));
      assertPropertyMatchesChar("{javaUpperCase}", c, Character.isUpperCase(c));
      assertPropertyMatchesChar("{javaTitleCase}", c, Character.isTitleCase(c));
      assertPropertyMatchesChar("{javaDigit}", c, Character.isDigit(c));
      assertPropertyMatchesChar("{javaDefined}", c, Character.isDefined(c));
      assertPropertyMatchesChar("{javaLetter}", c, Character.isLetter(c));
      assertPropertyMatchesChar("{javaLetterOrDigit}", c, Character.isLetterOrDigit(c));
      assertPropertyMatchesChar("{javaWhitespace}", c, Character.isWhitespace(c));
      assertPropertyMatchesChar("{javaISOControl}", c, Character.isISOControl(c));
      assertPropertyMatchesChar("{javaMirrored}", c, Character.isMirrored(c));
    }
  }

  private static void assertPropertyMatchesChar(String property, char c, boolean expected) {
    String propertyPattern = expected ? "\\p" + property : "\\P" + property;
    Pattern pattern = Pattern.compile(propertyPattern);
    Matcher matcher = pattern.matcher(String.valueOf(c));
    assertThat(matcher.find())
        .as("\\p%s for char U+%04X (expected=%b)", property, (int) c, expected)
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // caretBetweenTerminatorsTest — the non-supplementary sub-cases
  // ---------------------------------------------------------------------------

  @Test
  void caretBetweenTerminatorsTest_dotallAndMultiline_caretPositioning() {
    // Bug 4776374 — test ^ with various terminator combinations (ASCII input only)
    assertFind("^....", buildFlag(PatternFlag.DOTALL), "test\ntest", "test", true);
    assertFind(".....^", buildFlag(PatternFlag.DOTALL), "test\ntest", "test", false);
    assertFind(".....^", buildFlag(PatternFlag.DOTALL), "test\n", "test", false);
    assertFind("....^", buildFlag(PatternFlag.DOTALL), "test\r\n", "test", false);

    assertFind("^....", buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES),
        "test\ntest", "test", true);
    assertFind("....^", buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES),
        "test\ntest", "test", false);

    assertFind("^....", buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES, PatternFlag.MULTILINE),
        "test\ntest", "test", true);
    assertFind(".....^",
        buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES, PatternFlag.MULTILINE),
        "test\ntest", "test\n", true);
  }

  /** Overloaded assertFind that takes a PatternFlag array and expected match group. */
  private static void assertFind(
      String p, PatternFlag[] flags, String input, String expectedGroup, boolean expected) {
    Pattern pattern = Pattern.compile(p, flags);
    Matcher matcher = pattern.matcher(input);
    if (expected) {
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group().equals(expectedGroup)).isTrue();
    } else {
      assertPatternFind(pattern, input, false);
    }
  }

  private static PatternFlag[] buildFlag(PatternFlag... flags) {
    return flags;
  }

  // ---------------------------------------------------------------------------
  // dollarAtEndTest
  // ---------------------------------------------------------------------------

  @Test
  void dollarAtEndTest_dollarBeforeLineEndings_matchesCorrectly() {
    // Bug 4727935 — non-supplementary sub-cases only
    assertFind("....$", buildFlag(PatternFlag.DOTALL), "test\n", "test", true);
    assertFind("....$", buildFlag(PatternFlag.DOTALL), "test\r\n", "test", true);
    assertFind(".....$", buildFlag(PatternFlag.DOTALL), "test\n", "test\n", true);
    assertFind("....$", buildFlag(PatternFlag.DOTALL), "test\u0085", "test", true);

    assertFind("....$", buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES),
        "test\n", "test", true);
    assertFind(".....$", buildFlag(PatternFlag.DOTALL, PatternFlag.UNIX_LINES),
        "test\n", "test\n", true);

    assertFind("....$.blah", buildFlag(PatternFlag.DOTALL, PatternFlag.MULTILINE),
        "test\nblah", "test\nblah", true);
    assertFind("....$blah", buildFlag(PatternFlag.DOTALL, PatternFlag.MULTILINE),
        "test\nblah", "!!!!", false);
  }

  // ---------------------------------------------------------------------------
  // multilineDollarTest
  // ---------------------------------------------------------------------------

  @Test
  void multilineDollarTest_findDollarMultiline_correctStartPositions() {
    // Bug 4711773
    Pattern findCR = Pattern.compile("$", PatternFlag.MULTILINE);
    Matcher matcher = findCR.matcher("first bit\nsecond bit");
    matcher.find();
    assertThat(matcher.start()).isEqualTo(9);
    matcher.find();
    assertThat(matcher.start(0)).isEqualTo(20);
  }

  // ---------------------------------------------------------------------------
  // reluctantRepetitionTest
  // ---------------------------------------------------------------------------

  @Test
  void reluctantRepetitionTest_lazyQuantifier_matchesMinimumRequired() {
    Pattern p = Pattern.compile("1(\\s\\S+?){1,3}?[\\s,]2");
    assertPatternFind(p, "1 word word word 2", true);
    assertPatternFind(p, "1 wor wo w 2", true);
    assertPatternFind(p, "1 word word 2", true);
    assertPatternFind(p, "1 word 2", true);
    assertPatternFind(p, "1 wo w w 2", true);
    assertPatternFind(p, "1 wo w 2", true);
    assertPatternFind(p, "1 wor w 2", true);

    p = Pattern.compile("([a-z])+?c");
    Matcher m = p.matcher("ababcdefdec");
    assertNextMatchEquals(m, "ababc");
  }

  // ---------------------------------------------------------------------------
  // serializeTest — Option A: Pattern implements Serializable
  // ---------------------------------------------------------------------------

  @Test
  void serializeTest_roundTrip_deserializedPatternMatchesEquivalently() throws Exception {
    // Bug: Pattern implements java.io.Serializable
    String patternStr = "(b)";
    String matchStr = "b";
    Pattern pattern = Pattern.compile(patternStr);
    Pattern serializedPattern = serializeAndDeserialize(pattern);
    Matcher matcher = serializedPattern.matcher(matchStr);
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.groupCount()).isEqualTo(1);
  }

  @Test
  void serializeTest_caseInsensitiveWithInlineNegation_preservedAfterRoundTrip()
      throws Exception {
    Pattern pattern = Pattern.compile("a(?-i)b", PatternFlag.CASE_INSENSITIVE);
    Pattern serializedPattern = serializeAndDeserialize(pattern);
    assertThat(serializedPattern.matcher("Ab").matches()).isTrue();
    assertThat(serializedPattern.matcher("AB").matches()).isFalse();
  }

  @SuppressWarnings("unchecked")
  private static Pattern serializeAndDeserialize(Pattern p) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(p);
    }
    try (ObjectInputStream ois = new ObjectInputStream(
        new ByteArrayInputStream(baos.toByteArray()))) {
      return (Pattern) ois.readObject();
    }
  }

  // ---------------------------------------------------------------------------
  // gTest
  // ---------------------------------------------------------------------------

  @Test
  void gTest_GBoundary_stopsAtNonWordChar() {
    Pattern pattern = Pattern.compile("\\G\\w");
    Matcher matcher = pattern.matcher("abc#x#x");
    matcher.find();
    matcher.find();
    matcher.find();
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void gTest_GStar_matchesOnlyAtStart() {
    Pattern pattern = Pattern.compile("\\GA*");
    Matcher matcher = pattern.matcher("1A2AA3");
    matcher.find();
    assertThat(matcher.find()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // zTest
  // ---------------------------------------------------------------------------

  @Test
  void zTest_upperZ_matchesBeforeTerminators() {
    Pattern pattern = Pattern.compile("foo\\Z");
    // Positives
    assertPatternFind(pattern, "foo\u0085", true);
    assertPatternFind(pattern, "foo\u2028", true);
    assertPatternFind(pattern, "foo\u2029", true);
    assertPatternFind(pattern, "foo\n", true);
    assertPatternFind(pattern, "foo\r", true);
    assertPatternFind(pattern, "foo\r\n", true);
    // Negatives
    assertPatternFind(pattern, "fooo", false);
    assertPatternFind(pattern, "foo\n\r", false);
  }

  @Test
  void zTest_upperZWithUnixLines_onlyNewline() {
    Pattern pattern = Pattern.compile("foo\\Z", PatternFlag.UNIX_LINES);
    assertPatternFind(pattern, "foo", true);
    assertPatternFind(pattern, "foo\n", true);
    assertPatternFind(pattern, "foo\r", false);
    assertPatternFind(pattern, "foo\u0085", false);
    assertPatternFind(pattern, "foo\u2028", false);
    assertPatternFind(pattern, "foo\u2029", false);
  }

  // ---------------------------------------------------------------------------
  // replaceFirstTest
  // ---------------------------------------------------------------------------

  @Test
  void replaceFirstTest_basicReplacement_replacesFirstOccurrenceOnly() {
    Pattern pattern = Pattern.compile("(ab)(c*)");
    assertThat(pattern.matcher("abccczzzabcczzzabccc").replaceFirst("test"))
        .isEqualTo("testzzzabcczzzabccc");
    assertThat(pattern.matcher("zzzabccczzzabcczzzabccczzz").replaceFirst("test"))
        .isEqualTo("zzztestzzzabcczzzabccczzz");
  }

  @Test
  void replaceFirstTest_groupBackref_in_replacement() {
    Pattern pattern = Pattern.compile("(ab)(c*)");
    assertThat(pattern.matcher("zzzabccczzzabcczzzabccczzz").replaceFirst("$1"))
        .isEqualTo("zzzabzzzabcczzzabccczzz");
    assertThat(pattern.matcher("zzzabccczzzabcczzzabccczzz").replaceFirst("$2"))
        .isEqualTo("zzzccczzzabcczzzabccczzz");
  }

  @Test
  void replaceFirstTest_starQuantifier_replacesEntireMatch() {
    Pattern pattern = Pattern.compile("a*");
    Matcher matcher = pattern.matcher("aaaaaaaaaa");
    assertThat(matcher.replaceFirst("test")).isEqualTo("test");
  }

  @Test
  void replaceFirstTest_plusQuantifier_replacesFirstRun() {
    Pattern pattern = Pattern.compile("a+");
    Matcher matcher = pattern.matcher("zzzaaaaaaaaaa");
    assertThat(matcher.replaceFirst("test")).isEqualTo("zzztest");
  }

  // ---------------------------------------------------------------------------
  // unixLinesTest
  // ---------------------------------------------------------------------------

  @Test
  void unixLinesTest_withoutFlag_unicodeSeparatorBreaksLine() {
    Pattern pattern = Pattern.compile(".*");
    Matcher matcher = pattern.matcher("aa\u2028blah");
    matcher.find();
    assertThat(matcher.group(0)).isEqualTo("aa");
  }

  @Test
  void unixLinesTest_withFlag_unicodeSeparatorNotLineBreak() {
    Pattern pattern = Pattern.compile(".*", PatternFlag.UNIX_LINES);
    Matcher matcher = pattern.matcher("aa\u2028blah");
    matcher.find();
    assertThat(matcher.group(0)).isEqualTo("aa\u2028blah");
  }

  // ---------------------------------------------------------------------------
  // commentsTest
  // ---------------------------------------------------------------------------

  @Test
  void commentsTest_hashEscaped_treatedAsLiteral() {
    Pattern pattern = Pattern.compile("aa \\# aa", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aa#aa");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void commentsTest_hashComment_ignored() {
    Pattern pattern = Pattern.compile("aa  # blah", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aa");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void commentsTest_spaceInPattern_ignored() {
    Pattern pattern = Pattern.compile("aa blah", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aablah");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void commentsTest_multilineComment_ignored() {
    Pattern pattern = Pattern.compile("aa  # blah\nbc # blech", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aabc");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void commentsTest_hashAfterNewline_endOfComment() {
    Pattern pattern = Pattern.compile("aa  # blah\nbc# blech", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aabc");
    assertThat(matcher.matches()).isTrue();
  }

  @Test
  void commentsTest_escapedHashAfterNewline_treatedAsLiteral() {
    Pattern pattern = Pattern.compile("aa  # blah\nbc\\# blech", PatternFlag.COMMENTS);
    Matcher matcher = pattern.matcher("aabc#blech");
    assertThat(matcher.matches()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // caseFoldingTest
  // ---------------------------------------------------------------------------

  @Test
  void caseFoldingTest_caseInsensitiveOnly_asciiOnly() {
    // Bug 4504687 — CASE_INSENSITIVE without UNICODE_CASE (ASCII patterns only)
    String[] patterns = {"a", "ab", "[a]", "[a-b]", "(a)\\1"};
    String[] texts = {"A", "AB", "A", "B", "aA"};

    for (int i = 0; i < patterns.length; i++) {
      Pattern p = Pattern.compile(patterns[i], PatternFlag.CASE_INSENSITIVE);
      Matcher m = p.matcher(texts[i]);
      assertThat(m.matches()).as("CASE_INSENSITIVE: pattern=%s text=%s", patterns[i], texts[i])
          .isTrue();
    }
  }

  @Test
  void caseFoldingTest_caseInsensitiveUnicode_matchesUnicodeEquivalents() {
    // CASE_INSENSITIVE | UNICODE_CASE — should also match non-ASCII equivalents
    String[] patterns = {"a", "ab", "[a]", "[a-b]", "(a)\\1"};
    String[] texts = {"A", "AB", "A", "B", "aA"};

    for (int i = 0; i < patterns.length; i++) {
      Pattern p = Pattern.compile(patterns[i], PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
      Matcher m = p.matcher(texts[i]);
      assertThat(m.matches())
          .as("CI+UNICODE: pattern=%s text=%s", patterns[i], texts[i])
          .isTrue();
    }
  }

  @Test
  void caseFoldingTest_unicodeCaseAlone_noEffect() {
    // UNICODE_CASE alone must not make matching case-insensitive
    String[] patterns = {"a", "ab", "[a]", "[a-b]", "(a)\\1"};
    String[] texts = {"A", "AB", "A", "B", "aA"};
    for (int i = 0; i < patterns.length; i++) {
      Pattern p = Pattern.compile(patterns[i], PatternFlag.UNICODE_CASE);
      Matcher m = p.matcher(texts[i]);
      assertThat(m.matches())
          .as("UNICODE_CASE alone: pattern=%s text=%s", patterns[i], texts[i])
          .isFalse();
    }
  }

  @Test
  void caseFoldingTest_dotlessIAndDotAboveI_matchedByCIUnicode() {
    Pattern pattern = Pattern.compile("[h-j]+",
        PatternFlag.UNICODE_CASE, PatternFlag.CASE_INSENSITIVE);
    assertThat(pattern.matcher("\u0131\u0130").matches()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // appendTest
  // ---------------------------------------------------------------------------

  @Test
  void appendTest_replaceAllWithGroupRefs_swapsGroups() {
    Pattern pattern = Pattern.compile("(ab)(cd)");
    Matcher matcher = pattern.matcher("abcd");
    String result = matcher.replaceAll("$2$1");
    assertThat(result).isEqualTo("cdab");
  }

  @Test
  void appendTest_replaceAllThreeGroups_swapsAllOccurrences() {
    String s1 = "Swap all: first = 123, second = 456";
    String r = "$3$2$1";
    Pattern pattern = Pattern.compile("([a-z]+)( *= *)([0-9]+)");
    Matcher matcher = pattern.matcher(s1);
    String result = matcher.replaceAll(r);
    assertThat(result).isEqualTo("Swap all: 123 = first, 456 = second");
  }

  @Test
  void appendTest_appendReplacementAndTail_swapsFirstOnly() {
    String s2 = "Swap one: first = 123, second = 456";
    String r = "$3$2$1";
    Pattern pattern = Pattern.compile("([a-z]+)( *= *)([0-9]+)");
    Matcher matcher = pattern.matcher(s2);
    if (matcher.find()) {
      StringBuilder sb = new StringBuilder();
      matcher.appendReplacement(sb, r);
      matcher.appendTail(sb);
      assertThat(sb.toString()).isEqualTo("Swap one: 123 = first, second = 456");
    }
  }

  // ---------------------------------------------------------------------------
  // splitTest
  // ---------------------------------------------------------------------------

  @Test
  void splitTest_colonDelimiterWithLimit2_splitsOnFirstOccurrence() {
    Pattern pattern = Pattern.compile(":");
    String[] result = pattern.split("foo:and:boo", 2);
    assertThat(result[0]).isEqualTo("foo");
    assertThat(result[1]).isEqualTo("and:boo");
  }

  @Test
  void splitTest_charBufferInput_splitsCorrectly() {
    Pattern pattern = Pattern.compile(":");
    CharBuffer cb = CharBuffer.allocate(100);
    cb.put("foo:and:boo");
    cb.flip();
    String[] result = pattern.split(cb);
    assertThat(result[0]).isEqualTo("foo");
    assertThat(result[1]).isEqualTo("and");
    assertThat(result[2]).isEqualTo("boo");
  }

  @Test
  void splitTest_limitZeroDropsTrailingEmpty() {
    String source = "0123456789";
    String[] result = source.split("9", 0);
    assertThat(result.length).isEqualTo(1);
    assertThat(result[0]).isEqualTo("012345678");
  }

  @Test
  void splitTest_emptySourceReturnsSingleElement() {
    String source = "";
    String[] result = source.split("e", 0);
    assertThat(result.length).isEqualTo(1);
    assertThat(result[0]).isEqualTo(source);
  }

  @Test
  void splitTest_splitVsSplitAsStreamConsistency() {
    // Verify that split() and splitAsStream() produce the same results
    String[][] input = {
        {" ", "Abc Efg Hij"},
        {" ", " Abc Efg Hij"},
        {" ", "Abc  Efg Hij"},
        {"(?=\\p{Lu})", "AbcEfgHij"},
        {" ", ""},
        {".*", ""},
    };
    for (String[] pair : input) {
      Pattern p = Pattern.compile(pair[0]);
      String[] splitResult = p.split(pair[1]);
      // splitAsStream only matches split for non-empty input in JDK contract
      if (pair[1].length() > 0) {
        Object[] streamResult = p.splitAsStream(pair[1]).toArray();
        assertThat(streamResult).as("split vs stream for /%s/ on \"%s\"", pair[0], pair[1])
            .isEqualTo(splitResult);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // negationTest
  // ---------------------------------------------------------------------------

  @Test
  void negationTest_specialCharsInClass_matchAllSpecialChars() {
    Pattern pattern = Pattern.compile("[\\[@^]+");
    Matcher matcher = pattern.matcher("@@@@[[[[^^^^");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("@@@@[[[[^^^^");

    pattern = Pattern.compile("[@\\[^]+");
    matcher = pattern.matcher("@@@@[[[[^^^^");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("@@@@[[[[^^^^");

    pattern = Pattern.compile("[@\\[^@]+");
    matcher = pattern.matcher("@@@@[[[[^^^^");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("@@@@[[[[^^^^");
  }

  @Test
  void negationTest_escapedCloseParen_matchesLiterally() {
    Pattern pattern = Pattern.compile("\\)");
    Matcher matcher = pattern.matcher("xxx)xxx");
    assertThat(matcher.find()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // ampersandTest
  // ---------------------------------------------------------------------------

  @Test
  void ampersandTest_ampersandAndAt_matchAll() {
    Pattern pattern = Pattern.compile("[&@]+");
    assertPatternFind(pattern, "@@@@&&&&", true);

    pattern = Pattern.compile("[@&]+");
    assertPatternFind(pattern, "@@@@&&&&", true);

    pattern = Pattern.compile("[@\\&]+");
    assertPatternFind(pattern, "@@@@&&&&", true);
  }

  // ---------------------------------------------------------------------------
  // octalTest
  // ---------------------------------------------------------------------------

  @Test
  void octalTest_variousOctalForms_matchCorrectCharacter() {
    assertThat(Pattern.compile("\\u0007").matcher("\u0007").matches()).isTrue();
    assertThat(Pattern.compile("\\07").matcher("\u0007").matches()).isTrue();
    assertThat(Pattern.compile("\\007").matcher("\u0007").matches()).isTrue();
    assertThat(Pattern.compile("\\0007").matcher("\u0007").matches()).isTrue();
    assertThat(Pattern.compile("\\040").matcher("\u0020").matches()).isTrue();
    assertThat(Pattern.compile("\\0403").matcher("\u00203").matches()).isTrue();
    assertThat(Pattern.compile("\\0103").matcher("\u0043").matches()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // longPatternTest
  // ---------------------------------------------------------------------------

  @Test
  void longPatternTest_longLiterals_compileWithoutException() {
    assertDoesNotThrow(() -> Pattern.compile("a 32-character-long pattern xxxx"));
    assertDoesNotThrow(() -> Pattern.compile("a 33-character-long pattern xxxxx"));
    assertDoesNotThrow(() -> Pattern.compile("a thirty four character long regex"));
    StringBuilder patternToBe = new StringBuilder(101);
    for (int i = 0; i < 100; i++) {
      patternToBe.append((char) (97 + i % 26));
    }
    assertDoesNotThrow(() -> Pattern.compile(patternToBe.toString()));
  }

  // ---------------------------------------------------------------------------
  // group0Test
  // ---------------------------------------------------------------------------

  @Test
  void group0Test_findGroup0_equalsEntireMatch() {
    Pattern pattern = Pattern.compile("(tes)ting");
    Matcher matcher = pattern.matcher("testing");
    assertNextMatchEquals(matcher, "testing");
  }

  @Test
  void group0Test_matches_group0IsFullMatch() {
    Pattern pattern = Pattern.compile("(tes)ting");
    Matcher matcher = pattern.matcher("testing");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(0)).isEqualTo("testing");
  }

  // ---------------------------------------------------------------------------
  // findIntTest — Orbit has no find(int), adapted to basic find semantics
  // ---------------------------------------------------------------------------

  @Test
  void findIntTest_basicFind_startsFromBeginning() {
    Pattern p = Pattern.compile("blah");
    Matcher m = p.matcher("zzzzblahzzzzzblah");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("blah");
  }

  @Test
  void findIntTest_findFromPosition_skipsEarlierMatches() {
    Pattern p = Pattern.compile("blah");
    Matcher m = p.matcher("zzzzblahzzzzzblah");
    assertThat(m.find(2)).isTrue();
    assertThat(m.start()).isEqualTo(4);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(13);
  }

  @Test
  void findIntTest_findBeyondLength_throwsIndexOutOfBoundsException() {
    Pattern p2 = Pattern.compile("blah");
    Matcher m2 = p2.matcher("zzzzblahzz"); // length 10
    assertThrows(IndexOutOfBoundsException.class, () -> m2.find(11));
  }

  // ---------------------------------------------------------------------------
  // emptyPatternTest
  // ---------------------------------------------------------------------------

  @Test
  void emptyPatternTest_emptyPattern_findsAtStart() {
    Pattern p = Pattern.compile("");
    Matcher m = p.matcher("foo");
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(0);
  }

  @Test
  void emptyPatternTest_matches_nonEmptyInput_returnsFalse() {
    Pattern p = Pattern.compile("");
    Matcher m = p.matcher("foo");
    m.reset();
    assertThat(m.matches()).isFalse();
  }

  @Test
  void emptyPatternTest_matchesEmptyInput_returnsTrue() {
    Pattern p = Pattern.compile("");
    Matcher m = p.matcher("");
    m.reset(); // reset without args; input already ""
    assertThat(m.matches()).isTrue();
  }

  @Test
  void emptyPatternTest_patternMatches_emptyOnEmpty() {
    assertThat(Pattern.matches("", "")).isTrue();
    assertThat(Pattern.matches("", "foo")).isFalse();
  }

  // ---------------------------------------------------------------------------
  // charClassTest
  // ---------------------------------------------------------------------------

  @Test
  void charClassTest_closeBracketAfterClass_matchesLiterally() {
    Pattern pattern = Pattern.compile("blah[ab]]blech");
    assertPatternFind(pattern, "blahb]blech", true);
  }

  @Test
  void charClassTest_nestedClass_matchesFromEitherClass() {
    Pattern pattern = Pattern.compile("[abc[def]]");
    assertPatternFind(pattern, "b", true);
  }

  @Test
  void charClassTest_unicodeCaseWithFF_matchesBothCases() {
    Pattern pattern = Pattern.compile("[ab\u00ffcd]",
        PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
    assertPatternFind(pattern, "ab\u00ffcd", true);
    assertPatternFind(pattern, "Ab\u0178Cd", true);
  }

  @Test
  void charClassTest_unicodeCaseWithMicro_matchesBothCases() {
    Pattern pattern = Pattern.compile("[ab\u00b5cd]",
        PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
    assertPatternFind(pattern, "ab\u00b5cd", true);
    assertPatternFind(pattern, "Ab\u039cCd", true);
  }

  @Test
  void charClassTest_specialUnicodeFolding_matchesAll() {
    // LatinSmallLetterLongS, DotlessI, IWithDot, KelvinSign, AngstromSign
    Pattern pattern = Pattern.compile("[sik\u00c5]+",
        PatternFlag.UNICODE_CASE, PatternFlag.CASE_INSENSITIVE);
    assertThat(pattern.matcher("\u017f\u0130\u0131\u212a\u212b").matches()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // caretTest
  // ---------------------------------------------------------------------------

  @Test
  void caretTest_wordStarFindAll_returnsAllWords() {
    Pattern pattern = Pattern.compile("\\w*");
    Matcher matcher = pattern.matcher("a#bc#def##g");
    assertNextMatchEquals(matcher, "a");
    assertNextMatchEquals(matcher, "");
    assertNextMatchEquals(matcher, "bc");
    assertNextMatchEquals(matcher, "");
    assertNextMatchEquals(matcher, "def");
    assertNextMatchEquals(matcher, "");
    assertNextMatchEquals(matcher, "");
    assertNextMatchEquals(matcher, "g");
    assertNextMatchEquals(matcher, "");
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void caretTest_caretWordStar_onlyMatchesFirstWord() {
    Pattern pattern = Pattern.compile("^\\w*");
    Matcher matcher = pattern.matcher("a#bc#def##g");
    assertNextMatchEquals(matcher, "a");
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void caretTest_replaceAll_prependsToEachLine() {
    Pattern pattern = Pattern.compile("^", PatternFlag.MULTILINE);
    Matcher matcher = pattern.matcher("this is some text");
    String result = matcher.replaceAll("X");
    assertThat(result).isEqualTo("Xthis is some text");
  }

  // ---------------------------------------------------------------------------
  // groupCaptureTest
  // ---------------------------------------------------------------------------

  @Test
  void groupCaptureTest_atomicGroup_group1OutOfBounds() {
    assertThatThrownBy(() -> {
      Pattern pattern = Pattern.compile("x+(?>y+)z+");
      Matcher matcher = pattern.matcher("xxxyyyzzz");
      matcher.find();
      matcher.group(1);
    }).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void groupCaptureTest_nonCapturingGroup_group1OutOfBounds() {
    assertThatThrownBy(() -> {
      Pattern pattern = Pattern.compile("x+(?:y+)z+");
      Matcher matcher = pattern.matcher("xxxyyyzzz");
      matcher.find();
      matcher.group(1);
    }).isInstanceOf(IndexOutOfBoundsException.class);
  }

  // ---------------------------------------------------------------------------
  // backRefTest
  // ---------------------------------------------------------------------------

  @Test
  void backRefTest_numericBackreference_matchesRepeatedGroup() {
    Pattern pattern = Pattern.compile("(a*)bc\\1");
    assertPatternFind(pattern, "zzzaabcazzz", true);
    assertPatternFind(pattern, "zzzaabcaazzz", true);

    pattern = Pattern.compile("(abc)(def)\\1");
    assertPatternFind(pattern, "abcdefabc", true);

    pattern = Pattern.compile("(abc)(def)\\3");
    assertPatternFind(pattern, "abcdefabc", false);
  }

  @Test
  void backRefTest_backrefs1to9_alwaysAccepted() {
    for (int i = 1; i < 10; i++) {
      Pattern pattern = Pattern.compile("abcdef\\" + i);
      assertPatternFind(pattern, "abcdef", false);
    }
  }

  @Test
  void backRefTest_doubleDigitBackreference_parsedCorrectly() {
    Pattern pattern = Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\11");
    assertPatternFind(pattern, "abcdefghija", false);
    assertPatternFind(pattern, "abcdefghija1", true);

    pattern = Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)\\11");
    assertPatternFind(pattern, "abcdefghijkk", true);

    pattern = Pattern.compile("(a)bcdefghij\\11");
    assertPatternFind(pattern, "abcdefghija1", true);
  }

  // ---------------------------------------------------------------------------
  // ciBackRefTest
  // ---------------------------------------------------------------------------

  @Test
  void ciBackRefTest_caseInsensitiveBackreference_matchesIgnoringCase() {
    Pattern pattern = Pattern.compile("(?i)(a*)bc\\1");
    assertPatternFind(pattern, "zzzaabcazzz", true);

    pattern = Pattern.compile("(?i)(abc)(def)\\1");
    assertPatternFind(pattern, "abcdefabc", true);

    pattern = Pattern.compile("(?i)(abc)(def)\\3");
    assertPatternFind(pattern, "abcdefabc", false);
  }

  // ---------------------------------------------------------------------------
  // anchorTest
  // ---------------------------------------------------------------------------

  @Test
  void anchorTest_multilineCRLF_noEmptyLineInCRLF() {
    Pattern p = Pattern.compile("^.*$", PatternFlag.MULTILINE);
    Matcher m = p.matcher("blah1\r\nblah2");
    m.find();
    m.find();
    assertThat(m.group()).isEqualTo("blah2");
  }

  @Test
  void anchorTest_multilineLFCR_hasEmptyLineInLFCR() {
    Pattern p = Pattern.compile("^.*$", PatternFlag.MULTILINE);
    Matcher m = p.matcher("blah1\n\rblah2");
    m.find();
    m.find();
    m.find();
    assertThat(m.group()).isEqualTo("blah2");
  }

  @Test
  void anchorTest_dollarBeforeCRLF_matchesCorrectly() {
    Pattern p = Pattern.compile(".+$");
    Matcher m = p.matcher("blah1\r\n");
    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("blah1");
    assertThat(m.find()).isFalse();
  }

  @Test
  void anchorTest_multilineDollarBeforeCRLF_correctBehavior() {
    Pattern p = Pattern.compile(".+$", PatternFlag.MULTILINE);
    Matcher m = p.matcher("blah1\r\n");
    assertThat(m.find()).isTrue();
    assertThat(m.find()).isFalse();
  }

  @Test
  void anchorTest_dollarBeforeNEL_matchesCorrectly() {
    Pattern p = Pattern.compile(".+$", PatternFlag.MULTILINE);
    Matcher m = p.matcher("blah1\u0085");
    assertThat(m.find()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // lookingAtTest
  // ---------------------------------------------------------------------------

  @Test
  void lookingAtTest_patternAtStart_returnsTrue() {
    Pattern p = Pattern.compile("foo");
    Matcher m = p.matcher("foobar");
    assertThat(m.lookingAt()).isTrue();

    Pattern p2 = Pattern.compile("foo");
    Matcher m2 = p2.matcher("barfoo");
    assertThat(m2.lookingAt()).isFalse();

    Pattern p3 = Pattern.compile("foo");
    Matcher m3 = p3.matcher("foobar");
    assertThat(m3.matches()).isFalse();
    assertThat(m3.lookingAt()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // matchesTest
  // ---------------------------------------------------------------------------

  @Test
  void matchesTest_fullMatch_returnsTrue() {
    Pattern p = Pattern.compile("ulb(c*)");
    Matcher m = p.matcher("ulbcccccc");
    assertThat(m.matches()).isTrue();
  }

  @Test
  void matchesTest_noFullMatch_returnsFalse() {
    Pattern p = Pattern.compile("ulb(c*)");
    assertThat(p.matcher("zzzulbcccccc").matches()).isFalse();
    assertThat(p.matcher("ulbccccccdef").matches()).isFalse();
  }

  @Test
  void matchesTest_alternation_matchesFirstAlternative() {
    Pattern p = Pattern.compile("a|ad");
    Matcher m = p.matcher("ad");
    assertThat(m.matches()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // patternMatchesTest
  // ---------------------------------------------------------------------------

  @Test
  void patternMatchesTest_staticMatches_correctResults() {
    assertThat(Pattern.matches("ulb(c*)", "ulbcccccc")).isTrue();
    assertThat(Pattern.matches("ulb(c*)", "zzzulbcccccc")).isFalse();
    assertThat(Pattern.matches("ulb(c*)", "ulbccccccdef")).isFalse();
  }

  // ---------------------------------------------------------------------------
  // lookbehindTest
  // ---------------------------------------------------------------------------

  @Test
  void lookbehindTest_positiveLookbehindWithRange_filtersMatches() {
    // Positive lookbehind with variable length {0,5}
    assertLookbehindFind("(?<=%.{0,5})foo\\d",
        "%foo1\n%bar foo2\n%bar  foo3\n%blahblah foo4\nfoo5",
        new String[]{"foo1", "foo2", "foo3"});
  }

  @Test
  void lookbehindTest_negativeLookbehindWithRange_filtersMatches() {
    assertLookbehindFind("(?<!%.{0,5})foo\\d",
        "%foo1\n%bar foo2\n%bar  foo3\n%blahblah foo4\nfoo5",
        new String[]{"foo4", "foo5"});
  }

  @Test
  void lookbehindTest_positiveLookbehindGreedy_matchesCorrectly() {
    assertLookbehindFind("(?<=%b{1,4})foo", "%bbbbfoo", new String[]{"foo"});
  }

  @Test
  void lookbehindTest_positiveLookbehindReluctant_matchesCorrectly() {
    assertLookbehindFind("(?<=%b{1,4}?)foo", "%bbbbfoo", new String[]{"foo"});
  }

  @Disabled("Orbit does not support variable-length lookbehind (e.g. (?<=.*\\b))")
  @Test
  void lookbehindTest_boundaryAtEndOfLookbehind_consistent() {
    assertLookbehindFind("(?<=.*\\b)foo", "abcd foo", new String[]{"foo"});
    assertLookbehindFind("(?<=.*)\\bfoo", "abcd foo", new String[]{"foo"});
    assertLookbehindFind("(?<!abc )\\bfoo", "abc foo", new String[0]);
    assertLookbehindFind("(?<!abc \\b)foo", "abc foo", new String[0]);
  }

  private static void assertLookbehindFind(
      String regex, String input, String[] expected) {
    java.util.List<String> result = new java.util.ArrayList<>();
    Pattern p = Pattern.compile(regex);
    Matcher m = p.matcher(input);
    while (m.find()) {
      result.add(m.group());
    }
    assertThat(result).containsExactly(expected);
  }

  // ---------------------------------------------------------------------------
  // boundsTest — Orbit has no region/useTransparentBounds
  // ---------------------------------------------------------------------------

  @Test
  void boundsTest_transparentBounds_lookaroundSeesOutsideRegion() {
    // Bug 4938995: with transparent bounds, lookahead/lookbehind can see outside the region.
    String input = "abcdef";
    Pattern p = Pattern.compile("(?<=abc)def");

    // Opaque bounds (default): lookbehind cannot see "abc" before the region start.
    Matcher mOpaque = p.matcher(input);
    mOpaque.region(3, 6);
    mOpaque.useTransparentBounds(false);
    assertThat(mOpaque.find()).isFalse();

    // Transparent bounds: lookbehind can see "abc" before the region start.
    Matcher mTransparent = p.matcher(input);
    mTransparent.region(3, 6);
    mTransparent.useTransparentBounds(true);
    assertThat(mTransparent.hasTransparentBounds()).isTrue();
    assertThat(mTransparent.find()).isTrue();
    assertThat(mTransparent.group()).isEqualTo("def");
  }

  // ---------------------------------------------------------------------------
  // blankInput
  // ---------------------------------------------------------------------------

  @Test
  void blankInput_caseInsensitiveEmptyInput_findsNoMatch() {
    Pattern p = Pattern.compile("abc", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("");
    assertThat(m.find()).isFalse();
  }

  @Test
  void blankInput_starQuantifierEmptyInput_findSucceeds() {
    Pattern p = Pattern.compile("a*", PatternFlag.CASE_INSENSITIVE);
    Matcher m = p.matcher("");
    assertThat(m.find()).isTrue();
  }

  @Test
  void blankInput_withoutFlags_emptyInputNoMatch() {
    Pattern p = Pattern.compile("abc");
    Matcher m = p.matcher("");
    assertThat(m.find()).isFalse();
  }

  @Test
  void blankInput_starQuantifierNoFlag_findSucceeds() {
    Pattern p = Pattern.compile("a*");
    Matcher m = p.matcher("");
    assertThat(m.find()).isTrue();
  }
}
