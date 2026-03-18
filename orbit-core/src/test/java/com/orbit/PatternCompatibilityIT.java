package com.orbital;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import static org.junit.jupiter.api.Assertions.*;

class PatternCompatibilityIT {

    @Test
    void testBasicMatching() {
        Pattern orbitPattern = Pattern.compile("hello");
        Matcher orbitMatcher = orbitPattern.matcher("hello world");
        assertTrue(orbitMatcher.find());
        assertEquals("hello", orbitMatcher.group());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("hello");
        Matcher javaMatcher = javaPattern.matcher("hello world");
        assertTrue(javaMatcher.find());
        assertEquals("hello", javaMatcher.group());
    }

    @Test
    void testGroups() {
        Pattern orbitPattern = Pattern.compile("(he)(llo)");
        Matcher orbitMatcher = orbitPattern.matcher("hello");
        assertTrue(orbitMatcher.matches());
        assertEquals("hello", orbitMatcher.group());
        assertEquals("he", orbitMatcher.group(1));
        assertEquals("llo", orbitMatcher.group(2));

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("(he)(llo)");
        Matcher javaMatcher = javaPattern.matcher("hello");
        assertTrue(javaMatcher.matches());
        assertEquals("hello", javaMatcher.group());
        assertEquals("he", javaMatcher.group(1));
        assertEquals("llo", javaMatcher.group(2));
    }

    @Test
    void testReplaceAll() {
        Pattern orbitPattern = Pattern.compile("world");
        String orbitResult = orbitPattern.matcher("hello world").replaceAll("everyone");
        assertEquals("hello everyone", orbitResult);

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("world");
        String javaResult = javaPattern.matcher("hello world").replaceAll("everyone");
        assertEquals("hello everyone", javaResult);
    }

    @Test
    void testSplit() {
        Pattern orbitPattern = Pattern.compile("\\s+");
        String[] orbitResult = orbitPattern.split("hello   world");
        assertEquals(2, orbitResult.length);
        assertEquals("hello", orbitResult[0]);
        assertEquals("world", orbitResult[1]);

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("\\s+");
        String[] javaResult = javaPattern.split("hello   world");
        assertEquals(2, javaResult.length);
        assertEquals("hello", javaResult[0]);
        assertEquals("world", javaResult[1]);
    }

    @Test
    void testAnchors() {
        Pattern orbitPattern = Pattern.compile("^hello$");
        Matcher orbitMatcher = orbitPattern.matcher("hello");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("^hello$");
        Matcher javaMatcher = javaPattern.matcher("hello");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testQuantifiers() {
        Pattern orbitPattern = Pattern.compile("a+b");
        Matcher orbitMatcher = orbitPattern.matcher("aaab");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("a+b");
        Matcher javaMatcher = javaPattern.matcher("aaab");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testCharClasses() {
        Pattern orbitPattern = Pattern.compile("[abc]");
        Matcher orbitMatcher = orbitPattern.matcher("a");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("[abc]");
        Matcher javaMatcher = javaPattern.matcher("a");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testDot() {
        Pattern orbitPattern = Pattern.compile(".");
        Matcher orbitMatcher = orbitPattern.matcher("a");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile(".");
        Matcher javaMatcher = javaPattern.matcher("a");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testAlternation() {
        Pattern orbitPattern = Pattern.compile("a|b");
        Matcher orbitMatcher = orbitPattern.matcher("a");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("a|b");
        Matcher javaMatcher = javaPattern.matcher("a");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testComplexPattern() {
        Pattern orbitPattern = Pattern.compile("(\d{3})-(\d{3})-(\d{4})");
        Matcher orbitMatcher = orbitPattern.matcher("123-456-7890");
        assertTrue(orbitMatcher.matches());
        assertEquals("123-456-7890", orbitMatcher.group());
        assertEquals("123", orbitMatcher.group(1));
        assertEquals("456", orbitMatcher.group(2));
        assertEquals("7890", orbitMatcher.group(3));

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("(\d{3})-(\d{3})-(\d{4})");
        Matcher javaMatcher = javaPattern.matcher("123-456-7890");
        assertTrue(javaMatcher.matches());
        assertEquals("123-456-7890", javaMatcher.group());
        assertEquals("123", javaMatcher.group(1));
        assertEquals("456", javaMatcher.group(2));
        assertEquals("7890", javaMatcher.group(3));
    }

    @Test
    void testUnicode() {
        Pattern orbitPattern = Pattern.compile("[\u00e9\u00e8]");
        Matcher orbitMatcher = orbitPattern.matcher("\u00e9");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("[\u00e9\u00e8]");
        Matcher javaMatcher = javaPattern.matcher("\u00e9");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testFlags() {
        Pattern orbitPattern = Pattern.compile("hello", PatternFlag.CASE_INSENSITIVE);
        Matcher orbitMatcher = orbitPattern.matcher("HELLO");
        assertTrue(orbitMatcher.matches());

        java.util.regex.Pattern javaPattern = java.util.regex.Pattern.compile("hello", java.util.regex.Pattern.CASE_INSENSITIVE);
        Matcher javaMatcher = javaPattern.matcher("HELLO");
        assertTrue(javaMatcher.matches());
    }

    @Test
    void testStaticMethods() {
        assertTrue(Pattern.matches("hello", "hello"));
        assertFalse(Pattern.matches("hello", "world"));

        String[] split = Pattern.split("\\s+", "hello world");
        assertEquals(2, split.length);

        String quoted = Pattern.quote("hello.world");
        assertEquals("hello\\.world", quoted);
    }
}