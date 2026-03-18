package com.orbital;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import com.orbital.util.PatternFlag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatternTest {

    @Test
    void testCompileSimplePattern() {
        Pattern pattern = Pattern.compile("hello");
        assertNotNull(pattern);
        assertEquals("hello", pattern.pattern());
    }

    @Test
    void testCompileWithFlags() {
        Pattern pattern = Pattern.compile("hello", PatternFlag.CASE_INSENSITIVE);
        assertNotNull(pattern);
    }

    @Test
    void testCompileInvalidPattern() {
        assertThrows(RuntimeException.class, () -> Pattern.compile("unclosed("));
    }

    @Test
    void testMatcher() {
        Pattern pattern = Pattern.compile("hello");
        Matcher matcher = pattern.matcher("hello world");
        assertNotNull(matcher);
    }

    @Test
    void testEngineHint() {
        Pattern pattern = Pattern.compile("hello");
        assertEquals(com.orbital.util.EngineHint.DFA_SAFE, pattern.engineHint());
    }

    @Test
    void testIsOnePassSafe() {
        Pattern pattern = Pattern.compile("hello");
        assertTrue(pattern.isOnePassSafe());
    }

    @Test
    void testCache() {
        Pattern p1 = Pattern.compile("test");
        Pattern p2 = Pattern.compile("test");
        assertSame(p1.prog(), p2.prog());
    }

    @Test
    void testFlags() {
        Pattern pattern = Pattern.compile("hello", PatternFlag.CASE_INSENSITIVE, PatternFlag.MULTILINE);
        PatternFlag[] flags = pattern.flags();
        assertEquals(2, flags.length);
        assertTrue(Arrays.asList(flags).contains(PatternFlag.CASE_INSENSITIVE));
        assertTrue(Arrays.asList(flags).contains(PatternFlag.MULTILINE));
    }

    @Test
    void testStaticMethods() {
        assertTrue(Pattern.matches("hello", "hello"));
        assertFalse(Pattern.matches("hello", "world"));

        String quoted = Pattern.quote("hello.world");
        assertEquals("hello\\.world", quoted);

        String[] split = Pattern.split("\\s+", "hello world");
        assertEquals(2, split.length);
        assertEquals("hello", split[0]);
        assertEquals("world", split[1]);
    }

    @Test
    void testNullInput() {
        assertThrows(NullPointerException.class, () -> Pattern.compile(null));
        assertThrows(NullPointerException.class, () -> Pattern.compile("test", (PatternFlag[]) null));
    }

    @Test
    void testEmptyPattern() {
        Pattern pattern = Pattern.compile("");
        assertNotNull(pattern);
        assertEquals("", pattern.pattern());

        Matcher matcher = pattern.matcher("test");
        assertTrue(matcher.matches());
    }
}