---
title: Compatibility Test Spec
nav_exclude: true
---
# Compatibility Test Suite Specification

**Audience:** Developers and QA engineers creating or maintaining adapted tests.
**Scope:** How JDK `java.util.regex` tests are mapped, adapted, and executed within orbit's framework.

---

## 1. Structure

The compatibility suite lives in `orbit-core/src/test/java/com/orbit/compat/`. Each class
in that package adapts one JDK test file or covers a distinct feature set. The mapping is:

| JDK test file | Adapted class | Coverage | Gap |
|---|---|---|---|
| `jdk-tests/RegExTest.java` (via `TestCases.txt`) | `BasicRegexCompatTest` | Partial — 6 tests skipped | `(?u` UNICODE_CHARACTER_CLASS flag, `\X` grapheme clusters |
| `jdk-tests/SplitWithDelimitersTest.java` | `SplitWithDelimitersCompatTest` | Full | None |
| `jdk-tests/GraphemeTestCases.txt` | `GraphemeCompatTest` | Partial — parameterized test disabled | `\X` not implemented |
| `jdk-tests/CaseFoldingTest.java` | `UnicodeCaseFoldingCompatTest` | Partial — 6 of 13 tests skipped | Turkish dotless-i and surrogate edge cases |
| — (hardcoded .NET cases) | `DotNetCompatTest` | 21 hardcoded .NET-specific cases | Balancing groups, conditional subpatterns, variable-length lookbehind (Phase 4) |
| `jdk-tests/NamedGroupsTests.java` | Not yet created | Pending — named groups work via `BasicRegexCompatTest`; dedicated class not yet adapted | — |
| `jdk-tests/PatternStreamTest.java` | Not yet created | Pending | — |
| `jdk-tests/POSIX_ASCII.java` | Not yet created | Pending | — |
| `jdk-tests/POSIX_Unicode.java` | Not yet created | Pending | — |
| `jdk-tests/ImmutableMatchResultTest.java` | Not yet created | Pending | — |

The integration test `PatternCompatibilityIT` (in `orbit-core/src/test/java/com/orbit/`)
provides a side-by-side smoke test of orbit vs. `java.util.regex` for basic features. It is
not a substitute for the adapted suite.

---

## 2. Adaptation Approach

### 2.1 The nine-stage elaboration process

Every new adapted test class follows these nine stages in order.

**Stage 1 — Locate the JDK source.**
Find the original test in `jdk-tests/`. Read it completely before writing any orbit code.

**Stage 2 — Identify the API surface used.**
List every `java.util.regex` method called. Map each to the orbit equivalent:

| JDK method | Orbit equivalent |
|---|---|
| `Pattern.compile(regex)` | `com.orbit.api.Pattern.compile(regex)` |
| `Pattern.compile(regex, flags)` | `Pattern.compile(regex, PatternFlag...)` |
| `pattern.matcher(input)` | `pattern.matcher(input)` |
| `matcher.find()` | `matcher.find()` |
| `matcher.matches()` | `matcher.matches()` |
| `matcher.group()` | `matcher.group()` |
| `matcher.group(n)` | `matcher.group(n)` |
| `matcher.group(name)` | `matcher.group(String name)` |
| `matcher.groupCount()` | `matcher.groupCount()` |
| `matcher.start()` | `matcher.start()` |
| `matcher.start(n)` | `matcher.start(int group)` |
| `matcher.start(name)` | `matcher.start(String name)` |
| `matcher.end()` | `matcher.end()` |
| `matcher.end(n)` | `matcher.end(int group)` |
| `matcher.end(name)` | `matcher.end(String name)` |
| `matcher.reset()` | `matcher.reset()` |
| `pattern.split(input, limit)` | `pattern.split(input, limit)` |
| `Pattern.matches(regex, input)` | `Pattern.matches(regex, input)` |
| `Pattern.quote(s)` | `Pattern.quote(s)` |

Methods with no orbit equivalent (e.g., `matcher.results()`, `matcher.namedGroups()`,
`matcher.replaceFirst(Function)`) must be noted as gaps in the adapted class's Javadoc.

