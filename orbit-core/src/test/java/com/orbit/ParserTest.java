package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.parse.Expr;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.parse.Literal;
import com.orbit.parse.CharClass;
import com.orbit.parse.CharRange;
import com.orbit.parse.Group;
import com.orbit.parse.Union;
import com.orbit.parse.Quantifier;
import com.orbit.parse.Anchor;
import com.orbit.parse.AnchorType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void testSimpleLiteral() {
        Expr expr = Parser.parse("hello");
        assertTrue(expr instanceof Literal);
        assertEquals("hello", ((Literal) expr).value());
    }

    @Test
    void testCharClass() {
        Expr expr = Parser.parse("[abc]");
        assertTrue(expr instanceof CharClass);
        CharClass cc = (CharClass) expr;
        assertEquals(3, cc.ranges().size());
        assertEquals('a', cc.ranges().get(0).lo());
        assertEquals('a', cc.ranges().get(0).hi());
        assertEquals('b', cc.ranges().get(1).lo());
        assertEquals('b', cc.ranges().get(1).hi());
        assertEquals('c', cc.ranges().get(2).lo());
        assertEquals('c', cc.ranges().get(2).hi());
    }

    @Test
    void testQuantifier() {
        Expr expr = Parser.parse("a+");
        assertTrue(expr instanceof Quantifier);
        Quantifier q = (Quantifier) expr;
        assertEquals(1, q.min());
        assertTrue(q.max().isEmpty());
        assertTrue(q.child() instanceof Literal);
    }

    @Test
    void testUnion() {
        Expr expr = Parser.parse("a|b");
        assertTrue(expr instanceof Union);
        Union u = (Union) expr;
        assertEquals(2, u.alternatives().size());
        assertTrue(u.alternatives().get(0) instanceof Literal);
        assertTrue(u.alternatives().get(1) instanceof Literal);
    }

    @Test
    void testGroup() {
        Expr expr = Parser.parse("(ab)");
        assertTrue(expr instanceof Group);
        Group g = (Group) expr;
        assertEquals(0, g.index());
        assertTrue(g.body() instanceof Expr);
    }

    @Test
    void testAnchor() {
        Expr expr = Parser.parse("^");
        assertTrue(expr instanceof Anchor);
        Anchor a = (Anchor) expr;
        assertEquals(AnchorType.START, a.type());
    }

    @Test
    void testEmptyPattern() {
        Expr expr = Parser.parse("");
        assertTrue(expr instanceof com.orbit.parse.Epsilon);
    }

    @Test
    void testInvalidPatterns() {
        assertThrows(PatternSyntaxException.class, () -> Parser.parse("*a"));
        assertThrows(PatternSyntaxException.class, () -> Parser.parse("(unclosed"));
        assertThrows(PatternSyntaxException.class, () -> Parser.parse("[unclosed"));
    }

    @Test
    void testComplexPattern() {
        Expr expr = Parser.parse("a(b|c)+d*");
        assertNotNull(expr);
    }

    @Test
    void testBackref() {
        Expr expr = Parser.parse("\\1");
        assertTrue(expr instanceof com.orbit.parse.Backref);
        assertEquals(1, ((com.orbit.parse.Backref) expr).groupIndex());
    }

    @Test
    void testDot() {
        // Default dot (no flags) excludes all Unicode line terminators per JDK semantics:
        // \n (0x0A), \r (0x0D), NEL (0x85), LS (0x2028), PS (0x2029).
        // This produces 5 ranges covering everything except those code points.
        Expr expr = Parser.parse(".");
        assertTrue(expr instanceof CharClass);
        CharClass cc = (CharClass) expr;
        assertEquals(5, cc.ranges().size());
        assertEquals('\u0000', cc.ranges().get(0).lo());
        assertEquals('\u0009', cc.ranges().get(0).hi());
        assertEquals('\u000B', cc.ranges().get(1).lo());
        assertEquals('\u000C', cc.ranges().get(1).hi());
        assertEquals('\u000E', cc.ranges().get(2).lo());
        assertEquals('\u0084', cc.ranges().get(2).hi());
        assertEquals('\u0086', cc.ranges().get(3).lo());
        assertEquals('\u2027', cc.ranges().get(3).hi());
        assertEquals('\u202A', cc.ranges().get(4).lo());
        assertEquals('\uffff', cc.ranges().get(4).hi());
    }

    @Test
    void testNestedGroups() {
        Expr expr = Parser.parse("(a(b)c)");
        assertTrue(expr instanceof Group);
        Group outer = (Group) expr;
        assertTrue(outer.body() instanceof com.orbit.parse.Concat);
    }

    // -----------------------------------------------------------------------
    // Repetition count overflow (A3)
    // -----------------------------------------------------------------------

    @Test
    void repetitionCount_maxIntValue_doesNotThrow() {
        // Integer.MAX_VALUE == 2147483647; must be accepted without error.
        assertDoesNotThrow(() -> Pattern.compile("a{2147483647}"));
    }

    @Test
    void repetitionCount_overflowBeyondMaxInt_throwsPatternSyntaxException() {
        // 2147483648 exceeds Integer.MAX_VALUE; must be rejected.
        // Pattern.compile wraps PatternSyntaxException in RuntimeException.
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> Pattern.compile("a{2147483648}"));
        assertTrue(ex instanceof PatternSyntaxException || ex.getCause() instanceof PatternSyntaxException,
                "expected PatternSyntaxException as the exception or its cause");
    }

    // -----------------------------------------------------------------------
    // Group name validation (A4)
    // -----------------------------------------------------------------------

    @Test
    void groupName_leadingUnderscore_throwsPatternSyntaxException() {
        // JDK requires group names to start with an ASCII letter; underscore is not allowed.
        // Pattern.compile wraps PatternSyntaxException in RuntimeException.
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> Pattern.compile("(?<_name>x)"));
        assertTrue(ex instanceof PatternSyntaxException || ex.getCause() instanceof PatternSyntaxException,
                "expected PatternSyntaxException as the exception or its cause");
    }

    @Test
    void groupName_letterAndDigits_doesNotThrow() {
        // name1 is a valid group name: starts with a letter, body is letter + digit.
        assertDoesNotThrow(() -> Pattern.compile("(?<name1>x)"));
    }

    // -----------------------------------------------------------------------
    // B3a — POSIX Unicode semantics verification
    // -----------------------------------------------------------------------

    /** B3a: {@code \p{Alpha}} must match U+00E9 (é), which is a Unicode letter. */
    @Test
    void posixAlpha_latinSmallLetterEWithAcute_matches() {
        Pattern p = Pattern.compile("\\p{Alpha}");
        assertTrue(p.matcher("\u00E9").find(),
                "\\p{Alpha} should match U+00E9 (é) under Unicode semantics");
    }

    /** B3a: {@code \p{Digit}} must match U+0661 (Arabic-Indic digit one). */
    @Test
    void posixDigit_arabicIndicDigitOne_matches() {
        Pattern p = Pattern.compile("\\p{Digit}");
        assertTrue(p.matcher("\u0661").find(),
                "\\p{Digit} should match U+0661 (Arabic-Indic digit one) under Unicode semantics");
    }

    // -----------------------------------------------------------------------
    // B3b — \p{ASCII} bounds verification
    // -----------------------------------------------------------------------

    /** B3b: {@code \p{ASCII}} must match U+007F (DEL, the highest ASCII code point). */
    @Test
    void posixAscii_del_matches() {
        Pattern p = Pattern.compile("\\p{ASCII}");
        assertTrue(p.matcher("\u007F").find(),
                "\\p{ASCII} should match U+007F (DEL)");
    }

    /** B3b: {@code \p{ASCII}} must not match U+0080 (the first non-ASCII code point). */
    @Test
    void posixAscii_firstNonAscii_doesNotMatch() {
        Pattern p = Pattern.compile("\\p{ASCII}");
        assertFalse(p.matcher("\u0080").find(),
                "\\p{ASCII} should not match U+0080");
    }
}