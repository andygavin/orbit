package com.orbit.prefilter;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.List;
import java.util.Objects;

/**
 * Prefilter that scans the input for any of a set of literal strings using SIMD acceleration
 * via the JDK Vector API ({@code jdk.incubator.vector}).
 *
 * <p>For short inputs (length {@code < SCALAR_THRESHOLD}) or when the literal list is empty,
 * a simple scalar scan is used. For longer inputs, each literal's first character is searched
 * using a {@link ShortVector} lane-compare broadcast, and the first candidate position is then
 * verified with {@link String#regionMatches}.
 *
 * <p>Literals are tested in list order; the position of the first literal (in list order) that
 * has any match in the search range is returned. Use {@link com.orbit.prefilter.AhoCorasickPrefilter}
 * when leftmost-match semantics across all literals are required.
 *
 * <p>{@link #findFirst} returns {@code -1} if no literal is found.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record VectorLiteralPrefilter(List<String> literals) implements Prefilter {

  /** Input length below which the scalar path is taken. */
  private static final int SCALAR_THRESHOLD = 32;

  /**
   * The preferred SIMD species for {@code short} lanes. {@code char} values are widened to
   * {@code short} so that a 128-bit vector holds 8 lanes and a 256-bit vector holds 16.
   */
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

  /**
   * Creates a {@code VectorLiteralPrefilter} for the given set of literals.
   *
   * @param literals the literal strings to search for; must not be null; entries must not be null
   */
  public VectorLiteralPrefilter {
    Objects.requireNonNull(literals, "literals must not be null");
    literals = List.copyOf(literals);
  }

  /**
   * Returns the match position of the first literal in list order that has any occurrence in
   * {@code input[from..to)}, or {@code -1} if no literal is found in the range.
   *
   * <p>Literals are tested in the order they appear in the list passed to the constructor. The
   * position returned is where that first-matched literal begins in the input, not necessarily
   * the leftmost position across all literals.
   *
   * @param input the string to search; must not be null
   * @param from the start index (inclusive); must be {@code >= 0}
   * @param to the end index (exclusive); must be {@code <= input.length()}
   * @return the match position for the first (list-order) literal that has a hit, or {@code -1}
   * @throws NullPointerException if {@code input} is null
   * @throws IllegalArgumentException if {@code from} or {@code to} are out of range
   */
  @Override
  public int findFirst(String input, int from, int to) {
    Objects.requireNonNull(input, "input must not be null");
    if (from < 0 || to < 0 || from > to) {
      throw new IllegalArgumentException("Invalid range: from=" + from + ", to=" + to);
    }

    if (literals.isEmpty()) {
      return -1;
    }

    int rangeLen = to - from;
    if (rangeLen < SCALAR_THRESHOLD) {
      return scalarFindFirst(input, from, to);
    }

    // Copy the relevant slice of the input to a char[] once, then run SIMD over it.
    char[] buf = input.toCharArray();

    for (String literal : literals) {
      if (literal.isEmpty()) {
        // An empty literal matches at the very start of the search range.
        return from;
      }
      int pos = simdFindLiteral(buf, from, to, literal, input);
      if (pos >= 0) {
        return pos; // first literal in list order that has a match
      }
    }
    return -1;
  }

  /** {@inheritDoc} */
  @Override
  public boolean isTrivial() {
    return literals.isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Scalar fallback
  // ---------------------------------------------------------------------------

  /**
   * Scalar search for the first literal (in list order) that has a match in {@code input[from..to)}.
   *
   * @return the match position of the first-matched literal, or {@code -1}
   */
  private int scalarFindFirst(String input, int from, int to) {
    for (String literal : literals) {
      if (literal.isEmpty()) {
        return from;
      }
      int idx = input.indexOf(literal, from);
      if (idx >= 0 && idx + literal.length() <= to) {
        return idx;
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------------------
  // SIMD scan for a single literal
  // ---------------------------------------------------------------------------

  /**
   * Searches {@code buf[from..to)} for the first occurrence of {@code literal} using a SIMD
   * scan on the literal's first character followed by a scalar region-match for confirmation.
   *
   * @param buf the char array copy of the input
   * @param from the start index in {@code buf} (inclusive)
   * @param to the end index in {@code buf} (exclusive)
   * @param literal the literal to search for
   * @param input the original input string (used for {@link String#regionMatches})
   * @return the position of the first occurrence, or {@code -1}
   */
  private static int simdFindLiteral(char[] buf, int from, int to, String literal, String input) {
    int litLen = literal.length();
    if (litLen > to - from) {
      return -1;
    }

    char firstChar = literal.charAt(0);
    short needle = (short) firstChar;
    int lanes = SPECIES.length();
    ShortVector needleVec = ShortVector.broadcast(SPECIES, needle);

    // Last position where the first character can start and still fit the full literal.
    int limit = to - litLen;

    // SIMD scan: process `lanes` characters at a time.
    int i = from;
    for (; i + lanes <= limit + 1; i += lanes) {
      ShortVector chunk = ShortVector.fromCharArray(SPECIES, buf, i);
      VectorMask<Short> match = chunk.compare(VectorOperators.EQ, needleVec);
      if (match.anyTrue()) {
        // At least one lane matched the first char — verify each hit.
        long bits = match.toLong();
        while (bits != 0) {
          int lane = Long.numberOfTrailingZeros(bits);
          int pos = i + lane;
          if (pos <= limit && input.regionMatches(pos, literal, 0, litLen)) {
            return pos;
          }
          bits &= bits - 1; // clear lowest set bit
        }
      }
    }

    // Scalar tail for the last (lanes - 1) positions that don't fill a full vector.
    for (; i <= limit; i++) {
      if (buf[i] == firstChar && input.regionMatches(i, literal, 0, litLen)) {
        return i;
      }
    }

    return -1;
  }
}
