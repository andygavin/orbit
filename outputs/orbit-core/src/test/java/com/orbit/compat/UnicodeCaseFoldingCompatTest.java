package com.orbit.compat;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import com.orbit.util.PatternFlag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeCaseFoldingCompatTest {

    @Test
    void testCaseInsensitiveMatching() {
        Pattern pattern = Pattern.compile("(?i)hello");
        Matcher matcher = pattern.matcher("HELLO world");
        assertTrue(matcher.find());
        assertEquals("HELLO", matcher.group());
    }

    @Test
    void testUnicodeCaseFolding() {
        Pattern pattern = Pattern.compile("(?iu)\u00df", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("ss");
        assertTrue(matcher.matches());

        pattern = Pattern.compile("(?iu)ss", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("\u00df");
        assertTrue(matcher.matches());
    }

    @Test
    void testSupplementaryCharacterFolding() {
        // MUSICAL SYMBOL G CLEF (U+1D11E) and its case equivalents
        Pattern pattern = Pattern.compile("(?iu)\u1d11e", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("\ud834\udd1e"); // same character as surrogate pair
        assertTrue(matcher.matches());

        // Test with a character that folds to itself in some cases
        pattern = Pattern.compile("(?iu)A", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("a");
        assertTrue(matcher.matches());
    }

    @Test
    void testCharClassWithCaseFolding() {
        Pattern pattern = Pattern.compile("(?iu)[\u00df]", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("s");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("S");
        assertTrue(matcher.matches());
    }

    @Test
    void testRangeWithCaseFolding() {
        Pattern pattern = Pattern.compile("(?iu)[a-e]", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("A");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("e");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("E");
        assertTrue(matcher.matches());
    }

    @Test
    void testMixedFlagCombinations() {
        // CASE_INSENSITIVE only
        Pattern pattern = Pattern.compile("(?i)\u00df", PatternFlag.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher("ss");
        assertFalse(matcher.matches()); // Without UNICODE_CASE, ß doesn't fold to ss

        // UNICODE_CASE only (should be case sensitive)
        pattern = Pattern.compile("(?u)\u00df", PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("SS");
        assertFalse(matcher.matches());

        // Both CASE_INSENSITIVE and UNICODE_CASE
        pattern = Pattern.compile("(?iu)\u00df", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("ss");
        assertTrue(matcher.matches());
    }

    @Test
    void testComplexPatternWithCaseFolding() {
        Pattern pattern = Pattern.compile("(?iu)Straße", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("STRASSE");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("strasse");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("straße");
        assertTrue(matcher.matches());
    }

    @Test
    void testSpecialFoldingCases() {
        // Greek small final sigma (U+03C2) and medial sigma (U+03C3)
        Pattern pattern = Pattern.compile("(?iu)\u03c2", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("\u03c3"); // medial sigma
        assertTrue(matcher.matches());

        pattern = Pattern.compile("(?iu)\u03c3", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("\u03c2"); // final sigma
        assertTrue(matcher.matches());
    }

    @Test
    void testEdgeCasesFromJDKTest() {
        // These are specific cases from the JDK CaseFoldingTest that are known to be problematic

        // Test case where folding doesn't map back to original
        Pattern pattern = Pattern.compile("(?iu)\u1fd3", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("\u1fd3"); // Greek small iota with dialytika and oxia
        assertTrue(matcher.matches());

        // Test another problematic case
        pattern = Pattern.compile("(?iu)\u1fe3", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("\u1fe3"); // Greek small upsilon with dialytika and oxia
        assertTrue(matcher.matches());
    }

    @Test
    void testComplexSubstitutions() {
        Pattern pattern = Pattern.compile("(?iu)Maß", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("MASSE");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("masse");
        assertTrue(matcher.matches());

        matcher = pattern.matcher("maß");
        assertTrue(matcher.matches());
    }

    @Test
    void testNonAsciiFolding() {
        // Turkish dotted/dotless i
        Pattern pattern = Pattern.compile("(?iu)\u0131", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("I"); // In Turkish context, this should match
        assertTrue(matcher.matches());

        pattern = Pattern.compile("(?iu)i", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("\u0130"); // Latin capital I with dot above
        assertTrue(matcher.matches());
    }

    @Test
    void testEmptyStringWithFlags() {
        Pattern pattern = Pattern.compile("(?iu)", PatternFlag.CASE_INSENSITIVE, PatternFlag.UNICODE_CASE);
        Matcher matcher = pattern.matcher("");
        assertTrue(matcher.matches()); // Empty pattern matches empty string
    }

    @Test
    void testBoundaryCases() {
        // Test that flags work independently
        Pattern pattern = Pattern.compile("a", PatternFlag.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher("A");
        assertTrue(matcher.matches());

        pattern = Pattern.compile("a", PatternFlag.UNICODE_CASE);
        matcher = pattern.matcher("A");
        assertFalse(matcher.matches()); // UNICODE_CASE alone doesn't imply case insensitivity
    }
}