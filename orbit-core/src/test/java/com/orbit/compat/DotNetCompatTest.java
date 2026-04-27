package com.orbit.compat;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.engine.MatchTimeoutException;
import com.orbit.engine.engines.BoundedBacktrackEngine;
import com.orbit.prog.Prog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compatibility test harness for .NET-specific regex features.
 *
 * <p>All expected values are hardcoded — this test harness does not delegate to
 * {@code java.util.regex}, which does not support these constructs. Each case is
 * a self-contained specification of what Orbit's engine must produce.
 *
 * <p>Features covered:
 * <ol>
 *   <li>Named capture groups (already working)</li>
 *   <li>Atomic groups {@code (?>...)} (Feature 1, new)</li>
 *   <li>Scoped flag removal {@code (?-i:...)} and mixed {@code (?i-s:...)} (Feature 2, new)</li>
 *   <li>ReDoS / timeout protection via bounded backtracking budget</li>
 *   <li>Possessive quantifiers as .NET {@code (?>...)} equivalents</li>
 * </ol>
 *
 * <p>Instances are not thread-safe; each parameterised invocation receives its own
 * {@code Matcher} created fresh from the compiled pattern.
 */
@DisplayName(".NET Compatibility Tests")
class DotNetCompatTest {

  // -----------------------------------------------------------------------
  // Case record
  // -----------------------------------------------------------------------

  /**
   * A single .NET compat test case carrying the description, pattern, input, and expected
   * match outcome. Named capture expectations are expressed as parallel arrays of names and
   * expected values.
   *
   * @param description   human-readable description shown in the test name
   * @param pattern       the regular expression to compile
   * @param input         the input string to match against
   * @param expectMatch   whether a match is expected
   * @param expectedGroup0 the expected full match string when {@code expectMatch} is true;
   *                       ignored when {@code expectMatch} is false; may be null
   * @param namedGroupNames  named capture groups whose values should be asserted; may be empty
   * @param namedGroupValues expected values for the corresponding named groups; must be the
   *                         same length as {@code namedGroupNames}
   */
  private record Case(
      String description,
      String pattern,
      String input,
      boolean expectMatch,
      String expectedGroup0,
      String[] namedGroupNames,
      String[] namedGroupValues) {

    /** Convenience constructor for cases with no named group assertions. */
    Case(String description, String pattern, String input, boolean expectMatch,
        String expectedGroup0) {
      this(description, pattern, input, expectMatch, expectedGroup0,
          new String[0], new String[0]);
    }
  }

  // -----------------------------------------------------------------------
  // Section 1: Named capture groups
  // -----------------------------------------------------------------------

