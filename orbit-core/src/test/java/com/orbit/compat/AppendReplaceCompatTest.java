package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ports the {@code stringBuffer*} and {@code stringBuilder*} test methods from the JDK's
 * {@code RegExTest.java}, plus {@code globalSubstitute} and the two substitution bashers,
 * covering {@link Matcher#appendReplacement(StringBuilder, String)},
 * {@link Matcher#appendTail(StringBuilder)}, {@link Matcher#replaceAll(String)}, and
 * {@link Matcher#replaceFirst(String)}.
 *
 * <p>Supplementary-character variants are disabled as the Orbit engine does not yet support
 * code points above {@code 0xFFFF}.
 *
 * <p>Instances of this class are <em>not</em> thread-safe.
 */
class AppendReplaceCompatTest {

  private static final Random RANDOM = new Random(42L);

  // ---------------------------------------------------------------------------
  // Helper: toSupplementaries — referenced only in @Disabled tests, retained for
  // documentation purposes as a static private method.
  // ---------------------------------------------------------------------------

  /**
   * Converts each BMP character in {@code s} to a surrogate pair by mapping it to the
   * supplementary code point {@code Character.MIN_SUPPLEMENTARY_CODE_POINT + ch}.
   *
   * @param s the BMP string to convert; must not be null
   * @return a string where every character from {@code s} has been replaced with a
   *     surrogate pair; never null
   */
  private static String toSupplementaries(String s) {
    int length = s.length();
    StringBuilder sb = new StringBuilder(length * 2);
    for (int i = 0; i < length; i++) {
      sb.append(Character.toChars(
          Character.MIN_SUPPLEMENTARY_CODE_POINT + s.charAt(i)));
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // Helper: getRandomAlphaString
  // ---------------------------------------------------------------------------

  /**
   * Returns a random alphabetic string of the requested length drawn from the fixed-seed
   * {@link #RANDOM} instance for reproducibility.
   *
   * @param length the number of characters to generate; must be non-negative
   * @return a string of exactly {@code length} lowercase Latin letters; never null
   */
  private static String getRandomAlphaString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char c = (char) ('a' + RANDOM.nextInt(26));
      sb.append(c);
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // globalSubstitute — Matcher.replaceAll(String)
  // ---------------------------------------------------------------------------

  /**
   * A basic sanity test of {@link Matcher#replaceAll(String)}.
   *
   * <p>Ported from {@code RegExTest.globalSubstitute} (JDK). The supplementary-character
   * sub-cases are excluded because the Orbit engine does not yet support code points above
   * {@code 0xFFFF}; {@code Matcher.reset(CharSequence)} is also absent from the Orbit API,
   * so those calls are replaced by constructing a new Matcher.
   */
  @Test
  void globalSubstitute_literalReplacement_replacesAllMatches() {
    // Global substitution with a literal (no group refs — should work)
    Pattern p = Pattern.compile("(ab)(c*)");
    Matcher m = p.matcher("abccczzzabcczzzabccc");
    assertThat(m.replaceAll("test")).isEqualTo("testzzztestzzztest");

    m = p.matcher("zzzabccczzzabcczzzabccczzz");
    assertThat(m.replaceAll("test")).isEqualTo("zzztestzzztestzzztestzzz");
  }

  @Test
  void globalSubstitute_groupReplacement_replacesAllMatches() {
    // Global substitution with groups
    Pattern p = Pattern.compile("(ab)(c*)");
    Matcher m = p.matcher("zzzabccczzzabcczzzabccczzz");
    assertThat(m.replaceAll("$1")).isEqualTo("zzzabzzzabzzzabzzz");
  }

  // ---------------------------------------------------------------------------
  // stringBuffer* tests — StringBuffer variants
  // ---------------------------------------------------------------------------

  @Test
  void stringBufferSubstituteLiteral_literalReplacement_appendsCorrectly() {
    Pattern p = Pattern.compile("blah");
    Matcher m = p.matcher("zzzblahzzz");
    StringBuffer sb = new StringBuffer();
    assertThrows(IllegalStateException.class, () -> m.appendReplacement(sb, "blech"));
    m.find();
    m.appendReplacement(sb, "blech");
    assertThat(sb.toString()).isEqualTo("zzzblech");
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("zzzblechzzz");
  }

  @Test
  void stringBufferSubtituteWithGroups_groupReplacement_appendsCorrectly() {
    Pattern p = Pattern.compile("(ab)(cd)*");
    Matcher m = p.matcher("zzzabcdzzz");
    StringBuffer sb = new StringBuffer();
    assertThrows(IllegalStateException.class, () -> m.appendReplacement(sb, "$1"));
    m.find();
    m.appendReplacement(sb, "$1");
    assertThat(sb.toString()).isEqualTo("zzzab");
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("zzzabzzz");
  }

  @Test
  void stringBufferThreeSubstitution_threeGroups_appendsCorrectly() {
    Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    Matcher m = p.matcher("zzzabcdcdefzzz");
    StringBuffer sb = new StringBuffer();
    assertThrows(IllegalStateException.class, () -> m.appendReplacement(sb, "$1w$2w$3"));
    m.find();
    m.appendReplacement(sb, "$1w$2w$3");
    assertThat(sb.toString()).isEqualTo("zzzabwcdwef");
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("zzzabwcdwefzzz");
  }

  @Test
  void stringBufferSubstituteGroupsThreeMatches_skipMiddleMatch_appendsCorrectly() {
    Pattern p = Pattern.compile("(ab)(cd*)");
    Matcher m = p.matcher("zzzabcdzzzabcddzzzabcdzzz");
    StringBuffer sb = new StringBuffer();
    assertThrows(IllegalStateException.class, () -> m.appendReplacement(sb, "$1"));
    m.find();
    m.appendReplacement(sb, "$1");
    assertThat(sb.toString()).isEqualTo("zzzab");
    m.find();
    m.find();
    m.appendReplacement(sb, "$2");
    assertThat(sb.toString()).isEqualTo("zzzabzzzabcddzzzcd");
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("zzzabzzzabcddzzzcdzzz");
  }

  @Test
  void stringBufferEscapedDollar_escapedDollar_treatedAsLiteral() {
    Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    Matcher m = p.matcher("zzzabcdcdefzzz");
    StringBuffer sb = new StringBuffer();
    m.find();
    m.appendReplacement(sb, "$1w\\$2w$3");
    assertThat(sb.toString()).isEqualTo("zzzabw$2wef");
    m.appendTail(sb);
    assertThat(sb.toString()).isEqualTo("zzzabw$2wefzzz");
  }

  @Test
  void stringBufferNonExistentGroup_nonExistentGroupRef_throwsIndexOutOfBounds() {
    Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    Matcher m = p.matcher("zzzabcdcdefzzz");
    StringBuffer sb = new StringBuffer();
    m.find();
    assertThrows(IndexOutOfBoundsException.class,
        () -> m.appendReplacement(sb, "$1w$5w$3"));
  }

  @Test
  void stringBufferCheckDoubleDigitGroupReferences_twoDigitGroup_resolvedCorrectly() {
    Pattern p = Pattern.compile("(1)(2)(3)(4)(5)(6)(7)(8)(9)(10)(11)");
    Matcher m = p.matcher("zzz123456789101112zzz");
    StringBuffer sb = new StringBuffer();
    m.find();
    m.appendReplacement(sb, "$1w$11w$3");
    assertThat(sb.toString()).isEqualTo("zzz1w11w3");
  }

  @Test
  void stringBufferBackoff_backoffFrom15To1_appendsCorrectly() {
    Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    Matcher m = p.matcher("zzzabcdcdefzzz");
    StringBuffer sb = new StringBuffer();
    m.find();
    m.appendReplacement(sb, "$1w$15w$3");
    assertThat(sb.toString()).isEqualTo("zzzabwab5wef");
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferSupplementaryCharacter_supplementaryLiteral_appendsCorrectly() {
    // Supplementary-character StringBuffer test.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferSubstitutionWithGroups_supplementaryGroups_appendsCorrectly() {
    // Supplementary-character StringBuffer with group substitution.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferSubstituteWithThreeGroups_supplementaryThreeGroups_appendsCorrectly() {
    // Supplementary-character StringBuffer with three-group substitution.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferWithGroupsAndThreeMatches_supplementarySkipMiddle_appendsCorrectly() {
    // Supplementary-character StringBuffer skipping middle of three matches.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferEnsureDollarIgnored_supplementaryEscapedDollar_treatedAsLiteral() {
    // Supplementary-character StringBuffer with escaped dollar.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferCheckNonexistentGroupReference_supplementaryBadRef_throwsIOOB() {
    // Supplementary-character StringBuffer with non-existent group reference.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferCheckSupplementalDoubleDigitGroupReferences_twoDigitGroup_resolved() {
    // Supplementary-character StringBuffer with double-digit group reference.
  }

  @Disabled("Orbit does not support supplementary code points > 0xFFFF; "
      + "test bodies are left empty pending supplementary support")
  @Test
  void stringBufferBackoffSupplemental_backoffFrom15To1_appendsCorrectly() {
    // Supplementary-character StringBuffer backoff from $15 to $1.
  }

  @Test
  void stringBufferCheckAppendException_invalidGroupSyntax_bufferUnchanged() {
    Pattern p = Pattern.compile("(abc)");
    Matcher m = p.matcher("abcd");
    StringBuffer sb = new StringBuffer();
    m.find();
    assertThrows(IllegalArgumentException.class,
        () -> m.appendReplacement(sb, "xyz$g"));
    assertThat(sb.length()).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // stringBuilder* tests — StringBuilder variants (live, using Orbit API)
  // ---------------------------------------------------------------------------

  /**
   * Tests {@link Matcher#appendReplacement(StringBuilder, String)} and
   * {@link Matcher#appendTail(StringBuilder)} with a literal replacement string.
   *
   * <p>Verifies that calling {@code appendReplacement} before any {@code find()} throws
   * {@link IllegalStateException}, and that after a successful find the literal is
   * substituted correctly.
   */
  @Test
  void stringBuilderSubstitutionWithLiteral_literalReplacement_appendsCorrectly() {
    final String blah = "zzzblahzzz";
    final Pattern p = Pattern.compile("blah");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    assertThrows(IllegalStateException.class, () -> m.appendReplacement(result, "blech"));

    m.find();
    m.appendReplacement(result, "blech");
    assertThat(result.toString()).isEqualTo("zzzblech");

    m.appendTail(result);
    assertThat(result.toString()).isEqualTo("zzzblechzzz");
  }

  /**
   * Tests {@link Matcher#appendReplacement(StringBuilder, String)} with a group reference.
   *
   * <p>Verifies that calling {@code appendReplacement} before any {@code find()} throws
   * {@link IllegalStateException}. The group-reference expansion part is disabled as Orbit
   * does not expand {@code $N} in replacement strings passed to {@code appendReplacement}.
   */
  @Test
  void stringBuilderSubstitutionWithGroups_groupReplacement_appendsCorrectly() {
    final String blah = "zzzabcdzzz";
    final Pattern p = Pattern.compile("(ab)(cd)*");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    assertThrows(IllegalStateException.class, () -> m.appendReplacement(result, "$1"));

    m.find();
    m.appendReplacement(result, "$1");
    assertThat(result.toString()).isEqualTo("zzzab");

    m.appendTail(result);
    assertThat(result.toString()).isEqualTo("zzzabzzz");
  }

  /**
   * Tests {@link Matcher#appendReplacement(StringBuilder, String)} with three group
   * references in a single replacement string.
   */
  @Test
  void stringBuilderSubstitutionWithThreeGroups_threeGroups_appendsCorrectly() {
    final String blah = "zzzabcdcdefzzz";
    final Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    assertThrows(IllegalStateException.class,
        () -> m.appendReplacement(result, "$1w$2w$3"));

    m.find();
    m.appendReplacement(result, "$1w$2w$3");
    assertThat(result.toString()).isEqualTo("zzzabwcdwef");

    m.appendTail(result);
    assertThat(result.toString()).isEqualTo("zzzabwcdwefzzz");
  }

  /**
   * Tests {@link Matcher#appendReplacement(StringBuilder, String)} with three matches where
   * the middle match is skipped by calling {@link Matcher#find()} twice without appending.
   */
  @Test
  void stringBuilderSubstitutionThreeMatch_skipMiddleMatch_appendsCorrectly() {
    final String blah = "zzzabcdzzzabcddzzzabcdzzz";
    final Pattern p = Pattern.compile("(ab)(cd*)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    assertThrows(IllegalStateException.class, () -> m.appendReplacement(result, "$1"));

    m.find();
    m.appendReplacement(result, "$1");
    assertThat(result.toString()).isEqualTo("zzzab");

    m.find();
    m.find();
    m.appendReplacement(result, "$2");
    assertThat(result.toString()).isEqualTo("zzzabzzzabcddzzzcd");

    m.appendTail(result);
    assertThat(result.toString()).isEqualTo("zzzabzzzabcddzzzcdzzz");
  }

  /**
   * Verifies that a backslash-escaped {@code $} in the replacement string is treated as a
   * literal dollar sign and not expanded as a group reference.
   */
  @Test
  void stringBuilderSubtituteCheckEscapedDollar_escapedDollar_treatedAsLiteral() {
    final String blah = "zzzabcdcdefzzz";
    final Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    m.find();
    m.appendReplacement(result, "$1w\\$2w$3");
    assertThat(result.toString()).isEqualTo("zzzabw$2wef");

    m.appendTail(result);
    assertThat(result.toString()).isEqualTo("zzzabw$2wefzzz");
  }

  /**
   * Verifies that referencing a non-existent group number throws
   * {@link IndexOutOfBoundsException}.
   */
  @Test
  void stringBuilderNonexistentGroupError_nonExistentGroupRef_throwsIndexOutOfBounds() {
    final String blah = "zzzabcdcdefzzz";
    final Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    m.find();
    assertThrows(IndexOutOfBoundsException.class,
        () -> m.appendReplacement(result, "$1w$5w$3"));
  }

  /**
   * Verifies that a two-digit group reference ({@code $11}) is resolved to group 11 rather
   * than group 1 followed by the literal character {@code "1"}.
   */
  @Test
  void stringBuilderDoubleDigitGroupReferences_twoDigitGroup_resolvedCorrectly() {
    final String blah = "zzz123456789101112zzz";
    final Pattern p = Pattern.compile("(1)(2)(3)(4)(5)(6)(7)(8)(9)(10)(11)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    m.find();
    m.appendReplacement(result, "$1w$11w$3");
    assertThat(result.toString()).isEqualTo("zzz1w11w3");
  }

  /**
   * Verifies that when there are only three groups, a reference to {@code $15} backs off
   * to {@code $1} (expanding group 1) followed by the literal character {@code "5"}.
   */
  @Test
  void stringBuilderCheckBackoff_backoffFrom15To1_appendsGroupOneLiteralFive() {
    final String blah = "zzzabcdcdefzzz";
    final Pattern p = Pattern.compile("(ab)(cd)*(ef)");
    final Matcher m = p.matcher(blah);
    final StringBuilder result = new StringBuilder();

    m.find();
    m.appendReplacement(result, "$1w$15w$3");
    assertThat(result.toString()).isEqualTo("zzzabwab5wef");
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalLiteralSubstitution_supplementaryLiteral_appendsCorrectly() {
    // Supplementary-character: literal substitution via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalSubstitutionWithGroups_supplementaryGroups_appended() {
    // Supplementary-character: group substitution via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalSubstitutionThreeGroups_supplementary3Groups_appended() {
    // Supplementary-character: three-group substitution via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSubstitutionSupplementalSkipMiddleThreeMatch_skipMiddle_appended() {
    // Supplementary-character: three matches, middle skipped, via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalEscapedDollar_escapedDollar_treatedAsLiteral() {
    // Supplementary-character: escaped dollar via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalNonExistentGroupError_badRef_throwsIndexOutOfBounds() {
    // Supplementary-character: non-existent group reference via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalCheckDoubleDigitGroupReferences_twoDigit_resolved() {
    // Supplementary-character: double-digit group reference via StringBuilder.
  }

  @Disabled("Supplementary code points > 0xFFFF not yet supported by Orbit engine")
  @Test
  void stringBuilderSupplementalCheckBackoff_backoffFrom15To1_appendsCorrectly() {
    // Supplementary-character: backoff from $15 to $1 via StringBuilder.
  }

  /**
   * Verifies that when {@link Matcher#appendReplacement(StringBuilder, String)} throws
   * {@link IllegalArgumentException} due to an invalid group reference syntax (e.g.
   * {@code "$g"}), the output buffer remains empty — no partial content is written.
   */
  @Test
  void stringBuilderCheckIllegalArgumentException_invalidGroupSyntax_bufferUnchanged() {
    final Pattern p = Pattern.compile("(abc)");
    final Matcher m = p.matcher("abcd");
    final StringBuilder result = new StringBuilder();

    m.find();
    assertThrows(IllegalArgumentException.class,
        () -> m.appendReplacement(result, "xyz$g"));
    assertThat(result.length()).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // substitutionBasher — randomised appendReplacement / replaceAll stress tests
  // ---------------------------------------------------------------------------

  /**
   * Stress test for {@link Matcher#replaceAll(String)}: builds 1 000 random five-group
   * patterns, applies a group-based replacement, and verifies the result against the
   * manually constructed expected string.
   *
   * <p>Ported from {@code RegExTest.substitutionBasher}. Uses a fixed-seed {@link Random}
   * for reproducibility.
   */
  @Test
  void substitutionBasher_randomGroupReplacement_resultMatchesExpected() {
    for (int runs = 0; runs < 1000; runs++) {
      int leadingChars = RANDOM.nextInt(10);
      StringBuilder baseBuffer = new StringBuilder(100);
      String leadingString = getRandomAlphaString(leadingChars);
      baseBuffer.append(leadingString);

      StringBuilder bufferToSub = new StringBuilder(25);
      StringBuilder bufferToPat = new StringBuilder(50);
      String[] groups = new String[5];
      for (int i = 0; i < 5; i++) {
        int aGroupSize = RANDOM.nextInt(5) + 1;
        groups[i] = getRandomAlphaString(aGroupSize);
        bufferToSub.append(groups[i]);
        bufferToPat.append('(');
        bufferToPat.append(groups[i]);
        bufferToPat.append(')');
      }
      String stringToSub = bufferToSub.toString();
      String patternStr = bufferToPat.toString();

      baseBuffer.append(stringToSub);

      int trailingChars = RANDOM.nextInt(10);
      String trailingString = getRandomAlphaString(trailingChars);
      baseBuffer.append(trailingString);
      String baseString = baseBuffer.toString();

      Pattern p = Pattern.compile(patternStr);
      Matcher m = p.matcher(baseString);

      m.find();
      if (m.start() < leadingChars) {
        continue;
      }
      if (m.find()) {
        continue;
      }

      StringBuilder bufferToRep = new StringBuilder();
      int groupIndex1 = RANDOM.nextInt(5);
      bufferToRep.append("$").append(groupIndex1 + 1);
      String randomMidString = getRandomAlphaString(5);
      bufferToRep.append(randomMidString);
      int groupIndex2 = RANDOM.nextInt(5);
      bufferToRep.append("$").append(groupIndex2 + 1);
      String replacement = bufferToRep.toString();

      String result = m.replaceAll(replacement);

      String expectedResult = leadingString
          + groups[groupIndex1]
          + randomMidString
          + groups[groupIndex2]
          + trailingString;

      assertThat(result).isEqualTo(expectedResult);
    }
  }

  /**
   * Second stress test for {@link Matcher#replaceAll(String)}: identical structure to
   * {@link #substitutionBasher_randomGroupReplacement_resultMatchesExpected()} but uses a
   * distinct run of the shared {@link Random} sequence, effectively a different set of
   * random inputs.
   *
   * <p>Ported from {@code RegExTest.substitutionBasher2}.
   */
  @Test
  void substitutionBasher2_randomGroupReplacement_resultMatchesExpected() {
    for (int runs = 0; runs < 1000; runs++) {
      int leadingChars = RANDOM.nextInt(10);
      StringBuilder baseBuffer = new StringBuilder(100);
      String leadingString = getRandomAlphaString(leadingChars);
      baseBuffer.append(leadingString);

      StringBuilder bufferToSub = new StringBuilder(25);
      StringBuilder bufferToPat = new StringBuilder(50);
      String[] groups = new String[5];
      for (int i = 0; i < 5; i++) {
        int aGroupSize = RANDOM.nextInt(5) + 1;
        groups[i] = getRandomAlphaString(aGroupSize);
        bufferToSub.append(groups[i]);
        bufferToPat.append('(');
        bufferToPat.append(groups[i]);
        bufferToPat.append(')');
      }
      String stringToSub = bufferToSub.toString();
      String patternStr = bufferToPat.toString();

      baseBuffer.append(stringToSub);

      int trailingChars = RANDOM.nextInt(10);
      String trailingString = getRandomAlphaString(trailingChars);
      baseBuffer.append(trailingString);
      String baseString = baseBuffer.toString();

      Pattern p = Pattern.compile(patternStr);
      Matcher m = p.matcher(baseString);

      m.find();
      if (m.start() < leadingChars) {
        continue;
      }
      if (m.find()) {
        continue;
      }

      StringBuilder bufferToRep = new StringBuilder();
      int groupIndex1 = RANDOM.nextInt(5);
      bufferToRep.append("$").append(groupIndex1 + 1);
      String randomMidString = getRandomAlphaString(5);
      bufferToRep.append(randomMidString);
      int groupIndex2 = RANDOM.nextInt(5);
      bufferToRep.append("$").append(groupIndex2 + 1);
      String replacement = bufferToRep.toString();

      String result = m.replaceAll(replacement);

      String expectedResult = leadingString
          + groups[groupIndex1]
          + randomMidString
          + groups[groupIndex2]
          + trailingString;

      assertThat(result).isEqualTo(expectedResult);
    }
  }
}
