package com.orbital;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatcherTest {

    @Test
    void testMatches() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello");
        assertTrue(matcher.matches());
    }

    @Test
    void testMatchesFails() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("world");
        assertFalse(matcher.matches());
    }

    @Test
    void testFind() {
        Pattern pattern = Pattern.compile("world");
        Matcher matcher = pattern.matcher("hello world");
        assertTrue(matcher.find());
        assertEquals(6, matcher.start());
        assertEquals(11, matcher.end());
    }

    @Test
    void testFindMultiple() {
        Pattern pattern = Pattern.compile("o");
        Matcher matcher = pattern.matcher("hello");
        assertTrue(matcher.find());
        assertEquals(4, matcher.start());
        assertTrue(matcher.find());
        assertEquals(7, matcher.start());
    }

    @Test
    void testGroup() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello");
        assertTrue(matcher.matches());
        assertEquals("hello", matcher.group());
    }

    @Test
    void testReplaceAll() {
        Pattern pattern = Pattern.compile("world");
        Matcher matcher = pattern.matcher("hello world");
        assertEquals("hello everyone", matcher.replaceAll("everyone"));
    }

    @Test
    void testStartEnd() {
        Pattern pattern = Pattern.compile("world");
        Matcher matcher = pattern.matcher("hello world");
        assertTrue(matcher.find());
        assertEquals(6, matcher.start());
        assertEquals(11, matcher.end());
    }

    @Test
    void testGroupCount() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello");
        assertEquals(0, matcher.groupCount());
    }

    @Test
    void testIllegalState() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello");
        assertThrows(IllegalStateException.class, matcher::start);
    }

    @Test
    void testNullInput() {
        Pattern pattern = Pattern.compile("test");
        assertThrows(NullPointerException.class, () -> pattern.matcher(null));
    }

    @Test
    void testEmptyPattern() {
        Pattern pattern = Pattern.compile("");
        Matcher matcher = pattern.matcher("test");
        assertTrue(matcher.matches());
    }

    @Test
    void testReset() {
        Pattern pattern = Pattern.compile("world");
        Matcher matcher = pattern.matcher("hello world world");
        assertTrue(matcher.find());
        matcher.reset();
        assertTrue(matcher.find());
        assertEquals(6, matcher.start());
    }

    @Test
    void testGroupWithGroups() {
        Pattern pattern = Pattern.compile("(he)(llo)");
        Matcher matcher = pattern.matcher("hello");
        assertTrue(matcher.matches());
        assertEquals("hello", matcher.group());
        assertEquals("he", matcher.group(1));
        assertEquals("llo", matcher.group(2));
        assertEquals(2, matcher.groupCount());
    }

    @Test
    void testGroupOutOfRange() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello");
        assertTrue(matcher.matches());
        assertThrows(IndexOutOfBoundsException.class, () -> matcher.group(1));
    }

    @Test
    void testMatchesWithGroups() {
        Pattern pattern = Pattern.compile("(test)");
        Matcher matcher = pattern.matcher("test");
        assertTrue(matcher.matches());
        assertEquals("test", matcher.group());
        assertEquals("test", matcher.group(1));
        assertEquals(1, matcher.groupCount());
    }

    @Test
    void testFindWithGroups() {
        Pattern pattern = Pattern.compile("(test)");
        Matcher matcher = pattern.matcher("test test");
        assertTrue(matcher.find());
        assertEquals("test", matcher.group());
        assertEquals("test", matcher.group(1));
        assertEquals(1, matcher.groupCount());
    }
}