**Stage 3 — Identify features orbit does not implement.**
Consult the skip list in `BasicRegexCompatTest.isUnimplementedFeature()`. If the JDK test
exercises a feature on that list, mark the corresponding test method with `@Disabled` and
note the reason. Do not silently omit the test.

**Stage 4 — Identify determinism requirements.**
JDK tests that use `java.util.Random` (e.g., `ImmutableMatchResultTest`) must fix the seed
to a compile-time constant so the test is reproducible across runs and environments.

**Stage 5 — Identify JDK internal API dependencies.**
Some JDK tests call internal APIs (e.g., `jdk.internal.util.regex.Grapheme`). Orbit does
not expose those APIs. Adapt the test to use orbit's public API exclusively, or, if that
is impossible, mark the test `@Disabled` and file a gap.

**Stage 6 — Identify data file dependencies.**
Tests reading Unicode data files (`TestCases.txt`, `GraphemeTestCases.txt`) must locate
them on the test classpath. The files in `jdk-tests/` are available as classpath resources
under `/jdk-tests/` in the test module. Confirm the resource path resolves before
writing assertions.

**Stage 7 — Write the adapted test class.**
Each adapted class:
- Is in package `com.orbit.compat`.
- Uses JUnit 5 (`org.junit.jupiter`).
- Imports only `com.orbit.api.*` and `com.orbit.util.*` from orbit production code.
- States its JDK origin in the class Javadoc.
- Uses `@ParameterizedTest` with `@MethodSource` for data-driven cases.
- Asserts equality with JUnit's `assertEquals`/`assertArrayEquals` and descriptive failure
  messages that include the pattern, input, and expected value.

**Stage 8 — Verify the failure path.**
For every test method, confirm that a deliberate mismatch produces a clear failure message.
The message must include pattern, input, and both expected and actual values. This serves as
the `ErrorToken`-equivalent signal in the JUnit output (see section 3).

**Stage 9 — Register with the suite and CI.**
Add the class to the recommended file structure (section 6). Confirm that `mvn test -pl
orbit-core` executes it.

---

### 2.2 JDK test mapping: side-by-side examples

#### BasicRegexCompatTest — data-driven from `TestCases.txt`

The JDK's `RegExTest.java` reads test cases from a flat file in groups of three lines:
pattern, input, expected result. `BasicRegexCompatTest` replicates the `processFile()` and
`grabLine()` logic exactly.

**JDK (from `RegExTest.java`, simplified):**

```java
// processFile() reads: pattern line, data line, expected line
Pattern pattern = Pattern.compile(patternString);
Matcher matcher = pattern.matcher(dataString);
boolean found = matcher.find();
// builds result string: "true <group0> <groupCount> [<group1>...]"
//                    or "false <groupCount>"
assertEquals(expectedResult, resultString);
```

**Orbit adaptation (`BasicRegexCompatTest`):**

```java
@ParameterizedTest(name = "[{index}] pattern=''{0}''")
@MethodSource("testCases")
void testBasicRegex(String patternString, String dataString, String expectedResult) {
    // Skip patterns that use features orbit has not implemented.
    Assumptions.assumeFalse(isUnimplementedFeature(patternString), ...);

    Pattern p = compileTestPattern(patternString);  // handles 'regex'flag syntax
    Matcher m = p.matcher(dataString);
    boolean found = m.find();

    StringBuilder result = new StringBuilder();
    if (found) {
        result.append("true ").append(m.group(0)).append(" ");
    } else {
        result.append("false ");
    }
    result.append(m.groupCount());
    if (found) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) result.append(" ").append(m.group(i));
        }
    }

    assertEquals(expectedResult, result.toString(),
        "Pattern = " + patternString + "\nData = " + dataString);
}
```

The key difference from the JDK test is the `isUnimplementedFeature` guard. Any pattern
that exercises a known-unsupported construct is skipped via `Assumptions.assumeFalse`
rather than failing. This preserves visibility of the skip count in the test report.

The `compileTestPattern` helper handles the JDK test file's `'regex'i` syntax (a pattern
wrapped in single quotes with a trailing flag character), mapping `i` to
`PatternFlag.CASE_INSENSITIVE` and `m` to `PatternFlag.MULTILINE`.

---

#### SplitWithDelimitersCompatTest — direct assertion

