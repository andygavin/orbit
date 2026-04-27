package com.orbit.compat;

import com.orbit.api.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Compatibility tests for {@link Pattern#split} against JDK 21 {@code String.split} behaviour.
 *
 * <p>All expected values in this file are derived from JDK 21 {@code String.split(regex, limit)}.
 */
public class SplitWithDelimitersCompatTest {

    static Arguments[] testSplit() {
        // Test cases verified against JDK 21 String.split(regex, limit).
        return new Arguments[] {
            // Tests with basic delimiter "o" on "boo:::and::foo"
            // "o" appears at positions 1, 2, 12, 13
            arguments(new String[] {"b", "", ":::and::f", "", ""},
                    "boo:::and::foo", "o", 5),
            arguments(new String[] {"b", "", ":::and::f", "o"},
                    "boo:::and::foo", "o", 4),
            arguments(new String[] {"b", "", ":::and::foo"},
                    "boo:::and::foo", "o", 3),
            arguments(new String[] {"b", "o:::and::foo"},
                    "boo:::and::foo", "o", 2),
            arguments(new String[] {"boo:::and::foo"},
                    "boo:::and::foo", "o", 1),
            arguments(new String[] {"b", "", ":::and::f"},
                    "boo:::and::foo", "o", 0),
            arguments(new String[] {"b", "", ":::and::f", "", ""},
                    "boo:::and::foo", "o", -1),

            // Tests with delimiter pattern ":+"
            arguments(new String[] {"boo", "and", "foo"},
                    "boo:::and::foo", ":+", 3),
            arguments(new String[] {"boo", "and::foo"},
                    "boo:::and::foo", ":+", 2),
            arguments(new String[] {"boo:::and::foo"},
                    "boo:::and::foo", ":+", 1),
            arguments(new String[] {"boo", "and", "foo"},
                    "boo:::and::foo", ":+", 0),
            arguments(new String[] {"boo", "and", "foo"},
                    "boo:::and::foo", ":+", -1),

            // Tests with pattern "a*|b*" - zero-width delimiters
            // JDK: a* is left alt, matches empty at pos 0 first, so splits between chars
            arguments(new String[] {"b", "b", ""},
                    "bb", "a*|b*", 3),
            arguments(new String[] {"b", "b"},
                    "bb", "a*|b*", 2),
            arguments(new String[] {"bb"},
                    "bb", "a*|b*", 1),
            arguments(new String[] {"b", "b"},
                    "bb", "a*|b*", 0),
            arguments(new String[] {"b", "b", ""},
                    "bb", "a*|b*", -1),

            // Tests with pattern "b*|a*" - zero-width delimiters
            // JDK: b* is left alt, matches "bb" at pos 0 greedily
            arguments(new String[] {"", "", ""},
                    "bb", "b*|a*", 3),
            arguments(new String[] {"", ""},
                    "bb", "b*|a*", 2),
            arguments(new String[] {"bb"},
                    "bb", "b*|a*", 1),
            arguments(new String[] {},
                    "bb", "b*|a*", 0),
            arguments(new String[] {"", "", ""},
                    "bb", "b*|a*", -1),
        };
    }

    @ParameterizedTest
    @MethodSource("testSplit")
    void testSplit(String[] expected, String input, String regex, int limit) {
        String[] actual = Pattern.split(regex, input, limit);
        assertArrayEquals(expected, actual,
            () -> "Failed for input='" + input + "', regex='" + regex + "', limit=" + limit);
    }

    @ParameterizedTest
    @MethodSource("testSplit")
    void testStaticSplit(String[] expected, String input, String regex, int limit) {
        String[] actual = Pattern.split(regex, input, limit);
        assertArrayEquals(expected, actual,
            () -> "Failed for input='" + input + "', regex='" + regex + "', limit=" + limit);
    }

    @Test
    void testSplitZeroLimitDropsTrailingEmptyStrings() {
        // Regular split with limit=0 should drop trailing empty strings
        assertArrayEquals(new String[] {"b", "", ":::and::f"},
                Pattern.split("o", "boo:::and::foo", 0));
        // Interior empty strings are preserved; only trailing ones are dropped
        assertArrayEquals(new String[] {"a", "", "b"},
                Pattern.split(":", "a::b::", 0));

        // Split with multiple trailing delimiters
        assertArrayEquals(new String[] {"a", "b", "c"},
                Pattern.split(":", "a:b:c::", 0));
    }

    @Test
    void testSplitNegativeLimitKeepsTrailingEmptyStrings() {
        // Regular split with limit=-1 should keep trailing empty strings
        assertArrayEquals(new String[] {"b", "", ":::and::f", "", ""},
                Pattern.split("o", "boo:::and::foo", -1));
        // JDK: "a::b::" split on ":" gives ["a","","b","",""]
        assertArrayEquals(new String[] {"a", "", "b", "", ""},
                Pattern.split(":", "a::b::", -1));
    }

    @Test
    void testSplitPositiveLimit() {
        // Positive limit should return at most N elements
        assertArrayEquals(new String[] {"boo:::and::foo"},
                Pattern.split("o", "boo:::and::foo", 1));
        // With limit=2: only first split happens; remainder includes the matched delimiter start
        assertArrayEquals(new String[] {"b", "o:::and::foo"},
                Pattern.split("o", "boo:::and::foo", 2));
        assertArrayEquals(new String[] {"b", "", ":::and::foo"},
                Pattern.split("o", "boo:::and::foo", 3));
    }

    @Test
    void testSplitEmptyInput() {
        // JDK: empty input always returns an empty array regardless of limit
        assertArrayEquals(new String[] {},
                Pattern.split("a", "", 0));
        assertArrayEquals(new String[] {},
                Pattern.split("a", "", 1));
        assertArrayEquals(new String[] {},
                Pattern.split("a", "", -1));
    }

    @Test
    void testSplitEmptyPattern() {
        // Empty pattern splits into individual characters
        assertArrayEquals(new String[] {"a", "b", "c"},
                Pattern.split("", "abc", 0));
        // With limit=2: split into first char and remainder
        assertArrayEquals(new String[] {"a", "bc"},
                Pattern.split("", "abc", 2));
    }

    @Test
    void testSplitPatternAtStartOrEnd() {
        // Pattern at start produces leading empty string
        assertArrayEquals(new String[] {"", "bc"},
                Pattern.split("a", "abc", -1));

        // Pattern at end produces trailing empty strings with appropriate limits
        assertArrayEquals(new String[] {"ab", ""},
                Pattern.split("c", "abc", -1));
        assertArrayEquals(new String[] {"ab"},
                Pattern.split("c", "abc", 0));
        // With limit=1: return entire string as one element
        assertArrayEquals(new String[] {"abc"},
                Pattern.split("c", "abc", 1));
    }

    @Test
    void testSplitConsecutiveDelimiters() {
        // ":::::" has 5 colons; with limit=5 we get 4 splits (elements 0-3 are empty, element 4
        // is the remainder after 4 splits = ":")
        assertArrayEquals(new String[] {"", "", "", "", ":"},
                Pattern.split(":", ":::::", 5));
        // With limit=-1: all 5 colons split → 6 empty strings
        assertArrayEquals(new String[] {"", "", "", "", "", ""},
                Pattern.split(":", ":::::", -1));
        // With limit=0: trailing empty strings dropped → empty array
        assertArrayEquals(new String[] {},
                Pattern.split(":", ":::::", 0));
    }

    @Test
    void testSplitNoDelimiterMatch() {
        // No delimiter in input returns the original string
        assertArrayEquals(new String[] {"abc"},
                Pattern.split("x", "abc", -1));
        assertArrayEquals(new String[] {"abc"},
                Pattern.split("x", "abc", 0));
        assertArrayEquals(new String[] {"abc"},
                Pattern.split("x", "abc", 5));
    }

    @Test
    void testSplitNullInput() {
        // Pattern.split with null input should throw NullPointerException
        assertThrows(NullPointerException.class, () -> Pattern.split("a", null, 0));
        assertThrows(NullPointerException.class, () -> Pattern.split("a", null, -1));
        assertThrows(NullPointerException.class, () -> Pattern.split("a", null, 1));

        // Also test that Pattern.compile doesn't accept null regex
        assertThrows(NullPointerException.class, () -> Pattern.compile(null));
    }

    @Test
    void testSplitWithComplexRegex() {
        // Character class: [bx] matches 'b' and 'x'; "axbxc" has 'x' at 1, 'b' at 2, 'x' at 3
        // Split produces: ["a","","","c"]
        assertArrayEquals(new String[] {"a", "", "", "c"},
                Pattern.split("[bx]", "axbxc", -1));

        // Alternation: "hello|world" matches "hello" and "world"; splitting "helloworld" on this
        // gives empty strings (both "hello" at 0-5 and "world" at 5-10); trailing empties dropped
        assertArrayEquals(new String[] {},
                Pattern.split("hello|world", "helloworld", 0));

        // Quantifier: "a+" matches one or more 'a'; "aaaabccc" has "aaaa" at 0-4
        // Split on "aaaa" gives ["","bccc"]; limit=0 drops trailing → keeps ["","bccc"]
        assertArrayEquals(new String[] {"", "bccc"},
                Pattern.split("a+", "aaaabccc", 0));
    }
}
