package com.orbital;

import com.orbital.parse.Expr;
import com.orbital.parse.Parser;
import com.orbital.parse.PatternSyntaxException;
import com.orbital.parse.Literal;
import com.orbital.parse.CharClass;
import com.orbital.parse.CharRange;
import com.orbital.parse.Group;
import com.orbital.parse.Union;
import com.orbital.parse.Quantifier;
import com.orbital.parse.Anchor;
import com.orbital.parse.AnchorType;
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
        Expr expr = Parser.parse("^$");
        assertTrue(expr instanceof Anchor);
        Anchor a = (Anchor) expr;
        assertEquals(AnchorType.START, a.type());
    }

    @Test
    void testEmptyPattern() {
        Expr expr = Parser.parse("");
        assertTrue(expr instanceof com.orbital.parse.Epsilon);
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
        Expr expr = Parser.parse("a\\1");
        assertTrue(expr instanceof com.orbital.parse.Backref);
        assertEquals(1, ((com.orbital.parse.Backref) expr).groupIndex());
    }

    @Test
    void testDot() {
        Expr expr = Parser.parse(".");
        assertTrue(expr instanceof CharClass);
        CharClass cc = (CharClass) expr;
        assertEquals(1, cc.ranges().size());
        assertEquals('\u0000', cc.ranges().get(0).lo());
        assertEquals('\uffff', cc.ranges().get(0).hi());
    }

    @Test
    void testNestedGroups() {
        Expr expr = Parser.parse("(a(b)c)");
        assertTrue(expr instanceof Group);
        Group outer = (Group) expr;
        assertTrue(outer.body() instanceof com.orbital.parse.Concat);
    }
}