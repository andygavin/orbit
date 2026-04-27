package com.orbit.compat;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility tests for orbit's grapheme cluster handling against Java's reference implementation.
 *
 * <p>This test class reads test cases from the JDK GraphemeTestCases.txt file and validates
 * that orbit correctly identifies grapheme cluster boundaries as defined by Unicode
 * Extended Grapheme Cluster rules.
 *
 * <p>The test data format is inspired by Unicode's GraphemeBreakTest.txt:
 *   - Lines contain hex codepoints separated by '×' (no break) or '÷' (break)
 *   - Grapheme cluster boundaries occur at '÷' positions
 *   - Lines starting with '#' are comments
 *   - The sequences are represented in hexadecimal format (e.g., 1F468)
 *
 * <p>The test verifies that orbit's regex engine with the '\X' pattern correctly identifies
 * grapheme cluster breaks according to the Unicode specification.
 */
@DisplayName("Grapheme Cluster Compatibility Tests")
class GraphemeCompatTest {

    private static final String GRAPHEME_TEST_DATA_PATH = "/jdk-tests/GraphemeTestCases.txt";

    private static final String GRAPHEME_PATTERN = "\\X";

    /**
     * Provides test cases from the JDK GraphemeTestCases.txt file.
     *
     * @return stream of test case arguments containing the input string and the expected grapheme clusters
     */
    static Stream<Arguments> testCases() {
        List<Arguments> testCases = new ArrayList<>();

        try (InputStream is = GraphemeCompatTest.class.getResourceAsStream(GRAPHEME_TEST_DATA_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Grapheme test data file not found in classpath: " + GRAPHEME_TEST_DATA_PATH);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip empty lines and comments
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // Parse the line to extract expected grapheme clusters
                List<String> expectedClusters = parseTestLine(trimmed);

                if (expectedClusters.isEmpty()) {
                    continue;
                }

                // Build the full input string by concatenating all expected clusters
                StringBuilder inputBuilder = new StringBuilder();
                for (String cluster : expectedClusters) {
                    inputBuilder.append(cluster);
                }
                String input = inputBuilder.toString();

                testCases.add(Arguments.of(input, expectedClusters));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read grapheme test data file", e);
        }

        return testCases.stream();
    }

    /**
     * Parses a single test line and returns the expected grapheme cluster strings.
     *
     * <p>The format uses '÷' to indicate a grapheme boundary and '×' to indicate
     * continuation within a grapheme cluster. Hex codepoints are concatenated into
     * grapheme clusters based on these markers.
     *
     * @param line the trimmed test line
     * @return list of expected grapheme cluster strings
     */
    private static List<String> parseTestLine(String line) {
        List<String> clusters = new ArrayList<>();
        StringBuilder currentCluster = new StringBuilder();

        String[] parts = line.split(" ");

        for (String part : parts) {
            if (part.equals("÷")) {
                // Boundary marker: end current cluster if it has content
                if (currentCluster.length() > 0) {
                    clusters.add(currentCluster.toString());
                    currentCluster.setLength(0);
                }
                // If currentCluster is empty, this is a leading ÷, just ignore
            } else if (part.equals("×")) {
                // No boundary marker: continue building current cluster
                // No action needed
            } else if (!part.isEmpty() && part.matches("^[0-9A-Fa-f]+$")) {
                // Codepoint: convert to string and append to current cluster
                try {
                    int cp = Integer.parseInt(part, 16);
                    currentCluster.append(Character.toChars(cp));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid hexadecimal codepoint: " + part, e);
                }
            } else {
                // Unknown token format - this shouldn't happen in valid test data
                throw new IllegalArgumentException("Unexpected token in test line: " + part);
            }
        }

        // Add the final cluster if there is any remaining content
        if (currentCluster.length() > 0) {
            clusters.add(currentCluster.toString());
        }

        return clusters;
    }

    /**
     * Tests that orbit's grapheme cluster breaking matches the expected segmentation.
     *
     * @param input the input string containing Unicode codepoints
     * @param expectedClusters the expected list of grapheme cluster strings
     */
    @ParameterizedTest(name = "[{index}] Grapheme segmentation test")
    @MethodSource("testCases")
    @DisplayName("Grapheme cluster boundary detection")
    void testOrbitGraphemeClusters(String input, List<String> expectedClusters) {
        try {
            Pattern orbitPattern = Pattern.compile(GRAPHEME_PATTERN);
            Matcher matcher = orbitPattern.matcher(input);

            // Collect all grapheme clusters found by the matcher
            List<String> actualClusters = new ArrayList<>();
            while (matcher.find()) {
                actualClusters.add(matcher.group());
            }

            // Verify cluster count
            assertEquals(expectedClusters.size(), actualClusters.size(),
                "Expected " + expectedClusters.size() + " grapheme clusters but got " + actualClusters.size());

            // Verify each cluster matches
            for (int i = 0; i < expectedClusters.size(); i++) {
                String expected = expectedClusters.get(i);
                String actual = actualClusters.get(i);
                assertEquals(expected, actual,
                    "Grapheme cluster at index " + i + " does not match. Expected: \"" + expected + "\", actual: \"" + actual + "\"");
            }

            // Verify that concatenating the clusters reproduces the input
            String reconstructed = String.join("", actualClusters);
            assertEquals(input, reconstructed,
                "Reconstructed input from clusters does not match original input");

        } catch (Exception e) {
            fail("Grapheme cluster test failed for input: \"" + input + "\", error: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies that the GraphemeTestCases.txt file is accessible on the classpath.
     */
    @Test
    @DisplayName("Test data file should be accessible")
    void testDataFileShouldExist() {
        InputStream is = GraphemeCompatTest.class.getResourceAsStream(GRAPHEME_TEST_DATA_PATH);
        assertNotNull(is, "Grapheme test data file should exist in classpath: " + GRAPHEME_TEST_DATA_PATH);
    }
}