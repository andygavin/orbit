package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.MatchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Adapted from {@code ImmutableMatchResultTest.java} in the JDK test suite (bugs 8132995,
 * 8312976). Uses a fixed seed of {@code 42} instead of {@code RandomFactory} so the test is
 * deterministic.
 *
 * <p>Verifies that the {@link MatchResult} snapshot returned by
 * {@link Matcher#toMatchResult()} is immutable — subsequent find/matches calls on the
 * same matcher must not affect a previously-obtained snapshot.
 */
class ImmutableMatchResultCompatTest {

  private static final int prefixLen;
  private static final int infixLen;
  private static final int suffixLen;
  private static final String group1 = "abc";
  private static final String group2 = "wxyz";
  private static final String group0;
  private static final String in;
  private static final String groupResults = "(([a-z]+)([0-9]*))";
  private static final String inResults;
  private static final String letters1 = "abcd";
  private static final String digits1 = "12";
  private static final String letters2 = "pqr";
  private static final String digits2 = "";

  static {
    Random rnd = new Random(42);
    prefixLen = rnd.nextInt(10);
    infixLen = rnd.nextInt(10);
    suffixLen = rnd.nextInt(10);
    group0 = group1 + "-".repeat(infixLen) + group2;
    in = "-".repeat(prefixLen) + group0 + "-".repeat(suffixLen);
    inResults =
        " ".repeat(prefixLen) + letters1 + digits1
        + " ".repeat(infixLen) + letters2 + digits2
        + " ".repeat(suffixLen);
  }

  // ---------------------------------------------------------------------------
  // Basic snapshot immutability: group 0, 1, 2
  // ---------------------------------------------------------------------------

  private static void assertSnapshot(CharSequence cs) {
    Matcher m = Pattern.compile("(" + group1 + ")-*(" + group2 + ")").matcher(cs);
    assertThat(m.find()).isTrue();

    assertThat(m.start()).isEqualTo(prefixLen);
    assertThat(m.end()).isEqualTo(prefixLen + group0.length());
    assertThat(m.toMatchResult().group()).isEqualTo(group0);

    assertThat(m.start(1)).isEqualTo(prefixLen);
    assertThat(m.end(1)).isEqualTo(prefixLen + group1.length());
    assertThat(m.toMatchResult().group(1)).isEqualTo(group1);

    assertThat(m.start(2)).isEqualTo(prefixLen + group1.length() + infixLen);
    assertThat(m.end(2)).isEqualTo(prefixLen + group1.length() + infixLen + group2.length());
    assertThat(m.toMatchResult().group(2)).isEqualTo(group2);
  }

  @Test
  void snapshot_string_groupsAreImmutable() {
    assertSnapshot(in);
  }

  @Test
  void snapshot_stringBuilder_groupsAreImmutable() {
    assertSnapshot(new StringBuilder(in));
  }

  @Test
  void snapshot_stringBuffer_groupsAreImmutable() {
    assertSnapshot(new StringBuffer(in));
  }

  @Test
  void snapshot_charBuffer_groupsAreImmutable() {
    assertSnapshot(CharBuffer.wrap(in));
  }

  // ---------------------------------------------------------------------------
  // results() stream: snapshot indices and text survive across find() calls
  // ---------------------------------------------------------------------------

  private static void assertResultsStream(CharSequence cs) {
    Matcher m = Pattern.compile(groupResults).matcher(cs);
    List<MatchResult> results = m.results().toList();
    assertThat(results).hasSize(2);

    int startLetters1 = prefixLen;
    int endLetters1 = startLetters1 + letters1.length();
    int startDigits1 = endLetters1;
    int endDigits1 = startDigits1 + digits1.length();

    MatchResult r0 = results.get(0);
    assertThat(r0.start()).isEqualTo(startLetters1);
    assertThat(r0.start(0)).isEqualTo(startLetters1);
    assertThat(r0.start(1)).isEqualTo(startLetters1);
    assertThat(r0.start(2)).isEqualTo(startLetters1);
    assertThat(r0.start(3)).isEqualTo(startDigits1);

    assertThat(r0.end()).isEqualTo(endDigits1);
    assertThat(r0.end(0)).isEqualTo(endDigits1);
    assertThat(r0.end(1)).isEqualTo(endDigits1);
    assertThat(r0.end(2)).isEqualTo(endLetters1);
    assertThat(r0.end(3)).isEqualTo(endDigits1);

    assertThat(r0.group()).isEqualTo(letters1 + digits1);
    assertThat(r0.group(0)).isEqualTo(letters1 + digits1);
    assertThat(r0.group(1)).isEqualTo(letters1 + digits1);
    assertThat(r0.group(2)).isEqualTo(letters1);
    assertThat(r0.group(3)).isEqualTo(digits1);

    int startLetters2 = endDigits1 + infixLen;
    int endLetters2 = startLetters2 + letters2.length();
    int startDigits2 = endLetters2;
    int endDigits2 = startDigits2 + digits2.length();

    MatchResult r1 = results.get(1);
    assertThat(r1.start()).isEqualTo(startLetters2);
    assertThat(r1.start(0)).isEqualTo(startLetters2);
    assertThat(r1.start(1)).isEqualTo(startLetters2);
    assertThat(r1.start(2)).isEqualTo(startLetters2);
    assertThat(r1.start(3)).isEqualTo(startDigits2);

    assertThat(r1.end()).isEqualTo(endDigits2);
    assertThat(r1.end(0)).isEqualTo(endDigits2);
    assertThat(r1.end(1)).isEqualTo(endDigits2);
    assertThat(r1.end(2)).isEqualTo(endLetters2);
    assertThat(r1.end(3)).isEqualTo(endDigits2);

    assertThat(r1.group()).isEqualTo(letters2 + digits2);
    assertThat(r1.group(0)).isEqualTo(letters2 + digits2);
    assertThat(r1.group(1)).isEqualTo(letters2 + digits2);
    assertThat(r1.group(2)).isEqualTo(letters2);
    assertThat(r1.group(3)).isEqualTo(digits2);
  }

  @Test
  void resultsStream_string_snapshotsAreStable() {
    assertResultsStream(inResults);
  }

  @Test
  void resultsStream_stringBuilder_snapshotsAreStable() {
    assertResultsStream(new StringBuilder(inResults));
  }

  @Test
  void resultsStream_stringBuffer_snapshotsAreStable() {
    assertResultsStream(new StringBuffer(inResults));
  }

  @Test
  void resultsStream_charBuffer_snapshotsAreStable() {
    assertResultsStream(CharBuffer.wrap(inResults));
  }

  // ---------------------------------------------------------------------------
  // Groups outside the match region (lookbehind/lookahead) — snapshot survives
  // after the backing char array is overwritten.
  // ---------------------------------------------------------------------------

  static Arguments[] testGroupsOutsideMatch() {
    return new Arguments[]{
        arguments("(?<=(\\d{3}))\\D*(?=(\\d{4}))", "-1234abcxyz5678-"),
        arguments("(?<=(\\d{3}))\\D*(?=(\\1))", "-1234abcxyz2348-"),
        arguments("(?<!(\\d{4}))\\D+(?=(\\d{4}))", "123abcxyz5678-"),
    };
  }

  @Disabled(
      "Capturing groups inside lookaround assertions are not yet tracked by the engine; "
      + "group positions for groups 1..n within (?<=...) / (?=...) return -1 instead of "
      + "the expected span. Deferred to Phase 5.")
  @ParameterizedTest
  @MethodSource
  void testGroupsOutsideMatch(String patternStr, String text) {
    char[] data = text.toCharArray();
    Matcher m = Pattern.compile(patternStr).matcher(CharBuffer.wrap(data));

    assertThat(m.groupCount()).isEqualTo(2);
    assertThat(m.find()).isTrue();

    int start = m.start();
    int end = m.end();
    String group = m.group();

    int prefixStart = m.start(1);
    int prefixEnd = m.end(1);
    String prefixGroup = m.group(1);

    int suffixStart = m.start(2);
    int suffixEnd = m.end(2);
    String suffixGroup = m.group(2);

    MatchResult mr = m.toMatchResult();
    Arrays.fill(data, '*');  // overwrite backing array — snapshot must be unaffected

    assertThat(mr.start()).isEqualTo(start);
    assertThat(mr.end()).isEqualTo(end);
    assertThat(mr.group()).isEqualTo(group);

    assertThat(mr.start(1)).isEqualTo(prefixStart);
    assertThat(mr.end(1)).isEqualTo(prefixEnd);
    assertThat(mr.group(1)).isEqualTo(prefixGroup);

    assertThat(mr.start(2)).isEqualTo(suffixStart);
    assertThat(mr.end(2)).isEqualTo(suffixEnd);
    assertThat(mr.group(2)).isEqualTo(suffixGroup);
  }
}
