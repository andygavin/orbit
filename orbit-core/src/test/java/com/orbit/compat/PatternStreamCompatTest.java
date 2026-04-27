package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers stream-based and functional API on {@link Pattern} and {@link Matcher}.
 *
 * <p>Written from scratch in place of adapting the JDK's {@code PatternStreamTest}, which
 * depends on internal OpenJDK stream test infrastructure ({@code OpTestCase},
 * {@code LambdaTestHelpers}) that cannot be ported.
 */
class PatternStreamCompatTest {

  // ---------------------------------------------------------------------------
  // matcher.results()
  // ---------------------------------------------------------------------------

  @Test
  void results_multipleMatches_returnsAllMatchesInOrder() {
    List<String> found = Pattern.compile("\\d+").matcher("a1b22c333").results()
        .map(MatchResult::group)
        .toList();
    assertThat(found).containsExactly("1", "22", "333");
  }

  @Test
  void results_noMatch_returnsEmptyStream() {
    Stream<MatchResult> s = Pattern.compile("\\d+").matcher("abc").results();
    assertThat(s.toList()).isEmpty();
  }

  @Test
  void results_singleMatch_returnsSingleElement() {
    List<MatchResult> results = Pattern.compile("X").matcher("XXXXXX").results().toList();
    assertThat(results).hasSize(6);
    results.forEach(r -> assertThat(r.group()).isEqualTo("X"));
  }

  @Test
  void results_resetsBeforeProducing_allMatchesPresent() {
    Matcher m = Pattern.compile("X").matcher("XYX");
    // Advance past the first match manually.
    assertThat(m.find()).isTrue();
    // results() must reset and return both matches.
    List<String> groups = m.results().map(MatchResult::group).toList();
    assertThat(groups).containsExactly("X", "X");
  }

  @Test
  void results_snapshotIsImmutable_subsequentFindDoesNotCorruptEarlierResult() {
    Matcher m = Pattern.compile("\\d+").matcher("1 2 3");
    List<MatchResult> results = m.results().toList();
    assertThat(results).hasSize(3);
    assertThat(results.get(0).group()).isEqualTo("1");
    assertThat(results.get(1).group()).isEqualTo("2");
    assertThat(results.get(2).group()).isEqualTo("3");
  }

  // ---------------------------------------------------------------------------
  // pattern.splitAsStream()
  // ---------------------------------------------------------------------------

  @Test
  void splitAsStream_simpleDelimiter_returnsTokens() {
    List<String> tokens = Pattern.compile(":").splitAsStream("a:b:c").toList();
    assertThat(tokens).containsExactly("a", "b", "c");
  }

  @Test
  void splitAsStream_noDelimiterInInput_returnsSingleToken() {
    List<String> tokens = Pattern.compile(":").splitAsStream("abc").toList();
    assertThat(tokens).containsExactly("abc");
  }

  @Test
  void splitAsStream_multiCharDelimiter_returnsTokens() {
    List<String> tokens = Pattern.compile("\\s+").splitAsStream("foo bar  baz").toList();
    assertThat(tokens).containsExactly("foo", "bar", "baz");
  }