The JDK's `SplitWithDelimitersTest.java` calls `String.split` and
`Pattern.compile(...).split`. The orbit adaptation calls `Pattern.split(regex, input,
limit)` (the static utility) and `pattern.split(input, limit)` (the instance method).

**JDK:**
```java
// From SplitWithDelimitersTest.java
assertArrayEquals(new String[]{"b", "", ":::and::f", "", ""},
    "boo:::and::foo".split("o", 5));
```

**Orbit adaptation:**
```java
@ParameterizedTest
@MethodSource("testSplit")
void testSplit(String[] expected, String input, String regex, int limit) {
    String[] actual = Pattern.split(regex, input, limit);
    assertArrayEquals(expected, actual,
        () -> "Failed for input='" + input + "', regex='" + regex + "', limit=" + limit);
}
```

The expected arrays are derived from JDK 21 `String.split(regex, limit)` and recorded
statically in `testSplit()`. Any future JDK change to split semantics requires updating
those expected values.

**Empty-element preservation.** `limit=-1` keeps all trailing empty strings; `limit=0`
drops them; `limit>0` caps the result count. These three behaviours are each tested
explicitly in `testSplitNegativeLimitKeepsTrailingEmptyStrings`,
`testSplitZeroLimitDropsTrailingEmptyStrings`, and `testSplitPositiveLimit`.

---

#### DotNetCompatTest — hardcoded .NET-specific cases

`DotNetCompatTest` covers features that `java.util.regex` does not support. All expected
values are hardcoded; the harness does not delegate to the JDK for reference values.

The harness defines a `Case` record carrying description, pattern, input, expected match
presence, expected group 0 value, and optional named-group name/value arrays. A shared
`runCase` helper compiles the pattern, calls `matcher.find()`, and asserts each field.

Five sections:

1. **Named capture groups** (2 parameterized cases) — asserts `matcher.group(String name)`
   for an ISO date pattern and verifies no-match behaviour.
2. **Atomic groups** (5 parameterized cases + 1 direct `@Test`) — verifies that `(?>a*)`
   does not backtrack and that captures inside atomic groups are recorded correctly.
3. **Scoped flag changes** (8 parameterized cases) — covers `(?i:body)`, `(?-i:body)`,
   and `(?i-s:body)`.
4. **ReDoS / timeout protection** (1 direct `@Test`) — invokes `BoundedBacktrackEngine`
   directly with a 10 000-step budget and asserts that `MatchTimeoutException` is thrown
   for `(a+)+b` on 20 `a` characters followed by `!`.
5. **Possessive quantifiers** (4 parameterized cases) — verifies `a++` and `a*+` against
   matching and non-matching inputs.

Total: 21 test cases.

Balancing groups and conditional subpatterns are not tested here; they are Phase 4 work.

---

#### GraphemeCompatTest — data-driven from `GraphemeTestCases.txt`, test disabled

The test reads `GraphemeTestCases.txt` (under `/jdk-tests/` on the classpath), parses lines
in Unicode break-test format (`÷` = boundary, `×` = continuation, hex codepoint tokens),
and builds expected cluster lists.

The parameterized test method `testOrbitGraphemeClusters` is annotated `@Disabled("not yet
implemented: \\X grapheme cluster matching")`. The data loading and parsing logic is fully
implemented so the test can be enabled without structural changes once `\X` is implemented.

A separate `@Test testDataFileShouldExist` verifies that `GraphemeTestCases.txt` is
accessible on the classpath. This test is not disabled and runs on every build.

**What enables the disabled test.** Implement `\X` (extended grapheme cluster) in the
orbit parser and engine, remove the `@Disabled` annotation, and run. The expected clusters
are the reference output; any deviation is a failure.

---

#### UnicodeCaseFoldingCompatTest — partially enabled

7 of 13 tests pass. 6 tests covering Turkish dotless-i (U+0130/U+0131) and surrogate
code point edge cases remain individually `@Disabled`. The class-level `@Disabled` was
removed when basic case-insensitive matching was implemented.

The class adapts `CaseFoldingTest.java` and covers:

