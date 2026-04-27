package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PossDebugTest {
  @Test
  void debugBoundedPossessive() {
    Pattern p = Pattern.compile("a{1,5}+a");
    String subj = "aaaaa";
    System.out.println("hint: " + p.prog().metadata.hint());
    System.out.println("Instructions:");
    for (int i = 0; i < p.prog().getInstructionCount(); i++) {
      System.out.println("  [" + i + "] " + p.prog().getInstruction(i));
    }
    Matcher m = p.matcher(subj);
    boolean found = m.find();
    System.out.println("find() = " + found);
    if (found) {
      System.out.println("  match: " + m.start() + "-" + m.end()
          + " '" + subj.substring(m.start(), m.end()) + "'");
    }
    assertFalse(found, "possessive a{1,5}+a should not match aaaaa");
  }
}
