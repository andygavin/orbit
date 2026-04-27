package com.orbit;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code (?|...)} branch reset group — §6.7.3 F1.
 *
 * <p>Branch reset groups share group-counter slots across alternatives: the first group in
 * each alternative occupies the same slot, the second group occupies the same slot, etc.
 */
class BranchResetTest {

  @Test
  void branchReset_singleGroup_capturesSameSlot() {
    Matcher m = Pattern.compile("(?|(a)|(b))").matcher("a");
    assertTrue(m.find());
    assertEquals("a", m.group(1));
  }

  @Test
  void branchReset_singleGroup_capturesSameSlot_secondAlt() {
    Matcher m = Pattern.compile("(?|(a)|(b))").matcher("b");
    assertTrue(m.find());
    assertEquals("b", m.group(1));
  }

  @Test
  void branchReset_multiGroup_sameCount() {
    Matcher m = Pattern.compile("(?|(a)(b)|(c)(d))").matcher("cd");
    assertTrue(m.find());
    assertEquals("c", m.group(1));
    assertEquals("d", m.group(2));
  }

  @Test
  void branchReset_unequalGroups_shortAltUndef() {
    Matcher m = Pattern.compile("(?|(a)(b)|(c))x").matcher("cx");
    assertTrue(m.find());
    assertEquals("c", m.group(1));
    assertNull(m.group(2));
  }

  @Test
  void branchReset_nested() {
    Matcher m = Pattern.compile("(?|(?|(a)|(b))|(?|(c)|(d)))").matcher("c");
    assertTrue(m.find());
    assertEquals("c", m.group(1));
  }

  @Test
  void branchReset_afterCounterContinues() {
    Matcher m = Pattern.compile("(?|(a)|(b))(c)").matcher("ac");
    assertTrue(m.find());
    assertEquals("a", m.group(1));
    assertEquals("c", m.group(2));
  }

  @Test
  void branchReset_noGroupEffect_plainMatch() {
    Matcher m = Pattern.compile("(?|ab|cd)").matcher("ab");
    assertTrue(m.find());
    assertEquals("ab", m.group(0));
  }

  @Test
  void branchReset_emptyIsNoop() {
    Matcher m = Pattern.compile("(?|)").matcher("");
    assertTrue(m.find());
    assertEquals("", m.group(0));
  }
}
