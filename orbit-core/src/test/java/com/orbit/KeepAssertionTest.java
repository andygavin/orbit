package com.orbit;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code \K} keep assertion — §6.7.3 F2.
 *
 * <p>{@code \K} resets the reported match start to the current input position. Input
 * consumed before {@code \K} anchors the match but is excluded from {@code group(0)},
 * {@code start()}, and {@code end()}.
 */
class KeepAssertionTest {

  @Test
  void keepAssertion_basic_resetsMatchStart() {
    Matcher m = Pattern.compile("foo\\Kbar").matcher("foobar");
    assertTrue(m.find());
    assertEquals("bar", m.group(0));
    assertEquals(3, m.start());
    assertEquals(6, m.end());
  }

  @Test
  void baseline_noKeepAssertion_startsAtZero() {
    Matcher m = Pattern.compile("foobar").matcher("foobar");
    assertTrue(m.find());
    assertEquals(0, m.start());
  }

  @Test
  void keepAssertion_insideRepetition_lastKWins() {
    // \K inside a + loop: last executed \K before Accept wins.
    Matcher m = Pattern.compile("(foo\\Kbar)+").matcher("foobarfoobar");
    assertTrue(m.find());
    assertEquals("bar", m.group(0));
    assertEquals(9, m.start());
    assertEquals(12, m.end());
  }

  @Test
  void keepAssertion_multipleK_lastOneWins() {
    // a\Kb\Kc — only the second \K (before c) determines the reported start.
    Matcher m = Pattern.compile("a\\Kb\\Kc").matcher("abc");
    assertTrue(m.find());
    assertEquals("c", m.group(0));
    assertEquals(2, m.start());
    assertEquals(3, m.end());
  }

  @Test
  void keepAssertion_afterLookahead_reportsFromK() {
    // (?=foo)\Kfoo — lookahead asserts foo is ahead, \K resets start, foo is consumed.
    Matcher m = Pattern.compile("(?=foo)\\Kfoo").matcher("foobar");
    assertTrue(m.find());
    assertEquals("foo", m.group(0));
    assertEquals(0, m.start());
    assertEquals(3, m.end());
  }

  @Test
  void keepAssertion_insideCharClass_throwsPatternSyntaxException() {
    // \K inside a character class is illegal.
    assertThrows(RuntimeException.class, () -> Pattern.compile("[\\K]"),
        "\\K inside character class should throw PatternSyntaxException");
  }

  @Test
  void keepAssertion_insideCharClassWithOtherChars_throwsPatternSyntaxException() {
    // [a\Kb] — \K is still illegal inside a character class even with surrounding chars.
    assertThrows(RuntimeException.class, () -> Pattern.compile("[a\\Kb]"),
        "\\K inside character class should throw PatternSyntaxException");
  }
}
