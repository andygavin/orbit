package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import com.orbit.util.PatternFlag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZTest798 {
    @Test void line726_bare() {
        // Line 726: a\z on b\na (no MULTILINE)
        Pattern p = Pattern.compile("a\\z");
        System.out.println("726 hint=" + p.prog().metadata.hint());
        assertTrue(p.matcher("b\na").find());
    }
    @Test void line735_multiline() {
        // Line 735: 'a\z'm on b\na (MULTILINE)
        Pattern p = Pattern.compile("a\\z", PatternFlag.MULTILINE);
        System.out.println("735 hint=" + p.prog().metadata.hint());
        assertTrue(p.matcher("b\na").find());
    }
    @Test void line798_bare() {
        // Line 798: ab\z on b\nab (no MULTILINE)
        Pattern p = Pattern.compile("ab\\z");
        System.out.println("798 hint=" + p.prog().metadata.hint());
        assertTrue(p.matcher("b\nab").find());
    }
    @Test void line807_multiline() {
        // Line 807: 'ab\z'm on b\nab (MULTILINE)
        Pattern p = Pattern.compile("ab\\z", PatternFlag.MULTILINE);
        System.out.println("807 hint=" + p.prog().metadata.hint());
        assertTrue(p.matcher("b\nab").find());
    }
}
