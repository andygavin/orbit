package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.parse.PatternSyntaxException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compatibility tests for orbit's regex engine using JDK test data.
 *
 * <p>Mirrors the JDK's {@code processFile()} method from {@code RegExTest.java}: reads
 * three-line test cases (pattern, input, expected result), compiles the pattern via
 * {@link #compileTestPattern}, runs {@link Matcher#find()}, builds a result string in
 * the JDK format, and asserts equality.
 *
 * <p>Instances are not thread-safe; each parameterised invocation receives its own
 * {@code Matcher} created fresh from the compiled pattern.
 */
class BasicRegexCompatTest {

  private static final String TEST_DATA_PATH = "/jdk-tests/TestCases.txt";

  /**
   * Reads lines from {@code r} until a non-empty, non-comment line is found, then
   * processes {@code \n} literal sequences into actual newline characters and
   * backslash-u-XXXX sequences into the corresponding Unicode characters.
   *
   * <p>Mirrors {@code grabLine(BufferedReader)} in {@code RegExTest.java}.
   *
   * @param r the reader to read from; must not be null
   * @return the processed line; never null
   * @throws IOException if an I/O error occurs
   */
  static String grabLine(BufferedReader r) throws IOException {
    int index = 0;
    String line = r.readLine();
    while (line != null && (line.startsWith("//") || line.length() < 1)) {
      line = r.readLine();
    }
    if (line == null) {
      return null;
    }
    while ((index = line.indexOf("\\n")) != -1) {
      StringBuilder temp = new StringBuilder(line);
      temp.replace(index, index + 2, "\n");
      line = temp.toString();
    }
    while ((index = line.indexOf("\\u")) != -1) {
      StringBuilder temp = new StringBuilder(line);
      String value = temp.substring(index + 2, index + 6);
      char aChar = (char) Integer.parseInt(value, 16);
      temp.replace(index, index + 6, String.valueOf(aChar));
      line = temp.toString();
    }
    return line;
  }

  /**
   * Compiles a test pattern string, optionally with flags encoded in the JDK test format.
   *
   * <p>If the pattern starts with {@code '}, it is in the form {@code 'regex'flag} where
   * {@code flag} is {@code i} (CASE_INSENSITIVE) or {@code m} (MULTILINE). The quotes
   * are stripped and the pattern is compiled with the corresponding flag.
   *
   * <p>Mirrors {@code compileTestPattern(String)} in {@code RegExTest.java}.
   *
   * @param patternString the raw pattern string from the test file; must not be null
   * @return the compiled orbit {@link Pattern}
   * @throws PatternSyntaxException if the pattern is syntactically invalid
   */
  static Pattern compileTestPattern(String patternString) {
    if (!patternString.startsWith("'")) {
      return Pattern.compile(patternString);
    }
    int break1 = patternString.lastIndexOf("'");
    String flagString = patternString.substring(break1 + 1);
    String inner = patternString.substring(1, break1);

    if (flagString.equals("i")) {
      return Pattern.compile(inner, com.orbit.util.PatternFlag.CASE_INSENSITIVE);
    }
    if (flagString.equals("m")) {
      return Pattern.compile(inner, com.orbit.util.PatternFlag.MULTILINE);
    }
    return Pattern.compile(inner);
  }

  /**
   * Returns {@code true} when the raw pattern string (as read from the test file, after
   * {@link #grabLine} escape processing) uses a regex feature that Orbit has not yet
   * implemented. Tests for such patterns are skipped via {@link Assumptions#assumeFalse}.
   *
   * @param patternString the raw pattern string, after grabLine escape processing
   * @return {@code true} if the pattern requires an unimplemented feature
   */
  private static boolean isUnimplementedFeature(String patternString) {
    return patternString.contains("(?u")    // Unicode inline flag
        || patternString.contains("\\X")    // extended grapheme cluster
        // patternString.startsWith("'") handled by compileTestPattern — no longer skipped
        ; // char class union [a[b]] and intersection [a&&b] are now implemented
  }

  /**
   * Returns {@code true} if the pattern contains a {@code {} that is not a valid quantifier
   * start ({@code {N}}, {@code {N,}}, {@code {N,M}}). The JDK treats such constructs as a
   * {@code PatternSyntaxException}; Orbit currently accepts them as literal characters.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern contains an invalid brace quantifier that JDK rejects
   */
  private static boolean containsInvalidBraceQuantifier(String pattern) {
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++; // skip escaped char
      } else if (c == '{') {
        // Check if next char is a digit (valid quantifier start) or not
        if (i + 1 < pattern.length() && !Character.isDigit(pattern.charAt(i + 1))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a possessive bounded quantifier
   * such as {@code {n,m}+}.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern contains a possessive bounded quantifier
   */
  private static boolean containsPossessiveBoundedQuantifier(String pattern) {
    int idx = pattern.indexOf('}');
    while (idx >= 0 && idx + 1 < pattern.length()) {
      if (pattern.charAt(idx + 1) == '+') {
        return true;
      }
      idx = pattern.indexOf('}', idx + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a lazy bounded quantifier
   * such as {@code {n,m}?}. Lazy unbounded quantifiers ({@code *?}, {@code +?},
   * {@code ??}) are handled by the engine; only the bounded form is unimplemented.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern contains a lazy bounded quantifier
   */
  private static boolean containsLazyBoundedQuantifier(String pattern) {
    int idx = pattern.indexOf('}');
    while (idx >= 0 && idx + 1 < pattern.length()) {
      if (pattern.charAt(idx + 1) == '?') {
        return true;
      }
      idx = pattern.indexOf('}', idx + 1);
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a shorthand character class
   * ({@code \w}, {@code \W}, {@code \s}, {@code \S}, {@code \d}, {@code \D})
   * inside a character class ({@code [...]}).
   *
   * <p>Shorthand classes are supported outside character classes in Orbit but not inside.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern uses a shorthand class inside a bracket expression
   */
  private static boolean containsShorthandInCharClass(String pattern) {
    boolean inClass = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        if (inClass && i + 1 < pattern.length()) {
          char next = pattern.charAt(i + 1);
          if (next == 'w' || next == 'W' || next == 's' || next == 'S'
              || next == 'd' || next == 'D') {
            return true;
          }
        }
        i++; // skip escaped char
      } else if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a named capture group ({@code (?<name>...)}
   * or {@code (?P<name>...)}) but is not a lookbehind assertion ({@code (?<=} or
   * {@code (?<!}).
   *
   * <p>Named groups are not yet implemented; lookbehind is.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern uses a named group
   */
  private static boolean containsNamedGroup(String pattern) {
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i += 2; // skip escaped char
        continue;
      }
      // Look for (?< but not (?<= or (?<!
      if (c == '(' && i + 2 < pattern.length()
          && pattern.charAt(i + 1) == '?'
          && pattern.charAt(i + 2) == '<') {
        if (i + 3 < pattern.length()) {
          char after = pattern.charAt(i + 3);
          if (after != '=' && after != '!') {
            return true; // named group
          }
        }
      }
      // Python-style (?P<name>...)
      if (c == '(' && i + 3 < pattern.length()
          && pattern.charAt(i + 1) == '?'
          && pattern.charAt(i + 2) == 'P'
          && pattern.charAt(i + 3) == '<') {
        return true;
      }
      i++;
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains both a lazy unbounded quantifier
   * ({@code +?}, {@code *?}, {@code ??}) and a numeric backreference ({@code \1}–{@code \9}).
   *
   * <p>Orbit ignores the laziness of unbounded quantifiers (compiles them as greedy). When a
   * lazy group interacts with a backreference, the greedy result diverges from the JDK result.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern has a lazy unbounded quantifier and a backreference
   */
  private static boolean containsLazyUnboundedWithBackref(String pattern) {
    boolean hasLazy = pattern.contains("+?") || pattern.contains("*?") || pattern.contains("??");
    if (!hasLazy) {
      return false;
    }
    // Check for backreference \1 through \9
    for (int i = 0; i < pattern.length() - 1; i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        char next = pattern.charAt(i + 1);
        if (next >= '1' && next <= '9') {
          return true;
        }
        i++; // skip escaped char
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern has a group containing an inner quantifier
   * that is itself followed by an outer quantifier — e.g., {@code (a+b)+}.
   *
   * <p>The Orbit PikeVM engine's visited-array deduplication causes incorrect results
   * for such patterns: the greedy outer loop exits too early because a higher-priority
   * loop-continuation thread is prevented from re-visiting already-seen PCs within
   * the same position step.
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern has a nested-quantifier-in-outer-quantified-group structure
   */
  private static boolean containsNestedQuantifiedGroup(String pattern) {
    // Scan for groups "(...)+" or "(...)*" where the group body contains a quantifier.
    // Track paren depth and whether current group body has seen a quantifier.
    int depth = 0;
    // Stack of booleans: does the group at this depth contain an inner quantifier?
    boolean[] groupHasQuantifier = new boolean[64];

    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++; // skip escape
      } else if (c == '[') {
        // Skip character class content
        i++;
        while (i < pattern.length() && pattern.charAt(i) != ']') {
          if (pattern.charAt(i) == '\\') {
            i++;
          }
          i++;
        }
      } else if (c == '(') {
        depth++;
        if (depth < groupHasQuantifier.length) {
          groupHasQuantifier[depth] = false;
        }
      } else if (c == ')') {
        // Check if this closing group is followed by a quantifier
        boolean bodyHasQuantifier = depth < groupHasQuantifier.length
            && groupHasQuantifier[depth];
        if (bodyHasQuantifier && i + 1 < pattern.length()) {
          char after = pattern.charAt(i + 1);
          if (after == '+' || after == '*' || after == '?' || after == '{') {
            return true;
          }
        }
        depth = Math.max(0, depth - 1);
      } else if ((c == '+' || c == '*' || c == '?') && depth > 0
          && depth < groupHasQuantifier.length) {
        // Record that the current group body has a quantifier
        groupHasQuantifier[depth] = true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a character class union ({@code [a[b]]}).
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern uses character class union syntax
   */
  private static boolean isCharClassUnion(String pattern) {
    // Look for [ followed later by another [ (nested character class)
    int depth = 0;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++; // skip escaped char
      } else if (c == '[') {
        if (depth > 0) {
          return true; // nested bracket = union
        }
        depth++;
      } else if (c == ']') {
        if (depth > 0) {
          depth--;
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the pattern contains a character class intersection
   * ({@code [a&&b]}).
   *
   * @param pattern the pattern string to inspect
   * @return true if the pattern uses character class intersection syntax
   */
  private static boolean isCharClassIntersection(String pattern) {
    return pattern.contains("&&");
  }

  /**
   * Provides test cases by reading triplets (pattern, input, expected result) from
   * the JDK test data file. Uses {@link #grabLine} for all three reads, exactly as
   * the JDK's {@code processFile()} does.
   *
   * @return stream of {@link Arguments} with (patternString, dataString, expectedResult)
   */
  static Stream<Arguments> testCases() {
    List<Arguments> testCases = new ArrayList<>();

    try (InputStream is = BasicRegexCompatTest.class.getResourceAsStream(TEST_DATA_PATH)) {
      if (is == null) {
        throw new IllegalStateException(
            "Test data file not found in classpath: " + TEST_DATA_PATH);
      }

      BufferedReader reader = new BufferedReader(new InputStreamReader(is));
      String aLine;
      // The outer while mirrors processFile(): read a line (blank separator), then
      // grabLine() skips blanks/comments to find the actual pattern/input/expected.
      while ((aLine = reader.readLine()) != null) {
        String patternString = grabLine(reader);
        if (patternString == null) {
          break; // EOF after a trailing blank line — no more test cases
        }
        String dataString = grabLine(reader);
        String expectedResult = grabLine(reader);
        testCases.add(Arguments.of(patternString, dataString, expectedResult));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read test data file", e);
    }

    return testCases.stream();
  }

  /**
   * Tests regex pattern matching against JDK test data, mirroring the JDK
   * {@code processFile()} result-building logic exactly.
   *
   * <p>The result string is built as:
   * <ul>
   *   <li>{@code "true " + m.group(0) + " " + m.groupCount()} plus each non-null group if
   *       found</li>
   *   <li>{@code "false " + m.groupCount()} if not found</li>
   * </ul>
   *
   * @param patternString the raw pattern string (after grabLine processing)
   * @param dataString the input string to match
   * @param expectedResult the expected result string from the test file
   */
  @ParameterizedTest(name = "[{index}] pattern=''{0}''")
  @MethodSource("testCases")
  @DisplayName("Orbit regex engine compatibility")
  void testBasicRegex(String patternString, String dataString, String expectedResult) {
    Assumptions.assumeFalse(
        isUnimplementedFeature(patternString),
        "Skipping unimplemented feature: " + patternString);

    Pattern p;
    try {
      p = compileTestPattern(patternString);
    } catch (PatternSyntaxException e) {
      if (expectedResult.startsWith("error")) {
        return; // expected compilation error — pass
      }
      fail("Unexpected PatternSyntaxException for pattern '" + patternString + "': "
          + e.getMessage());
      return;
    } catch (RuntimeException e) {
      // Pattern.compile wraps PatternSyntaxException in RuntimeException
      if (e.getCause() instanceof PatternSyntaxException) {
        if (expectedResult.startsWith("error")) {
          return; // expected compilation error — pass
        }
        fail("Unexpected PatternSyntaxException for pattern '" + patternString + "': "
            + e.getCause().getMessage());
        return;
      }
      throw e; // rethrow unexpected runtime exceptions
    }

    Matcher m = p.matcher(dataString);
    boolean found = m.find();

    StringBuilder result = new StringBuilder();
    if (found) {
      result.append("true ");
      result.append(m.group(0)).append(" ");
    } else {
      result.append("false ");
    }
    result.append(m.groupCount());

    if (found) {
      for (int i = 1; i <= m.groupCount(); i++) {
        if (m.group(i) != null) {
          result.append(" ").append(m.group(i));
        }
      }
    }

    assertEquals(
        expectedResult,
        result.toString(),
        "Pattern = " + patternString
            + System.lineSeparator()
            + "Data = " + dataString
            + System.lineSeparator()
            + "Expected = " + expectedResult
            + System.lineSeparator()
            + "Actual   = " + result);
  }

  /** Tests that verify the test infrastructure itself is correctly configured. */
  @Nested
  @DisplayName("Test data parsing")
  class TestDataParsingTests {

    @Test
    @DisplayName("Test data file should be accessible from classpath")
    void testDataFileShouldExist() {
      InputStream is = BasicRegexCompatTest.class.getResourceAsStream(TEST_DATA_PATH);
      assertNotNull(is, "Test data file should exist in classpath: " + TEST_DATA_PATH);
    }

    @Test
    @DisplayName("Test data should be non-empty")
    void testDataShouldBeNonEmpty() throws IOException {
      try (InputStream is = BasicRegexCompatTest.class.getResourceAsStream(TEST_DATA_PATH)) {
        assertNotNull(is);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        int lineCount = 0;
        while (reader.readLine() != null) {
          lineCount++;
        }
        assertTrue(lineCount > 0, "Test data file should not be empty");
      }
    }
  }
}