  private static Stream<Arguments> namedGroupCases() {
    return Stream.of(
        Arguments.of(new Case(
            "ISO date — named groups year/month/day",
            "(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})",
            "2026-03-22",
            true,
            "2026-03-22",
            new String[]{"year", "month", "day"},
            new String[]{"2026", "03", "22"})),
        Arguments.of(new Case(
            "Named group with no match",
            "(?<word>\\d+)",
            "abc",
            false,
            null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("namedGroupCases")
  @DisplayName("Section 1: Named capture groups")
  void namedGroupCompat(Case c) {
    runCase(c);
  }

  // -----------------------------------------------------------------------
  // Section 2: Atomic groups (?>...)
  // -----------------------------------------------------------------------

  private static Stream<Arguments> atomicGroupCases() {
    return Stream.of(
        Arguments.of(new Case(
            "(?>a*)b on 'aaab' — atomic a* greedily takes all a's, b matches remainder",
            "(?>a*)b",
            "aaab",
            true,
            "aaab")),
        Arguments.of(new Case(
            "(?>a*)b on 'aaa' — atomic a* takes all a's, no b left to match",
            "(?>a*)b",
            "aaa",
            false,
            null)),
        Arguments.of(new Case(
            "(?>a+)a on 'aa' — atomic a+ takes both a's, nothing left for trailing a",
            "(?>a+)a",
            "aa",
            false,
            null)),
        Arguments.of(new Case(
            "(?>a+)a on 'aaa' — atomic a+ takes all three, nothing left for trailing a",
            "(?>a+)a",
            "aaa",
            false,
            null)),
        Arguments.of(new Case(
            "(?>(a+))b on 'aab' — atomic group with capture: group(1) = 'aa'",
            "(?>(a+))b",
            "aab",
            true,
            "aab"))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("atomicGroupCases")
  @DisplayName("Section 2: Atomic groups")
  void atomicGroupCompat(Case c) {
    runCase(c);
  }

  /**
   * Verifies that the capture inside an atomic group is correctly recorded.
   *
   * <p>(?>(a+))b on "aab": the atomic group greedily captures "aa" into group 1, then "b"
   * matches the literal. Because the input ends with "b", the match succeeds.
   */
  @org.junit.jupiter.api.Test
  @DisplayName("Atomic group captures: (?>(a+))b on 'aab' → group(1)='aa'")
  void atomicGroupCapture_groupContentIsCorrect() {
    Matcher m = Pattern.compile("(?>(a+))b").matcher("aab");
    assertThat(m.find()).isTrue();
    assertThat(m.group(0)).isEqualTo("aab");
    assertThat(m.group(1)).isEqualTo("aa");
  }

  // -----------------------------------------------------------------------
  // Section 3: Scoped flag changes (?-i:...) and (?flags:...)
  // -----------------------------------------------------------------------

  private static Stream<Arguments> scopedFlagCases() {
    return Stream.of(
        Arguments.of(new Case(
            "(?i:hello) on 'HELLO' — scoped case-insensitive match",
            "(?i:hello)",
            "HELLO",
            true,
            "HELLO")),
        Arguments.of(new Case(
            "(?i:hello) on 'hello' — scoped case-insensitive matches lowercase too",
            "(?i:hello)",
            "hello",
            true,
            "hello")),
        Arguments.of(new Case(
            "(?i)hello(?-i:world) on 'HELLOworld' — case-insensitive hello, case-sensitive world",
            "(?i)hello(?-i:world)",
            "HELLOworld",
            true,
            "HELLOworld")),
        Arguments.of(new Case(
            "(?i)hello(?-i:world) on 'HELLOWORLD' — second part requires literal 'world'",
            "(?i)hello(?-i:world)",
            "HELLOWORLD",
            false,
            null)),
        Arguments.of(new Case(
            "(?i-s:hello) on 'HELLO' — case-insensitive flag on, dotall off (no change to literal)",
            "(?i-s:hello)",
            "HELLO",
            true,
            "HELLO")),
        Arguments.of(new Case(
            "(?i-s:hello) on 'hello' — lowercase literal with case-insensitive flag",
            "(?i-s:hello)",
            "hello",
            true,
            "hello")),
        Arguments.of(new Case(
            "(?-i:hello) on 'hello' (default flags) — no-op removal, literal still matches",
            "(?-i:hello)",
            "hello",
            true,
            "hello")),
        Arguments.of(new Case(
            "(?-i:hello) on 'HELLO' (default flags) — case-sensitive: uppercase should not match",
            "(?-i:hello)",
            "HELLO",
            false,
            null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("scopedFlagCases")
  @DisplayName("Section 3: Scoped flag changes")
  void scopedFlagCompat(Case c) {
    runCase(c);
  }

  // -----------------------------------------------------------------------
  // Section 4: Timeout / ReDoS protection
  // -----------------------------------------------------------------------

  /**
   * Verifies that catastrophically-backtracking patterns are interrupted before exhausting
   * system resources.
   *
   * <p>{@code (a+)+b} on a string of 20 {@code a}s followed by {@code !} causes exponential
   * backtracking in a naive engine. The test invokes {@link BoundedBacktrackEngine} directly
   * with a small budget so the timeout fires quickly, without depending on the meta-engine's
   * routing decision (the pattern has no backrefs or possessive quantifiers, so the meta-engine
   * would route it to the PikeVM, which doesn't backtrack exponentially).
   */
  @org.junit.jupiter.api.Test
  @DisplayName("Section 4: ReDoS protection — (a+)+b times out on evil input")
  void redos_catastrophicBacktracking_throwsMatchTimeout() {
    String evilInput = "a".repeat(20) + "!";
    Prog prog = Pattern.compile("(a+)+b").prog();
    BoundedBacktrackEngine engine = new BoundedBacktrackEngine(10_000);
    RuntimeException ex = assertThrows(RuntimeException.class, () ->
        engine.execute(prog, evilInput, 0, evilInput.length(), 0, true, false, 0));
    assertThat(ex.getCause()).isInstanceOf(MatchTimeoutException.class);
  }

  // -----------------------------------------------------------------------
  // Section 5: Possessive quantifiers (.NET (?>...) equivalence)
  // -----------------------------------------------------------------------

  private static Stream<Arguments> possessiveCases() {
    return Stream.of(
        Arguments.of(new Case(
            "a++b on 'aaab' — possessive a+ takes all a's, b matches",
            "a++b",
            "aaab",
            true,
            "aaab")),
        Arguments.of(new Case(
            "a++b on 'aaa' — possessive a+ takes all a's, no b left",
            "a++b",
            "aaa",
            false,
            null)),
        Arguments.of(new Case(
            "a*+b on 'aaab' — possessive a* matches all three a's, b matches remainder",
            "a*+b",
            "aaab",
            true,
            "aaab")),
        Arguments.of(new Case(
            "a*+b on 'b' — possessive a* matches empty, b matches",
            "a*+b",
            "b",
            true,
            "b"))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("possessiveCases")
  @DisplayName("Section 5: Possessive quantifiers (equivalent to .NET atomic groups)")
  void possessiveQuantifierCompat(Case c) {
    runCase(c);
  }

  // -----------------------------------------------------------------------
  // Section 6: Balancing groups
  // -----------------------------------------------------------------------

  /**
   * Standard .NET balanced-parentheses pattern.
   *
   * <p>Uses {@code (?<Open>\()} to push on open, {@code (?<-Open>\))} to pop on close, and
   * {@code (?(Open)(?!))} as a final assertion that the stack is empty (i.e., all opens were
   * matched).
   */
  private static final String BALANCED_PARENS =
      "^(?:[^()]|(?<Open>\\()|(?<-Open>\\)))*(?(Open)(?!))$";

  private static Stream<Arguments> balancingGroupMatchCases() {
    return Stream.of(
        Arguments.of(new Case(
            "empty string — no parens, stack always empty",
            BALANCED_PARENS, "", true, "")),
        Arguments.of(new Case(
            "() — single balanced pair",
            BALANCED_PARENS, "()", true, "()")),
        Arguments.of(new Case(
            "(abc) — balanced pair with content",
            BALANCED_PARENS, "(abc)", true, "(abc)")),
        Arguments.of(new Case(
            "((()))  — triply nested balanced",
            BALANCED_PARENS, "((()))", true, "((()))")),
        Arguments.of(new Case(
            "()()() — three adjacent pairs",
            BALANCED_PARENS, "()()()", true, "()()()")),
        Arguments.of(new Case(
            "(a(b)c(d(e)f)) — complex nesting",
            BALANCED_PARENS, "(a(b)c(d(e)f))", true, "(a(b)c(d(e)f))")),
        Arguments.of(new Case(
            "word (without parens) word — no parens needed",
            BALANCED_PARENS, "word (without parens) word", true,
            "word (without parens) word"))
    );
  }

  private static Stream<Arguments> balancingGroupNoMatchCases() {
    return Stream.of(
        Arguments.of(new Case(
            "( — unclosed open",
            BALANCED_PARENS, "(", false, null)),
        Arguments.of(new Case(
            ") — unopened close",
            BALANCED_PARENS, ")", false, null)),
        Arguments.of(new Case(
            "(() — unclosed nested",
            BALANCED_PARENS, "(()", false, null)),
        Arguments.of(new Case(
            "()) — extra close",
            BALANCED_PARENS, "())", false, null)),
        Arguments.of(new Case(
            ")( — wrong order",
            BALANCED_PARENS, ")(", false, null)),
        Arguments.of(new Case(
            "((() — double unclosed",
            BALANCED_PARENS, "((())", false, null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("balancingGroupMatchCases")
  @DisplayName("Section 6a: Balancing groups — should match")
  void balancingGroups_shouldMatch(Case c) {
    runCase(c);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("balancingGroupNoMatchCases")
  @DisplayName("Section 6b: Balancing groups — should NOT match")
  void balancingGroups_shouldNotMatch(Case c) {
    runCase(c);
  }

  // -----------------------------------------------------------------------
  // Section 7: Conditional subpatterns
  // -----------------------------------------------------------------------

  private static Stream<Arguments> groupIndexConditionalCases() {
    return Stream.of(
        Arguments.of(new Case(
            "aX — group 1 captured 'a', takes X branch",
            "^(a)?(?(1)X|Y)$", "aX", true, "aX")),
        Arguments.of(new Case(
            "Y — group 1 not captured, takes Y branch",
            "^(a)?(?(1)X|Y)$", "Y", true, "Y")),
        Arguments.of(new Case(
            "X — group 1 not captured but got X (wants Y) — no match",
            "^(a)?(?(1)X|Y)$", "X", false, null)),
        Arguments.of(new Case(
            "aY — group 1 captured but got Y (wants X) — no match",
            "^(a)?(?(1)X|Y)$", "aY", false, null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("groupIndexConditionalCases")
  @DisplayName("Section 7a: Conditional — group-number condition (?(1)X|Y)")
  void conditional_groupIndex(Case c) {
    runCase(c);
  }

  private static Stream<Arguments> lookaheadConditionalCases() {
    return Stream.of(
        Arguments.of(new Case(
            "123! — lookahead sees digit, takes digit branch",
            "^(?(?=\\d)\\d+|[a-z]+)!$", "123!", true, "123!")),
        Arguments.of(new Case(
            "abc! — no digit, takes letter branch",
            "^(?(?=\\d)\\d+|[a-z]+)!$", "abc!", true, "abc!")),
        Arguments.of(new Case(
            "12ab! — starts with digit but letters follow — no match",
            "^(?(?=\\d)\\d+|[a-z]+)!$", "12ab!", false, null)),
        Arguments.of(new Case(
            "! — neither branch matches — no match",
            "^(?(?=\\d)\\d+|[a-z]+)!$", "!", false, null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("lookaheadConditionalCases")
  @DisplayName("Section 7b: Conditional — lookahead condition (?(?=\\d)\\d+|[a-z]+)")
  void conditional_lookahead(Case c) {
    runCase(c);
  }

  private static Stream<Arguments> groupNameConditionalCases() {
    return Stream.of(
        Arguments.of(new Case(
            "123NUM — Digit group captured, takes NUM branch",
            "^(?<Digit>\\d+)?(?<Alpha>[a-z]+)?(?(Digit)NUM|WORD)$", "123NUM", true, "123NUM")),
        Arguments.of(new Case(
            "abcWORD — Alpha captured, no Digit, takes WORD branch",
            "^(?<Digit>\\d+)?(?<Alpha>[a-z]+)?(?(Digit)NUM|WORD)$", "abcWORD", true, "abcWORD")),
        Arguments.of(new Case(
            "123WORD — Digit captured but got WORD — no match",
            "^(?<Digit>\\d+)?(?<Alpha>[a-z]+)?(?(Digit)NUM|WORD)$", "123WORD", false, null))
    );
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("groupNameConditionalCases")
  @DisplayName("Section 7c: Conditional — group-name condition (?(Digit)NUM|WORD)")
  void conditional_groupName(Case c) {
    runCase(c);
  }

  // -----------------------------------------------------------------------
  // Shared test execution helper
  // -----------------------------------------------------------------------

  /**
   * Executes a single {@link Case} against Orbit's compiled pattern engine and asserts the
   * expected outcome. Named group assertions are checked only when {@code expectMatch} is true
   * and named group names are provided.
   *
   * @param c the test case to run; must not be null
   */
  private static void runCase(Case c) {
    Pattern compiled = Pattern.compile(c.pattern());
    Matcher m = compiled.matcher(c.input());
    boolean matched = m.find();

    assertThat(matched)
        .as("match presence for pattern '%s' on input '%s'", c.pattern(), c.input())
        .isEqualTo(c.expectMatch());

    if (c.expectMatch()) {
      assertThat(m.group(0))
          .as("full match (group 0) for pattern '%s' on input '%s'", c.pattern(), c.input())
          .isEqualTo(c.expectedGroup0());

      for (int i = 0; i < c.namedGroupNames().length; i++) {
        String name = c.namedGroupNames()[i];
        String expected = c.namedGroupValues()[i];
        assertThat(m.group(name))
            .as("named group '%s' for pattern '%s' on input '%s'", name, c.pattern(), c.input())
            .isEqualTo(expected);
      }
    }
  }
}
