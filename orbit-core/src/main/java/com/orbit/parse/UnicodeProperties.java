package com.orbit.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Unicode property names to lists of BMP character ranges.
 *
 * <p>Supports the following property families:
 * <ul>
 *   <li>Unicode general categories (e.g. {@code Lu}, {@code L}, {@code N})</li>
 *   <li>POSIX-style named classes (e.g. {@code Alpha}, {@code Digit}, {@code Space})</li>
 *   <li>Unicode blocks via the {@code In} prefix (e.g. {@code InBasicLatin})</li>
 *   <li>Unicode scripts via the {@code Is} prefix or bare name (e.g. {@code IsLatin},
 *       {@code Latin})</li>
 *   <li>The {@code Is} prefix also works as an alias for general categories and POSIX
 *       names (e.g. {@code IsL}, {@code IsASCII})</li>
 * </ul>
 *
 * <p>Only BMP code points (U+0000 – U+FFFF) are covered; supplementary characters are out
 * of scope.
 *
 * <p>Resolved results are cached in a thread-safe map, so the BMP scan is performed at most
 * once per unique {@code (name, negated)} combination.
 *
 * <p>This class is a static utility class and cannot be instantiated.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public final class UnicodeProperties {

  private static final ConcurrentHashMap<String, List<CharRange>> CACHE =
      new ConcurrentHashMap<>();

  private UnicodeProperties() {}

  /**
   * Resolves a Unicode property name to a list of BMP character ranges.
   *
   * @param name    the property name as it appears between the braces; must not be null or blank
   * @param negated {@code true} if the surrounding escape was {@code \P} (complement)
   * @return the list of matching BMP ranges, never null or empty
   * @throws NullPointerException     if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   * @throws PatternSyntaxException   if the property name is not recognised
   */
  public static List<CharRange> resolve(String name, boolean negated) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Property name must not be blank");
    }

    String cacheKey = name + (negated ? "_neg" : "");
    List<CharRange> cached = CACHE.get(cacheKey);
    if (cached != null) {
      return cached;
    }

    List<CharRange> result = computeRanges(name, negated);
    CACHE.putIfAbsent(cacheKey, result);
    return result;
  }

  // -----------------------------------------------------------------------
  // Internal resolution
  // -----------------------------------------------------------------------

  /**
   * Computes the ranges for the given property name and negation flag by scanning the BMP.
   *
   * @param name    the property name (caret already stripped)
   * @param negated whether to complement the result
   * @return the computed range list, never null or empty
   * @throws PatternSyntaxException if the property name is not recognised
   */
  private static List<CharRange> computeRanges(String name, boolean negated) {
    boolean[] matched = new boolean[0x10000];

    // Strip Unicode property value prefix: gc= or general_category= (case-insensitive).
    // e.g. "gc=Ll" → "Ll", "general_category=Lu" → "Lu"
    String lowerCheck = name.toLowerCase(java.util.Locale.ROOT);
    if (lowerCheck.startsWith("gc=")) {
      name = name.substring(3);
    } else if (lowerCheck.startsWith("general_category=")) {
      name = name.substring(17);
    }

    // 0. java-prefixed property names (e.g. javaLowerCase, javaUpperCase).
    String lowerName = name.toLowerCase(java.util.Locale.ROOT);
    if (lowerName.startsWith("java") && lowerName.length() > 4) {
      String suffix = lowerName.substring(4);
      if (handleJavaProperty(suffix, matched)) {
        return collapseToRanges(matched, negated);
      }
      throw new PatternSyntaxException("Unknown Unicode property: " + name);
    }

    String upper = name.toUpperCase();

    // 1. Check POSIX-style names first (case-insensitive).
    if (isPosixMatch(upper, matched)) {
      return collapseToRanges(matched, negated);
    }

    // 2. Unicode block: name starts with "In".
    if (upper.startsWith("IN") && name.length() > 2) {
      String blockName = name.substring(2);
      Character.UnicodeBlock block;
      try {
        block = Character.UnicodeBlock.forName(blockName);
      } catch (IllegalArgumentException e) {
        throw new PatternSyntaxException("Unknown Unicode property: " + name);
      }
      for (int c = 0; c < 0x10000; c++) {
        matched[c] = Character.UnicodeBlock.of(c) == block;
      }
      return collapseToRanges(matched, negated);
    }

    // 3. Unicode script or Is-prefixed alias.
    //    If name starts with "Is", try:
    //      a) the stripped suffix as a script name
    //      b) the stripped suffix as a POSIX name
    //      c) the stripped suffix as a general category
    //    Also try the bare name as a script (no prefix).
    if (upper.startsWith("IS") && name.length() > 2) {
      String stripped = name.substring(2);
      String strippedUpper = stripped.toUpperCase();

      // 3a. Try as a script.
      Character.UnicodeScript script = tryResolveScript(stripped);
      if (script != null) {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.UnicodeScript.of(c) == script;
        }
        return collapseToRanges(matched, negated);
      }

      // 3b. Try as a POSIX name (Is-prefixed alias, e.g. IsASCII, IsAlpha).
      if (isPosixMatch(strippedUpper, matched)) {
        return collapseToRanges(matched, negated);
      }

      // 3c. Try as a general category (e.g. IsL, IsLC, IsPf).
      if (isGeneralCategoryMatch(stripped, matched)) {
        return collapseToRanges(matched, negated);
      }

      throw new PatternSyntaxException("Unknown Unicode property: " + name);
    }

    // Bare name: try as a script.
    Character.UnicodeScript script = tryResolveScript(name);
    if (script != null) {
      for (int c = 0; c < 0x10000; c++) {
        matched[c] = Character.UnicodeScript.of(c) == script;
      }
      return collapseToRanges(matched, negated);
    }

    // 4. General Unicode category.
    if (isGeneralCategoryMatch(name, matched)) {
      return collapseToRanges(matched, negated);
    }

    throw new PatternSyntaxException("Unknown Unicode property: " + name);
  }

  // -----------------------------------------------------------------------
  // java-prefixed property names
  // -----------------------------------------------------------------------

  /**
   * Handles {@code java}-prefixed property names by populating {@code matched} for each BMP
   * code point according to the corresponding {@link Character#isXxx(int)} predicate.
   *
   * <p>Recognised suffixes (all lowercase): {@code lowercase}, {@code uppercase},
   * {@code titlecase}, {@code digit}, {@code defined}, {@code letter}, {@code letterordigit},
   * {@code whitespace}, {@code isocontrol}, {@code mirrored}.
   *
   * @param lowerSuffix the part of the property name after {@code "java"}, already lowercased;
   *     must not be null
   * @param matched     the 0x10000-element boolean array to populate; must not be null
   * @return {@code true} if the suffix was recognised; {@code false} otherwise
   */
  private static boolean handleJavaProperty(String lowerSuffix, boolean[] matched) {
    return switch (lowerSuffix) {
      case "lowercase" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLowerCase(c);
        }
        yield true;
      }
      case "uppercase" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isUpperCase(c);
        }
        yield true;
      }
      case "titlecase" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isTitleCase(c);
        }
        yield true;
      }
      case "digit" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isDigit(c);
        }
        yield true;
      }
      case "defined" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isDefined(c);
        }
        yield true;
      }
      case "letter" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLetter(c);
        }
        yield true;
      }
      case "letterordigit" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLetterOrDigit(c);
        }
        yield true;
      }
      case "whitespace" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isWhitespace(c);
        }
        yield true;
      }
      case "isocontrol" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isISOControl(c);
        }
        yield true;
      }
      case "mirrored" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isMirrored(c);
        }
        yield true;
      }
      default -> false;
    };
  }

  // -----------------------------------------------------------------------
  // POSIX-style names
  // -----------------------------------------------------------------------

  /**
   * Tests whether {@code upperName} is a recognised POSIX-style property name, and if so
   * populates {@code matched}. Returns {@code true} when the name was recognised.
   *
   * @param upperName the uppercased property name; must not be null
   * @param matched   the 0x10000-element boolean array to populate; must not be null
   * @return {@code true} if the name was handled
   */
  private static boolean isPosixMatch(String upperName, boolean[] matched) {
    return switch (upperName) {
      case "ALPHA" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLetter(c);
        }
        yield true;
      }
      case "DIGIT" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isDigit(c);
        }
        yield true;
      }
      case "ALNUM" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLetterOrDigit(c);
        }
        yield true;
      }
      case "SPACE" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F';
        }
        yield true;
      }
      case "BLANK" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = c == ' ' || c == '\t';
        }
        yield true;
      }
      case "UPPER" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isUpperCase(c);
        }
        yield true;
      }
      case "LOWER" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLowerCase(c);
        }
        yield true;
      }
      case "ASCII" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = c <= 0x7F;
        }
        yield true;
      }
      case "L1" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = c >= 0x80 && c <= 0xFF;
        }
        yield true;
      }
      case "PUNCT" -> {
        for (int c = 0; c < 0x10000; c++) {
          int type = Character.getType(c);
          matched[c] = type == Character.CONNECTOR_PUNCTUATION
              || type == Character.DASH_PUNCTUATION
              || type == Character.START_PUNCTUATION
              || type == Character.END_PUNCTUATION
              || type == Character.INITIAL_QUOTE_PUNCTUATION
              || type == Character.FINAL_QUOTE_PUNCTUATION
              || type == Character.OTHER_PUNCTUATION;
        }
        yield true;
      }
      case "GRAPH" -> {
        for (int c = 0; c < 0x10000; c++) {
          int type = Character.getType(c);
          matched[c] = type != Character.SPACE_SEPARATOR
              && type != Character.LINE_SEPARATOR
              && type != Character.PARAGRAPH_SEPARATOR
              && type != Character.CONTROL
              && type != Character.SURROGATE
              && type != Character.UNASSIGNED
              && c != '\t' && c != '\n' && c != '\r' && c != '\f';
        }
        yield true;
      }
      case "PRINT" -> {
        for (int c = 0; c < 0x10000; c++) {
          int type = Character.getType(c);
          matched[c] = type != Character.CONTROL
              && type != Character.SURROGATE
              && type != Character.UNASSIGNED;
        }
        yield true;
      }
      case "CNTRL", "CONTROL" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.CONTROL;
        }
        yield true;
      }
      case "WORD" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isLetterOrDigit(c) || c == '_'
              || Character.getType(c) == Character.CONNECTOR_PUNCTUATION;
        }
        yield true;
      }
      case "ALPHABETIC" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.isAlphabetic(c);
        }
        yield true;
      }
      case "XDIGIT" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.digit(c, 16) != -1;
        }
        yield true;
      }
      default -> false;
    };
  }

  // -----------------------------------------------------------------------
  // Unicode general categories
  // -----------------------------------------------------------------------

  /**
   * Tests whether {@code name} is a recognised Unicode general category name and populates
   * {@code matched}. Returns {@code true} when the name was recognised.
   *
   * <p>Supports both single-letter combined categories ({@code L}, {@code N}, etc.) and
   * two-letter single categories ({@code Lu}, {@code Ll}, etc.), compared
   * case-insensitively.
   *
   * @param name    the property name with original casing; must not be null
   * @param matched the 0x10000-element boolean array to populate; must not be null
   * @return {@code true} if the name was handled
   */
  private static boolean isGeneralCategoryMatch(String name, boolean[] matched) {
    String upper = name.toUpperCase();
    return switch (upper) {
      // Combined letter category
      case "L" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.UPPERCASE_LETTER
              || t == Character.LOWERCASE_LETTER
              || t == Character.TITLECASE_LETTER
              || t == Character.MODIFIER_LETTER
              || t == Character.OTHER_LETTER;
        }
        yield true;
      }
      // Combined cased letter
      case "LC" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.UPPERCASE_LETTER
              || t == Character.LOWERCASE_LETTER
              || t == Character.TITLECASE_LETTER;
        }
        yield true;
      }
      // Combined number
      case "N" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.DECIMAL_DIGIT_NUMBER
              || t == Character.LETTER_NUMBER
              || t == Character.OTHER_NUMBER;
        }
        yield true;
      }
      // Combined punctuation
      case "P" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.CONNECTOR_PUNCTUATION
              || t == Character.DASH_PUNCTUATION
              || t == Character.START_PUNCTUATION
              || t == Character.END_PUNCTUATION
              || t == Character.INITIAL_QUOTE_PUNCTUATION
              || t == Character.FINAL_QUOTE_PUNCTUATION
              || t == Character.OTHER_PUNCTUATION;
        }
        yield true;
      }
      // Combined symbol
      case "S" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.MATH_SYMBOL
              || t == Character.CURRENCY_SYMBOL
              || t == Character.MODIFIER_SYMBOL
              || t == Character.OTHER_SYMBOL;
        }
        yield true;
      }
      // Combined separator
      case "Z" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.SPACE_SEPARATOR
              || t == Character.LINE_SEPARATOR
              || t == Character.PARAGRAPH_SEPARATOR;
        }
        yield true;
      }
      // Combined other / control
      case "C" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.CONTROL
              || t == Character.FORMAT
              || t == Character.SURROGATE
              || t == Character.PRIVATE_USE
              || t == Character.UNASSIGNED;
        }
        yield true;
      }
      // Combined mark
      case "M" -> {
        for (int c = 0; c < 0x10000; c++) {
          int t = Character.getType(c);
          matched[c] = t == Character.NON_SPACING_MARK
              || t == Character.COMBINING_SPACING_MARK
              || t == Character.ENCLOSING_MARK;
        }
        yield true;
      }
      // Single categories
      case "LU", "UPPERCASE", "UPPERCASE_LETTER" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.UPPERCASE_LETTER;
        }
        yield true;
      }
      case "LL", "LOWERCASE", "LOWERCASE_LETTER" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.LOWERCASE_LETTER;
        }
        yield true;
      }
      case "LT", "TITLECASE", "TITLECASE_LETTER" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.TITLECASE_LETTER;
        }
        yield true;
      }
      case "LM" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.MODIFIER_LETTER;
        }
        yield true;
      }
      case "LO" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.OTHER_LETTER;
        }
        yield true;
      }
      case "ND" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER;
        }
        yield true;
      }
      case "NL" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.LETTER_NUMBER;
        }
        yield true;
      }
      case "NO" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.OTHER_NUMBER;
        }
        yield true;
      }
      case "PC" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.CONNECTOR_PUNCTUATION;
        }
        yield true;
      }
      case "PD" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.DASH_PUNCTUATION;
        }
        yield true;
      }
      case "PS" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.START_PUNCTUATION;
        }
        yield true;
      }
      case "PE" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.END_PUNCTUATION;
        }
        yield true;
      }
      case "PI" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.INITIAL_QUOTE_PUNCTUATION;
        }
        yield true;
      }
      case "PF" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.FINAL_QUOTE_PUNCTUATION;
        }
        yield true;
      }
      case "PO" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.OTHER_PUNCTUATION;
        }
        yield true;
      }
      case "SM" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.MATH_SYMBOL;
        }
        yield true;
      }
      case "SC" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.CURRENCY_SYMBOL;
        }
        yield true;
      }
      case "SK" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.MODIFIER_SYMBOL;
        }
        yield true;
      }
      case "SO" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.OTHER_SYMBOL;
        }
        yield true;
      }
      case "ZS" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.SPACE_SEPARATOR;
        }
        yield true;
      }
      case "ZL" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.LINE_SEPARATOR;
        }
        yield true;
      }
      case "ZP" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.PARAGRAPH_SEPARATOR;
        }
        yield true;
      }
      case "CC" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.CONTROL;
        }
        yield true;
      }
      case "CF" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.FORMAT;
        }
        yield true;
      }
      case "CS" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.SURROGATE;
        }
        yield true;
      }
      case "CO" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.PRIVATE_USE;
        }
        yield true;
      }
      case "CN" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.UNASSIGNED;
        }
        yield true;
      }
      case "MN" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.NON_SPACING_MARK;
        }
        yield true;
      }
      case "MC" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.COMBINING_SPACING_MARK;
        }
        yield true;
      }
      case "ME" -> {
        for (int c = 0; c < 0x10000; c++) {
          matched[c] = Character.getType(c) == Character.ENCLOSING_MARK;
        }
        yield true;
      }
      default -> false;
    };
  }

  // -----------------------------------------------------------------------
  // Script resolution
  // -----------------------------------------------------------------------

  /**
   * Tries to resolve a script name, returning the matching {@link Character.UnicodeScript} or
   * {@code null} if the name is not a known script.
   *
   * @param name the script name (may or may not be uppercased); must not be null
   * @return the matching script, or {@code null} if unrecognised
   */
  private static Character.UnicodeScript tryResolveScript(String name) {
    try {
      return Character.UnicodeScript.forName(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // -----------------------------------------------------------------------
  // BMP collapse
  // -----------------------------------------------------------------------

  /**
   * Collapses a {@code boolean[0x10000]} matched-character array into a sorted list of
   * non-overlapping {@link CharRange} objects.
   *
   * <p>When {@code negated} is {@code true}, the boolean values are complemented before
   * collapsing, so the returned ranges cover all characters that did <em>not</em> match the
   * original property test.
   *
   * @param matched an array of exactly 0x10000 booleans, one per BMP code point
   * @param negated whether to flip the matched values before collapsing
   * @return the collapsed range list, never null; may be a single sentinel range if nothing
   *     matched
   */
  private static List<CharRange> collapseToRanges(boolean[] matched, boolean negated) {
    if (negated) {
      for (int c = 0; c < 0x10000; c++) {
        matched[c] = !matched[c];
      }
    }

    List<CharRange> ranges = new ArrayList<>();
    int start = -1;

    for (int c = 0; c < 0x10000; c++) {
      if (matched[c] && start < 0) {
        start = c;
      } else if (!matched[c] && start >= 0) {
        ranges.add(new CharRange((char) start, (char) (c - 1)));
        start = -1;
      }
    }
    if (start >= 0) {
      ranges.add(new CharRange((char) start, '\uFFFF'));
    }

    if (ranges.isEmpty()) {
      // Return a sentinel that matches nothing — an empty CharClass is invalid.
      ranges.add(new CharRange('\u0000', '\u0000'));
    }

    return List.copyOf(ranges);
  }
}