- Basic `(?i)` case-insensitive matching — **passes**
- Unicode folding (`(?iu)`) for ß, σ/ς — **passes for these**
- Turkish dotless-i (`(?iu)` with U+0130/U+0131) — **skipped**
- Supplementary character case folding — **skipped**
- Flag interaction: `CASE_INSENSITIVE` alone vs. `UNICODE_CASE` alone vs. both

Each test uses orbit's `PatternFlag.CASE_INSENSITIVE` and `PatternFlag.UNICODE_CASE` enum
values, passed as varargs to `Pattern.compile(regex, PatternFlag...)`.

---

## 3. Failure Reporting

Orbit's `Token` hierarchy is a sealed interface with three permits: `MatchToken`,
`OutputToken`, and `ErrorToken`. The compatibility suite does not use the token API
directly; JUnit assertions surface failures through the standard test report.

`ErrorToken` is defined as:

```java
public record ErrorToken(String message, int start, int end) implements Token { ... }
```

The compatibility analogue is the JUnit assertion failure message. Every `assertEquals`
and `assertArrayEquals` call in the suite includes a descriptive message that encodes the
same fields as an `ErrorToken` would:

- **message** — pattern, input, expected value, actual value
- **start/end** — the match boundaries, when relevant

For integration with a structured report (see section 5), a future `CompatibilityFailure`
POJO should capture: test class name, test method name, pattern string, input string,
expected result, actual result, and ISO-8601 timestamp. This parallels what the
`TO_DOCUMENT.md` brief calls an `ErrorEnvelope`:

```
{
  "code": "REGEX_INCOMPAT",
  "message": "BasicRegexCompatTest.testBasicRegex[42]: expected 'true abc 1', got 'false 1'",
  "timestamp": "2026-03-22T10:15:30Z"
}
```

No code in the current suite emits this JSON directly. The model classes
`CompatibilityFailure` and `CompatibilityReport` are not yet implemented; the recommended
location is `orbit-core/src/test/java/com/orbit/compat/model/`.

---

## 4. Known Feature Gaps

The following features are skipped in `BasicRegexCompatTest` via `isUnimplementedFeature`.
Each is a concrete skip condition:

| Pattern construct | Reason skipped |
|---|---|
| `(?u...)` | `UNICODE_CHARACTER_CLASS` inline flag not implemented |
| `\X` | Extended grapheme cluster not implemented |

Unicode property classes (`\p{...}`, `\P{...}`) are implemented and no longer skipped.

Additionally:

- `GraphemeCompatTest.testOrbitGraphemeClusters` is disabled: `\X` is not implemented.
- `UnicodeCaseFoldingCompatTest`: 7 of 13 tests pass; 6 tests covering Turkish dotless-i
  and surrogate edge cases remain `@Disabled`.

The following constructs were previously skipped and are now implemented:

| Construct | Implemented in |
|---|---|
| `(?=...)`, `(?!...)` | Lookahead |
| `(?<=...)`, `(?<!...)` | Fixed-length lookbehind |
| `(?<name>...)`, `(?P<name>...)` | Named capture groups |
| `\k<name>` | Named backreferences |
| `(?i)`, `(?m)`, `(?s)`, `(?x)` — standalone and scoped | Inline flags |
| `(?-i:body)`, `(?i-s:body)` | Flag removal and mixed scoped flags |
| `(?>...)` | Atomic groups |
| `*+`, `++`, `?+`, `{n,m}+` | Possessive quantifiers |
| `{n,m}?` | Lazy bounded quantifiers |
| `\Q...\E` | Quotemeta |
| `\0XX` | Octal escapes |
| `[\w]`, `[\s]`, `[\d]` inside `[...]` | Shorthand classes in character class |
| `[a[b]]` | Character class union |
| `[a&&b]` | Character class intersection |
| `(X+Y)+` | Nested quantified groups |

---

## 5. JSON Report Format

No JSON report is generated by the current suite. When `CompatibilityFailure` and
`CompatibilityReport` are implemented, they should produce output in this shape:

```json
{
  "generatedAt": "2026-03-23T10:15:30Z",
  "totalTests": 554,
  "passed": 520,
  "failed": 0,
  "skipped": 34,
  "passRate": 1.0,
  "failures": []
}
```

