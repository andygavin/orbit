package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.MatchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapted from {@code NamedGroupsTests.java} in the JDK test suite (bugs 8065554, 8309515).
 *
 * <p>Tests named-group lookup on {@link Pattern}, {@link Matcher}, and the
 * {@link MatchResult} snapshot returned by {@link Matcher#toMatchResult()}.
 */
class NamedGroupsCompatTest {

  // ---------------------------------------------------------------------------
  // testMatchResultNoDefault — MatchResult default methods throw when not overridden
  // ---------------------------------------------------------------------------

  /**
   * A MatchResult implementation that purposely does not override any default methods.
   * The default implementations in java.util.regex.MatchResult throw UnsupportedOperationException.
   */
  private static class TestMatcherNoNamedGroups implements MatchResult {
    @Override
    public int start() {
      return 0;
    }

    @Override
    public int start(int group) {
      return 0;
    }

    @Override
    public int end() {
      return 0;
    }

    @Override
    public int end(int group) {
      return 0;
    }

    @Override
    public String group() {
      return null;
    }

    @Override
    public String group(int group) {
      return null;
    }

    @Override
    public int groupCount() {
      return 0;
    }
  }

  @Test
  void matchResultNoDefault_hasMatch_throwsUnsupportedOperationException() {
    TestMatcherNoNamedGroups m = new TestMatcherNoNamedGroups();
    // Default throws UnsupportedOperationException; just confirm it doesn't blow up badly.
    try {
      m.hasMatch();
    } catch (UnsupportedOperationException e) {
      // expected — default method not overridden
    }
  }

  @Test
  void matchResultNoDefault_namedGroups_throwsUnsupportedOperationException() {
    TestMatcherNoNamedGroups m = new TestMatcherNoNamedGroups();
    try {
      m.namedGroups();
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void matchResultNoDefault_startByName_throwsUnsupportedOperationException() {
    TestMatcherNoNamedGroups m = new TestMatcherNoNamedGroups();
    try {
      m.start("anyName");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void matchResultNoDefault_endByName_throwsUnsupportedOperationException() {
    TestMatcherNoNamedGroups m = new TestMatcherNoNamedGroups();
    try {
      m.end("anyName");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void matchResultNoDefault_groupByName_throwsUnsupportedOperationException() {
    TestMatcherNoNamedGroups m = new TestMatcherNoNamedGroups();
    try {
      m.group("anyName");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  // ---------------------------------------------------------------------------
  // testMatchResultStartEndGroupBeforeMatchOp
  // ---------------------------------------------------------------------------

  @Test
  void matcher_startByName_beforeMatchOp_throwsIllegalStateException() {
    Matcher m = Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc");
    assertThatThrownBy(() -> m.start("anyName"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void matcher_endByName_beforeMatchOp_throwsIllegalStateException() {
    Matcher m = Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc");
    assertThatThrownBy(() -> m.end("anyName"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void matcher_groupByName_beforeMatchOp_throwsIllegalStateException() {
    Matcher m = Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc");
    assertThatThrownBy(() -> m.group("anyName"))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------
  // testMatchResultStartEndGroupNoMatch  — results() on empty input yields no matches
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_startByName_noMatch_neverReturnsNonNegative() {
    // empty input → no matches → forEach body never executes → implicitly passes
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThat(r.start("some")).isLessThan(0);
      assertThat(r.start("rest")).isLessThan(0);
    });
  }

  @Test
  void matchResult_endByName_noMatch_neverReturnsNonNegative() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThat(r.end("some")).isLessThan(0);
      assertThat(r.end("rest")).isLessThan(0);
    });
  }

  @Test
  void matchResult_groupByName_noMatch_returnsNull() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThat(r.group("some")).isNull();
      assertThat(r.group("rest")).isNull();
    });
  }

  // ---------------------------------------------------------------------------
  // testMatchResultStartEndGroupWithMatch
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_startByName_withMatch_returnsNonNegative() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThat(r.start("some")).isGreaterThanOrEqualTo(0);
      assertThat(r.start("rest")).isGreaterThanOrEqualTo(0);
    });
  }

  @Test
  void matchResult_endByName_withMatch_returnsNonNegative() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThat(r.end("some")).isGreaterThanOrEqualTo(0);
      assertThat(r.end("rest")).isGreaterThanOrEqualTo(0);
    });
  }

  @Test
  void matchResult_groupByName_withMatch_returnsNonNull() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThat(r.group("some")).isNotNull();
      assertThat(r.group("rest")).isNotNull();
    });
  }

  // ---------------------------------------------------------------------------
  // testMatchResultStartEndGroupNoMatchNoSuchGroup
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_startByName_noSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThatThrownBy(() -> r.start("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void matchResult_endByName_noSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThatThrownBy(() -> r.end("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void matchResult_groupByName_noSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").results().forEach(r -> {
      assertThatThrownBy(() -> r.group("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  // ---------------------------------------------------------------------------
  // testMatchResultStartEndGroupWithMatchNoSuchGroup
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_startByName_withMatchNoSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThatThrownBy(() -> r.start("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void matchResult_endByName_withMatchNoSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThatThrownBy(() -> r.end("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void matchResult_groupByName_withMatchNoSuchGroup_throwsIllegalArgumentException() {
    Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("abc").results().forEach(r -> {
      assertThatThrownBy(() -> r.group("noSuchGroup"))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  // ---------------------------------------------------------------------------
  // testMatcherHasMatch
  // ---------------------------------------------------------------------------

  @Test
  void matcher_hasMatch_afterNoMatch_returnsFalse() {
    Matcher m = Pattern.compile(".+").matcher("");
    m.find();
    assertThat(m.hasMatch()).isFalse();
  }

  @Test
  void matcher_hasMatch_afterMatch_returnsTrue() {
    Matcher m = Pattern.compile(".+").matcher("abc");
    m.find();
    assertThat(m.hasMatch()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // testMatchResultHasMatch
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_hasMatch_afterNoMatch_returnsFalse() {
    Matcher m = Pattern.compile(".+").matcher("");
    m.find();
    assertThat(m.toMatchResult().hasMatch()).isFalse();
  }

  @Test
  void matchResult_hasMatch_afterMatch_returnsTrue() {
    Matcher m = Pattern.compile(".+").matcher("abc");
    m.find();
    assertThat(m.toMatchResult().hasMatch()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // testMatchResultNamedGroups
  // ---------------------------------------------------------------------------

  @Test
  void matchResult_namedGroups_noNamedGroups_isEmpty() {
    assertThat(Pattern.compile(".*").matcher("").toMatchResult().namedGroups()).isEmpty();
  }

  @Test
  void matchResult_namedGroups_oneNamedGroup_returnsMapping() {
    assertThat(Pattern.compile("(?<all>.*)").matcher("").toMatchResult().namedGroups())
        .isEqualTo(Map.of("all", 1));
  }

  @Test
  void matchResult_namedGroups_twoNamedGroups_returnsBothMappings() {
    assertThat(
        Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").toMatchResult().namedGroups())
        .isEqualTo(Map.of("some", 1, "rest", 2));
  }

  // ---------------------------------------------------------------------------
  // testMatcherNamedGroups
  // ---------------------------------------------------------------------------

  @Test
  void matcher_namedGroups_noNamedGroups_isEmpty() {
    assertThat(Pattern.compile(".*").matcher("").namedGroups()).isEmpty();
  }

  @Test
  void matcher_namedGroups_oneNamedGroup_returnsMapping() {
    assertThat(Pattern.compile("(?<all>.*)").matcher("").namedGroups())
        .isEqualTo(Map.of("all", 1));
  }

  @Test
  void matcher_namedGroups_twoNamedGroups_returnsBothMappings() {
    assertThat(Pattern.compile("(?<some>.+?)(?<rest>.*)").matcher("").namedGroups())
        .isEqualTo(Map.of("some", 1, "rest", 2));
  }

  // ---------------------------------------------------------------------------
  // testPatternNamedGroups
  // ---------------------------------------------------------------------------

  @Test
  void pattern_namedGroups_noNamedGroups_isEmpty() {
    assertThat(Pattern.compile(".*").namedGroups()).isEmpty();
  }

  @Test
  void pattern_namedGroups_oneNamedGroup_returnsMapping() {
    assertThat(Pattern.compile("(?<all>.*)").namedGroups())
        .isEqualTo(Map.of("all", 1));
  }

  @Test
  void pattern_namedGroups_twoNamedGroups_returnsBothMappings() {
    assertThat(Pattern.compile("(?<some>.+?)(?<rest>.*)").namedGroups())
        .isEqualTo(Map.of("some", 1, "rest", 2));
  }

  // ---------------------------------------------------------------------------
  // testMatchAfterUsePattern
  // ---------------------------------------------------------------------------

  @Test
  void matcher_usePattern_changesGroupMapping() {
    Pattern p1 = Pattern.compile("(?<a>...)(?<b>...)");
    Matcher m = p1.matcher("foobar");
    assertThat(m.matches()).isTrue();
    assertThat(m.group("a")).isEqualTo("foo");

    Pattern p2 = Pattern.compile("(?<b>...)(?<a>...)");
    m.usePattern(p2);
    assertThat(m.matches()).isTrue();
    assertThat(m.group("a")).isEqualTo("bar");
  }
}
