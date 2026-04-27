package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.util.EngineHint;
import org.junit.jupiter.api.Test;

import static com.orbit.util.PatternFlag.COMMENTS;
import static com.orbit.util.PatternFlag.RE2_COMPAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link com.orbit.util.PatternFlag#RE2_COMPAT} compile-time validation and semantics.
 *
 * <p>Instances are not thread-safe (JUnit manages lifecycle).
 */
class Re2CompatTest {

  // -----------------------------------------------------------------------
  // Forbidden constructs — must throw PatternSyntaxException (wrapped)
  // -----------------------------------------------------------------------

  @Test
  void re2Compat_backreference_throws() {
    assertThatThrownBy(() -> Pattern.compile("(a)\\1", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_lookahead_throws() {
    assertThatThrownBy(() -> Pattern.compile("a(?=b)", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_negativeLookahead_throws() {
    assertThatThrownBy(() -> Pattern.compile("a(?!b)", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_lookbehind_throws() {
    assertThatThrownBy(() -> Pattern.compile("(?<=a)b", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_atomicGroup_throws() {
    assertThatThrownBy(() -> Pattern.compile("(?>a+)", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_possessiveQuantifier_throws() {
    assertThatThrownBy(() -> Pattern.compile("a++", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_classIntersection_throws() {
    assertThatThrownBy(() -> Pattern.compile("[a&&[b]]", RE2_COMPAT))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  @Test
  void re2Compat_incompatibleFlag_throws() {
    assertThatThrownBy(() -> Pattern.compile("a", RE2_COMPAT, COMMENTS))
        .hasCauseInstanceOf(PatternSyntaxException.class);
  }

  // -----------------------------------------------------------------------
  // Valid RE2 patterns — must compile and match correctly
  // -----------------------------------------------------------------------

  @Test
  void re2Compat_simplePattern_matches() {
    assertThat(Pattern.compile("\\w+", RE2_COMPAT).matcher("hello").matches()).isTrue();
  }

  @Test
  void re2Compat_dot_excludesNewlineOnly() {
    // Under RE2_COMPAT, dot excludes \n but matches \r
    assertThat(Pattern.compile(".", RE2_COMPAT).matcher("\r").matches()).isTrue();
    assertThat(Pattern.compile(".", RE2_COMPAT).matcher("\n").matches()).isFalse();
  }

  @Test
  void re2Compat_engineHint_notBBE() {
    // RE2_COMPAT patterns must never route to the backtracking engine
    Pattern p = Pattern.compile("a+b*c?", RE2_COMPAT);
    assertThat(p.engineHint()).isNotEqualTo(EngineHint.NEEDS_BACKTRACKER);
  }
}