`passRate` is computed as `passed / (passed + failed)`. Skipped tests (those bypassed by
`Assumptions.assumeFalse`) are excluded from both numerator and denominator.

The report should be written to `target/compat-report.json` after the `verify` phase.
The Maven Failsafe plugin in `orbit-core/pom.xml` is already bound to `integration-test`
and `verify`; CI should be configured to fail the build if `passRate < 0.90` or if any
failure carries a `code` value not present in the previous run's report (i.e., a new
failure type was introduced).

---

## 6. Recommended File Structure

```
orbit-core/
└── src/
    └── test/
        ├── java/com/orbit/
        │   └── PatternCompatibilityIT.java          // smoke test; runs under Failsafe
        └── java/com/orbit/compat/
            ├── BasicRegexCompatTest.java            // adapted from RegExTest.java + TestCases.txt
            ├── DotNetCompatTest.java                // 21 hardcoded .NET-specific cases
            ├── SplitWithDelimitersCompatTest.java   // adapted from SplitWithDelimitersTest.java
            ├── GraphemeCompatTest.java              // adapted from GraphemeTestCases.txt; \X disabled
            ├── UnicodeCaseFoldingCompatTest.java    // adapted from CaseFoldingTest.java; 7/13 pass, 6 skipped
            ├── NamedGroupsCompatTest.java           // to be created; from NamedGroupsTests.java
            ├── StreamingResultsCompatTest.java      // to be created; from PatternStreamTest.java
            ├── POSIXASCIICompatTest.java            // to be created; from POSIX_ASCII.java
            ├── POSIXUnicodeCompatTest.java          // to be created; from POSIX_Unicode.java
            └── model/
                ├── CompatibilityFailure.java        // to be created
                └── CompatibilityReport.java         // to be created
```

Test data files are accessed as classpath resources:

| Resource path | Physical location |
|---|---|
| `/jdk-tests/TestCases.txt` | `jdk-tests/TestCases.txt` |
| `/jdk-tests/GraphemeTestCases.txt` | `jdk-tests/GraphemeTestCases.txt` |
| `/jdk-tests/BMPTestCases.txt` | `jdk-tests/BMPTestCases.txt` |
| `/jdk-tests/SupplementaryTestCases.txt` | `jdk-tests/SupplementaryTestCases.txt` |

If a resource is not found on the classpath, the test throws `IllegalStateException` rather
than producing a silent pass. This is the pattern established by `BasicRegexCompatTest` and
`GraphemeCompatTest`.

---

## 7. Running the Suite

**Unit tests only (no Failsafe):**

```
mvn test -pl orbit-core
```

This runs all classes in `src/test/java/` that JUnit discovers. `PatternCompatibilityIT`
is also picked up here because its name ends in `IT` but Surefire's default includes
are broad enough to catch it.

**Specific test class:**

```
mvn test -pl orbit-core -Dtest=BasicRegexCompatTest
mvn test -pl orbit-core -Dtest=DotNetCompatTest
mvn test -pl orbit-core -Dtest=SplitWithDelimitersCompatTest
```

**Integration tests (Failsafe, `verify` phase):**

```
mvn verify -pl orbit-core
```

**Entire build from root:**

```
mvn test
```

---

## 8. Extending the Suite

To adapt a new JDK test file:

1. Follow the nine-stage process (section 2.1).
2. Place the new class in `com.orbit.compat`, named `<Feature>CompatTest.java`.
3. Add the entry to the mapping table in this document and in `docs/compatibility.md`.
4. If the test requires Unicode data files not yet on the classpath, copy them from the
   JDK test repository to `jdk-tests/` and add the resource path to the table in section 6.
5. If the JDK test requires JVM flags (e.g., `--add-modules jdk.incubator.vector`),
   document them as prerequisites in the class Javadoc and in the CI pipeline
   configuration.
6. Run `mvn test -pl orbit-core` to confirm the new test is discovered and produces the
   expected result.

To add a .NET-specific test case to `DotNetCompatTest`, append a `Case` to the appropriate
section's `Stream.of(...)` provider method. If the case exercises a new feature category,
add a new section following the existing `// Section N` comment convention and register the
provider with a new `@ParameterizedTest` method. Update the total test count in this
document and in `docs/compatibility.md`.
