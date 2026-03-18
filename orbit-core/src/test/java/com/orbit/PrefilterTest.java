package com.orbital.prefilter;

import com.orbital.prefilter.NoopPrefilter;
import com.orbital.prefilter.VectorLiteralPrefilter;
import com.orbital.prefilter.AhoCorasickPrefilter;
import com.orbital.prefilter.LiteralIndexOfPrefilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.*;

class PrefilterTest {

    @Test
    void testNoopPrefilter() {
        Prefilter prefilter = NoopPrefilter.INSTANCE;

        assertEquals(-1, prefilter.findFirst("test", 0, 4));
        assertTrue(prefilter.isTrivial());
    }

    @Test
    void testVectorLiteralPrefilter() {
        List<String> literals = List.of("test", "hello", "world");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("test string", 0, 11));
        assertEquals(6, prefilter.findFirst("hello world", 0, 11));
        assertEquals(6, prefilter.findFirst("world test", 0, 10));
        assertEquals(-1, prefilter.findFirst("no match", 0, 8));
        assertFalse(prefilter.isTrivial());
    }

    @Test
    void testVectorLiteralPrefilterEdgeCases() {
        List<String> literals = List.of("test");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("test", 0, 4));
        assertEquals(-1, prefilter.findFirst("test", 0, 3)); // Range too small
        assertEquals(-1, prefilter.findFirst("test", 1, 4)); // Start position
        assertEquals(-1, prefilter.findFirst("test", 0, 2)); // Range before match
        assertEquals(-1, prefilter.findFirst("", 0, 0)); // Empty string
    }

    @Test
    void testAhoCorasickPrefilter() {
        List<String> literals = List.of("test", "hello", "world");
        Prefilter prefilter = new AhoCorasickPrefilter(literals, false);

        assertEquals(0, prefilter.findFirst("test string", 0, 11));
        assertEquals(6, prefilter.findFirst("hello world", 0, 11));
        assertEquals(6, prefilter.findFirst("world test", 0, 10));
        assertEquals(-1, prefilter.findFirst("no match", 0, 8));
        assertFalse(prefilter.isTrivial());
    }

    @Test
    void testAhoCorasickPrefilterNFA() {
        List<String> literals = List.of("test", "hello", "world");
        Prefilter prefilter = new AhoCorasickPrefilter(literals, true);

        assertEquals(0, prefilter.findFirst("test string", 0, 11));
        assertEquals(6, prefilter.findFirst("hello world", 0, 11));
        assertEquals(6, prefilter.findFirst("world test", 0, 10));
        assertEquals(-1, prefilter.findFirst("no match", 0, 8));
        assertFalse(prefilter.isTrivial());
    }

    @Test
    void testLiteralIndexOfPrefilter() {
        Prefilter prefilter = new LiteralIndexOfPrefilter("test");

        assertEquals(0, prefilter.findFirst("test string", 0, 11));
        assertEquals(5, prefilter.findFirst("hello test", 0, 10));
        assertEquals(-1, prefilter.findFirst("hello world", 0, 10));
        assertEquals(-1, prefilter.findFirst("", 0, 0));
        assertFalse(prefilter.isTrivial());
    }

    @Test
    void testPrefilterNullInput() {
        List<String> literals = List.of("test");
        Prefilter vectorPrefilter = new VectorLiteralPrefilter(literals);
        Prefilter ahoPrefilter = new AhoCorasickPrefilter(literals, false);
        Prefilter literalPrefilter = new LiteralIndexOfPrefilter("test");

        assertThrows(NullPointerException.class, () -> vectorPrefilter.findFirst(null, 0, 4));
        assertThrows(NullPointerException.class, () -> ahoPrefilter.findFirst(null, 0, 4));
        assertThrows(NullPointerException.class, () -> literalPrefilter.findFirst(null, 0, 4));
    }

    @Test
    void testPrefilterInvalidRange() {
        List<String> literals = List.of("test");
        Prefilter vectorPrefilter = new VectorLiteralPrefilter(literals);
        Prefilter ahoPrefilter = new AhoCorasickPrefilter(literals, false);
        Prefilter literalPrefilter = new LiteralIndexOfPrefilter("test");

        assertThrows(IllegalArgumentException.class, () -> vectorPrefilter.findFirst("test", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> ahoPrefilter.findFirst("test", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> literalPrefilter.findFirst("test", -1, 4));

        assertThrows(IllegalArgumentException.class, () -> vectorPrefilter.findFirst("test", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ahoPrefilter.findFirst("test", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> literalPrefilter.findFirst("test", 0, -1));

        assertThrows(IllegalArgumentException.class, () -> vectorPrefilter.findFirst("test", 5, 4));
        assertThrows(IllegalArgumentException.class, () -> ahoPrefilter.findFirst("test", 5, 4));
        assertThrows(IllegalArgumentException.class, () -> literalPrefilter.findFirst("test", 5, 4));
    }

    @Test
    void testPrefilterMultipleLiterals() {
        List<String> literals = List.of("test", "testing", "tester");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("test", 0, 4)); // Exact match
        assertEquals(0, prefilter.findFirst("testing", 0, 7)); // Longer match
        assertEquals(0, prefilter.findFirst("tester", 0, 6)); // Different ending
        assertEquals(-1, prefilter.findFirst("toast", 0, 5)); // Similar but not match
    }

    @Test
    void testPrefilterCaseSensitivity() {
        List<String> literals = List.of("test", "Test", "TEST");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("test", 0, 4));
        assertEquals(0, prefilter.findFirst("Test", 0, 4));
        assertEquals(0, prefilter.findFirst("TEST", 0, 4));
        assertEquals(-1, prefilter.findFirst("tEst", 0, 4)); // Different case
    }

    @Test
    void testPrefilterEmptyLiterals() {
        List<String> literals = List.of();
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(-1, prefilter.findFirst("test", 0, 4));
        assertTrue(prefilter.isTrivial());
    }

    @Test
    void testPrefilterNullLiterals() {
        assertThrows(NullPointerException.class, () -> new VectorLiteralPrefilter(null));
        assertThrows(IllegalArgumentException.class, () -> new VectorLiteralPrefilter(List.of((String) null)));
    }

    @Test
    void testPrefilterLargeInput() {
        List<String> literals = List.of("pattern");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        String largeInput = "a".repeat(10000) + "pattern" + "b".repeat(10000);
        assertEquals(10000, prefilter.findFirst(largeInput, 0, largeInput.length()));
    }

    @Test
    void testPrefilterOverlappingMatches() {
        List<String> literals = List.of("aa", "aaa");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("aaa", 0, 3)); // Should find "aa" first
    }

    @Test
    void testPrefilterUnicode() {
        List<String> literals = List.of("café", "élève", "本");
        Prefilter prefilter = new VectorLiteralPrefilter(literals);

        assertEquals(0, prefilter.findFirst("café", 0, 4));
        assertEquals(0, prefilter.findFirst("élève", 0, 7));
        assertEquals(0, prefilter.findFirst("本", 0, 1));
    }
}