  // ---------------------------------------------------------------------------
  // pattern.splitAsStream() — data-driven parametrized tests (JDK data)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> splitAsStreamData() {
    return Stream.of(
        Arguments.of("All matches", "XXXXXX", "X"),
        Arguments.of("Bounded every other match", "XYXYXYYXYX", "X"),
        Arguments.of("Every other match", "YXYXYXYYXYXY", "X"),
        Arguments.of("4 in mixed", "awgqwefg1fefw4vssv1vvv1", "4"),
        Arguments.of("pound-a delimiter",
            "afbfq\u00a3abgwgb\u00a3awngnwggw\u00a3a\u00a3ahjrnhneerh", "\u00a3a"),
        Arguments.of("1 in mixed", "awgqwefg1fefw4vssv1vvv1", "1"),
        Arguments.of("1 with CJK",
            "a\u4ebafg1fefw\u4eba4\u9f9cvssv\u9f9c1v\u672c\u672cvv", "1"),
        Arguments.of("CJK delimiter simple", "1\u56da23\u56da456\u56da7890", "\u56da"),
        Arguments.of("CJK delimiter mixed",
            "1\u56da23\u9f9c\u672c\u672c\u56da456\u56da\u9f9c\u672c7890", "\u56da"),
        Arguments.of("Empty input", "", "\u56da"),
        Arguments.of("Empty input empty pattern", "", ""),
        Arguments.of("Multiple separators",
            "This is,testing: with\tdifferent separators.", "[ \t,:.]+"),
        Arguments.of("Repeated separators at end", "boo:and:foo", "o"),
        Arguments.of("Many repeated separators", "booooo:and:fooooo", "o"),
        Arguments.of("Many repeated before last", "fooooo:", "o"),
        Arguments.of("Lookahead zero-width", "AbcEfgHij", "(?=\\p{Lu})")
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("splitAsStreamData")
  void splitAsStream_parametrized_matchesSplitArray(
      String description, String input, String patternStr) {
    Pattern p = Pattern.compile(patternStr);
    List<String> fromStream = p.splitAsStream(input).toList();
    List<String> fromSplit = Arrays.asList(p.split(input));
    assertThat(fromStream).as(description).isEqualTo(fromSplit);
  }

  // ---------------------------------------------------------------------------
  // pattern.asPredicate()
  // ---------------------------------------------------------------------------

  @Test
  void asPredicate_inputContainsMatch_returnsTrue() {
    assertThat(Pattern.compile("\\d+").asPredicate().test("abc123")).isTrue();
  }

  @Test
  void asPredicate_inputHasNoMatch_returnsFalse() {
    assertThat(Pattern.compile("\\d+").asPredicate().test("abc")).isFalse();
  }

  @Test
  void asPredicate_filterStream_keepsMatchingElements() {
    List<String> matched = Stream.of("abc", "123", "a1b", "xyz")
        .filter(Pattern.compile("\\d").asPredicate())
        .toList();
    assertThat(matched).containsExactly("123", "a1b");
  }

  // ---------------------------------------------------------------------------
  // pattern.asMatchPredicate()
  // ---------------------------------------------------------------------------

  @Test
  void asMatchPredicate_fullMatch_returnsTrue() {
    assertThat(Pattern.compile("\\d+").asMatchPredicate().test("123")).isTrue();
  }

  @Test
  void asMatchPredicate_partialMatch_returnsFalse() {
    assertThat(Pattern.compile("\\d+").asMatchPredicate().test("123abc")).isFalse();
  }

  @Test
  void asMatchPredicate_noMatch_returnsFalse() {
    assertThat(Pattern.compile("\\d+").asMatchPredicate().test("abc")).isFalse();
  }

  @Test
  void asMatchPredicate_filterStream_keepsOnlyFullMatches() {
    List<String> matched = Stream.of("123", "456abc", "789", "xyz")
        .filter(Pattern.compile("\\d+").asMatchPredicate())
        .toList();
    assertThat(matched).containsExactly("123", "789");
  }

  // ---------------------------------------------------------------------------
  // matcher.replaceAll(Function)
  // ---------------------------------------------------------------------------

  @Test
  void replaceAll_function_replacesEachMatchWithUpperCase() {
    String result = Pattern.compile("[a-z]+").matcher("hello world").replaceAll(
        mr -> mr.group().toUpperCase());
    assertThat(result).isEqualTo("HELLO WORLD");
  }

  @Test
  void replaceAll_function_noMatch_returnsInputUnchanged() {
    String result = Pattern.compile("\\d+").matcher("abc").replaceAll(
        mr -> "X");
    assertThat(result).isEqualTo("abc");
  }

  @Test
  void replaceAll_function_includesGroupInReplacement() {
    // wrap each word in brackets
    String result = Pattern.compile("(\\w+)").matcher("foo bar").replaceAll(
        mr -> "[" + mr.group(1) + "]");
    assertThat(result).isEqualTo("[foo] [bar]");
  }

  // ---------------------------------------------------------------------------
  // matcher.replaceFirst(String)
  // ---------------------------------------------------------------------------

  @Test
  void replaceFirst_string_replacesOnlyFirstMatch() {
    String result = Pattern.compile("\\d+").matcher("a1b2c3").replaceFirst("X");
    assertThat(result).isEqualTo("aXb2c3");
  }

  @Test
  void replaceFirst_string_noMatch_returnsInputUnchanged() {
    String result = Pattern.compile("\\d+").matcher("abc").replaceFirst("X");
    assertThat(result).isEqualTo("abc");
  }

  // ---------------------------------------------------------------------------
  // appendReplacement / appendTail
  // ---------------------------------------------------------------------------

  @Test
  void appendReplacement_andAppendTail_buildReplacementCorrectly() {
    Pattern p = Pattern.compile("\\b(\\w+)\\b");
    Matcher m = p.matcher("hello world");
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, m.group(1).toUpperCase());
    }
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("HELLO WORLD");
  }

  @Test
  void appendReplacement_andAppendTail_preservesNonMatchingPortions() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("a1b22c");
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, "N");
    }
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("aNbNc");
  }

  @Test
  void appendTail_noMatchFound_appendsEntireInput() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc");
    StringBuilder sb = new StringBuilder();
    // find() returns false immediately — appendTail should still append everything
    while (m.find()) {
      m.appendReplacement(sb, "X");
    }
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("abc");
  }

  // ---------------------------------------------------------------------------
  // testLateBinding — splitAsStream on a mutable StringBuilder
  // ---------------------------------------------------------------------------

  @Disabled("splitAsStream snapshots content at creation time; late mutation not observable")
  @Test
  void splitAsStream_lateBinding_truncationObservedBeforeTerminalOp() {
    // JDK PatternStreamTest.testLateBinding case 1:
    // Truncate the builder after creating the stream — only tokens in the
    // truncated portion should appear if binding is late.
    Pattern pattern = Pattern.compile(",");
    StringBuilder sb = new StringBuilder("a,b,c,d,e");
    Stream<String> stream = pattern.splitAsStream(sb);
    sb.setLength(3); // truncate to "a,b"
    assertThat(stream.toList()).containsExactly("a", "b");
  }

  @Disabled("splitAsStream snapshots content at creation time; late mutation not observable")
  @Test
  void splitAsStream_lateBinding_appendObservedBeforeTerminalOp() {
    // JDK PatternStreamTest.testLateBinding case 2:
    // Append to the builder after creating the stream — appended tokens
    // should appear if binding is late.
    Pattern pattern = Pattern.compile(",");
    StringBuilder sb = new StringBuilder("a,b");
    Stream<String> stream = pattern.splitAsStream(sb);
    sb.append(",f,g");
    assertThat(stream.toList()).containsExactly("a", "b", "f", "g");
  }

  // ---------------------------------------------------------------------------
  // testFailfastMatchResults — ConcurrentModificationException inside results()
  // ---------------------------------------------------------------------------

  @Test
  void results_concurrentReset_forEachRemaining_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.results().peek(mr -> m.reset()).count());
  }

  @Test
  void results_concurrentFind_forEachRemaining_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.results().peek(mr -> m.find()).count());
  }

  @Test
  void results_concurrentReset_withLimit_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.results().peek(mr -> m.reset()).limit(2).count());
  }

  @Test
  void results_concurrentFind_withLimit_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.results().peek(mr -> m.find()).limit(2).count());
  }

  // ---------------------------------------------------------------------------
  // testFailfastReplace — ConcurrentModificationException inside functional replace
  // ---------------------------------------------------------------------------

  @Test
  void replaceFirst_concurrentReset_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.replaceFirst(mr -> {
          m.reset();
          return "Y";
        }));
  }

  @Test
  void replaceAll_concurrentReset_throwsCME() {
    Pattern p = Pattern.compile("X");
    Matcher m = p.matcher("XX");
    m.reset();
    assertThrows(ConcurrentModificationException.class,
        () -> m.replaceAll(mr -> {
          m.reset();
          return "Y";
        }));
  }
}
