---
title: Compatibility
nav_order: 4
---
# JDK Regex Compatibility Guide

Orbit's regex engine targets behavioral parity with `java.util.regex` as shipped in JDK 21.
This guide describes which JDK behaviors are covered, which are not, how to run the
compatibility suite, and how to interpret its results.

---

## Compatibility approach

Orbit adapts OpenJDK test files from `jdk-tests/` directly. Each adapted class in
`orbit-core/src/test/java/com/orbit/compat/` reads the same test data as the JDK test it
mirrors, compiles patterns with `com.orbit.api.Pattern`, and asserts that orbit's output
matches the JDK reference.

Patterns that exercise features orbit has not yet implemented are skipped via JUnit 5
`@Disabled` or `Assumptions.assumeFalse` rather than removed. Skipped tests remain visible
in the test report so that coverage gaps are explicit.

---

## Feature coverage

### Covered

| Feature | Adapted test class | JDK source |
|---|---|---|
| Concatenation, alternation, character classes | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Greedy quantifiers (`*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Lazy unbounded quantifiers (`*?`, `+?`, `??`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Lazy bounded quantifiers (`{n,m}?`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Possessive quantifiers (`*+`, `++`, `?+`, `{n,m}+`) | `BasicRegexCompatTest`, `DotNetCompatTest` | `jdk-tests/TestCases.txt` |
| Capturing groups and numeric backreferences (`\1`–`\9`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Named capturing groups (`(?<name>...)`, `(?P<name>...)`, `(?'name'...)`) | `BasicRegexCompatTest`, `DotNetCompatTest`, `NamedGroupsCompatTest` | `jdk-tests/TestCases.txt` |
| Named backreferences (`\k<name>`, `\k'name'`, `\k{name}`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Anchors (`^`, `$`, `\A`, `\Z`, `\z`, `\G`) — including Unicode line terminators and MULTILINE CRLF | `BasicRegexCompatTest`, `RegExCompatTest` | `jdk-tests/TestCases.txt` |
| Lookahead (`(?=...)`, `(?!...)`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Lookbehind (`(?<=...)`, `(?<!...)`) — fixed-length and bounded variable-length | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Inline flags — standalone (`(?i)`, `(?m)`, `(?s)`, `(?x)`, `(?-i)`, `(?i-s)`) | `BasicRegexCompatTest`, `DotNetCompatTest` | `jdk-tests/TestCases.txt` |
| Inline flags — scoped (`(?i:body)`, `(?-i:body)`, `(?i-s:body)`) | `BasicRegexCompatTest`, `DotNetCompatTest` | `jdk-tests/TestCases.txt` |
| Atomic groups (`(?>...)`) | `DotNetCompatTest` | — |
| Balancing groups (`(?<name-name2>...)`) | `DotNetCompatTest` | — |
| Conditional subpatterns (`(?(condition)yes\|no)`) | `DotNetCompatTest` | — |
| Branch reset groups (`(?|...)`) | `BranchResetTest` | — |
| `\K` keep assertion (reset match start) | `KeepAssertionTest` | — |
| Nested quantified groups (`(a+b)+`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Character class shorthand inside `[...]` (`[\w\d]`, `[\s\S]`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Character class union (`[a[bc]]`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Character class intersection (`[a&&[bc]]`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| `\Q...\E` quotemeta | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| Octal escapes (`\0`, `\00`, `\000`–`\0377`) | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| `\x{NNN}` hex code point escapes | `PerlRegexCompatTest` | Perl source distribution |
| `\cX` control character escapes | `PerlRegexCompatTest` | Perl source distribution |
| `\o{NNN}` octal code point escapes | `PerlRegexCompatTest` | Perl source distribution |
| `\N` non-newline shorthand | `PerlRegexCompatTest` | Perl source distribution |
| `\N{NAME}` Unicode named character escapes | `UnicodeRegexCompatTest` | — |
| `\R` line-break escape (`\n`, `\r`, `\r\n`, `\u0085`, `\u2028`, `\u2029`) | `PerlRegexCompatTest` | Perl source distribution |
| `\h` / `\H` horizontal whitespace | `PerlRegexCompatTest` | Perl source distribution |
| `\v` / `\V` vertical whitespace | `PerlRegexCompatTest` | Perl source distribution |
| `\g{n}` / `\g{name}` backreferences | `PerlRegexCompatTest` | Perl source distribution |
| `(?#comment)` inline comments | `PerlRegexCompatTest` | Perl source distribution |
| `{,N}` bounded repetition with omitted lower bound | `PerlRegexCompatTest` | Perl source distribution |
| Case-insensitive backreferences | `BasicRegexCompatTest` | `jdk-tests/TestCases.txt` |
| `split(regex, input, limit)` — all limit semantics | `SplitWithDelimitersCompatTest` | `jdk-tests/SplitWithDelimitersTest.java` |
| Zero-width delimiter splits | `SplitWithDelimitersCompatTest` | `jdk-tests/SplitWithDelimitersTest.java` |
| `Pattern.matches`, `Pattern.quote` | `RegExCompatTest` | `jdk-tests/RegExTest.java` |
| `Matcher.replaceAll(String)` | `RegExCompatTest` | `jdk-tests/RegExTest.java` |
| `Matcher.results()` streaming API with CME detection | `PatternStreamCompatTest` | `jdk-tests/PatternStreamTest.java` |
| `Matcher.hitEnd()` | `UnicodeRegexCompatTest` | — |
| `Matcher.usePattern()`, `region()`, `regionStart()`, `regionEnd()` | `RegExCompatTest` | `jdk-tests/RegExTest.java` |
| `Matcher.useAnchoringBounds()`, `useTransparentBounds()` | `RegExCompatTest` | `jdk-tests/RegExTest.java` |
| `matcher.namedGroups()` | `NamedGroupsCompatTest` | `jdk-tests/NamedGroupsTests.java` |
| `ImmutableMatchResult` | `ImmutableMatchResultCompatTest` | `jdk-tests/ImmutableMatchResultTest.java` |
| ReDoS protection (`MatchTimeoutException` on budget exhaustion) | `DotNetCompatTest` | — |
| `PatternFlag.UNICODE` — Unicode `\w`/`\d`/`\s`/`\b` and extended case folding | `UnicodeRegexCompatTest`, `UnicodeCaseFoldingCompatTest` | — |
| `PatternFlag.PERL_NEWLINES` — dot excludes `\n` only; `\r`/`\r\n` as anchor terminators | `PerlRegexCompatTest` | — |
| `PatternFlag.RE2_COMPAT` — compile-time rejection of non-RE2 constructs | `Re2CompatTest` | — |
| Perl regex test suite (`re_tests`) | `PerlRegexCompatTest` | Perl source distribution |

### Partially covered

| Feature | Status | Adapted test class |
|---|---|---|
| Unicode case folding (`(?iu)`, `PatternFlag.UNICODE_CASE`) | 9 of 13 tests pass; 4 skipped (multi-char folds: ß↔ss, ligatures, supplementary) | `UnicodeCaseFoldingCompatTest` |
| Grapheme clusters (`\X`) | Test infrastructure complete; `\X` not yet implemented | `GraphemeCompatTest` |
| `appendReplacement`/`appendTail(StringBuilder/StringBuffer)` | 22 of 38 tests pass; 16 skipped (supplementary-character code-point tests) | `AppendReplaceCompatTest` |
| Advanced JDK features | 103 of 111 tests pass; 8 skipped | `AdvancedMatchingCompatTest` |
| JDK regression suite | 120 of 122 tests pass; 2 skipped | `RegExCompatTest` |
| Unicode properties and edge cases | 60 of 64 tests pass; 4 skipped | `UnicodeRegexCompatTest` |
| Perl `re_tests` suite | 1351 of 1974 tests pass; 623 skipped | `PerlRegexCompatTest` |
| Basic regex cases | 301 of 307 tests pass; 6 skipped (supplementary, `\X`) | `BasicRegexCompatTest` |
| POSIX ASCII classes | 14 of 14 tests pass | `POSIXASCIICompatTest` |
| POSIX Unicode classes | 20 of 35 tests pass; 15 skipped (`(?U)`, `\p{Is*}`, emoji properties) | `POSIXUnicodeCompatTest` |

_Test counts as of 2026-04-25. Run `mvn test -pl orbit-core` for current figures._

---

## .NET regex extensions

`DotNetCompatTest` covers features present in .NET's regex engine that have no equivalent
in `java.util.regex`. Expected values in this harness are hardcoded — the test does not
delegate to `java.util.regex`, which does not support these constructs.

| Feature | Status |
|---|---|
| Named capture groups (`(?<name>...)`) | Implemented |
| Atomic groups (`(?>...)`) | Implemented |
| Scoped flag changes (`(?i:body)`, `(?-i:body)`, `(?i-s:body)`) | Implemented |
| Possessive quantifiers (`a++`, `a*+`) | Implemented |
| Balancing groups (`(?<name-name2>...)`) | Implemented |
| Conditional subpatterns (`(?(condition)yes\|no)`) | Implemented |
| ReDoS / timeout protection | Implemented (`MatchTimeoutException`) |
| Variable-length lookbehind | Bounded variable-length implemented; unbounded (`.*` in lookbehind) not yet supported |

---

## Known incompatibilities

The following are confirmed gaps between Orbit and `java.util.regex` or the Perl `re_tests`
suite, organized by category. Each item is tracked by at least one `@Disabled` test or
`KNOWN_FAILING_LINES` entry in the compat suite.

### API gaps

| Gap | Notes |
|---|---|
| `Matcher.requireEnd()` | Not implemented |

### Missing features

| Feature | Notes |
|---|---|
| Supplementary code points > U+FFFF | Engine is BMP-only; all UTF-16 surrogate-pair inputs fail |
| Unbounded variable-length lookbehind | `.*` and `\w+` inside lookbehind not supported; fixed-length and bounded repetitions work |
| `\X` grapheme cluster escape | Not in parser |
| `\b{g}` grapheme boundary | Not in parser |
| `\b{n}` quantified word boundary | Not in parser |
| `(?u)` inline form of `UNICODE_CHARACTER_CLASS` | `PatternFlag.UNICODE` works; the inline-flag form `(?u)` is not wired |
| Self-referential backreferences (`(a\1?)`) | Group references its own capture during matching |
| Conditional backreferences (`(?(1)\1)`) | Condition tests whether a group participated; backref inside condition |
| Capturing groups inside lookahead/lookbehind | Groups matched inside lookaround return `-1` from `group(n)` |

### Unicode and case-folding gaps

| Gap | Notes |
|---|---|
| Unicode ligature case folds | ff→U+FB00, fi→U+FB01, fl→U+FB02, st→U+FB05 multi-character folds not implemented |
| Reverse multi-char fold (ß↔ss) | `CharMatch` cannot represent one-char-to-two-char mappings; `ß` and `ss` are not treated as equivalent under `CASE_INSENSITIVE` |
| Case fold in lookbehind | `(?iu)(?<=\xdf)` should match `ss` (via ß fold) but does not |
| Capital sharp S `\x{1E9E}` CI complement | `[^\x{1E9E}]/i` should not match `ß` (U+00DF) because they fold to the same uppercase; not correctly handled |

### Parsing bugs

| Bug | Notes |
|---|---|
| Perl octal `\400`–`\777` | Perl extends the octal range to U+0100–U+01FF; see Deliberate divergences |
| `[\0005]` — NUL adjacent to digit in char class | Should produce class `{NUL, '5'}`; Orbit parses the octal sequence differently |

### Engine / matching bugs

| Bug | Notes |
|---|---|
| `\s` consuming `\n` then `^` in MULTILINE | `\s` consuming a newline leaves `^` unable to assert start-of-line on the next position |
| `(?!)+?` edge case | Always-failing zero-width assertion under a one-or-more lazy quantifier not handled |
| Branch reset + conditional backreference | `(?|(?<a>a)|(?<b>b))(?(<a>)x|y)\1` — conditional on a named group from a branch-reset alternative combined with a backreference to the same slot produces a wrong result |

---

## Deliberate divergences from Perl

The items below are **intentional choices**, not implementation gaps. Each reflects a
decision to follow JDK `java.util.regex` semantics (or to make a specific implementation
choice) rather than match Perl/PCRE behaviour.

### Line terminators

JDK recognises six line terminators: `\n`, `\r`, the `\r\n` pair (as one unit), `\u0085`
(NEL), `\u2028` (line separator), and `\u2029` (paragraph separator). Perl/PCRE recognises
only three (`\n`, `\r`, `\r\n`).

Orbit follows JDK. This means `.` excludes all six characters by default, and `$`/`\Z`
match before any of them at end of input. Patterns that rely on `.` matching `\u0085`
will behave differently than their Perl equivalent.

See "Line terminator semantics" below and `docs/compatibility.md` for the flag reference.

### `\10` backreference disambiguation

When a pattern has fewer than 10 capturing groups, Perl treats `\10` as the octal escape
`\010` (backspace, U+0008). JDK uses a different rule: `\10` is treated as `\1` followed
by the literal character `0`. Orbit takes a third approach: `\10`–`\99` are always treated
as backreferences to the numbered capturing group, regardless of how many groups exist.

If the referenced group does not exist, the backreference fails to match (rather than being
silently reinterpreted as an octal or partial-backref escape). This is simpler and more
predictable than either Perl or JDK, but means patterns that rely on the Perl or JDK
disambiguation rules will behave differently.

_Affected `KNOWN_FAILING_LINES`: 291, 1965–1968._

### Perl octal `\400`–`\777`

Perl extends its octal escape range to U+01FF: `\400` = U+0100, `\777` = U+01FF. JDK does
not implement this extension; Orbit follows JDK. These escape sequences will either fail
to match (if interpreted as a backreference to a non-existent group) or throw a
`PatternSyntaxException`.

_Affected `KNOWN_FAILING_LINES`: 1559–1564._

### ZWNJ and ZWJ as `\w`

Perl treats U+200C (ZERO WIDTH NON-JOINER) and U+200D (ZERO WIDTH JOINER) as word
characters under some conditions. Java's default `\w` definition (`[a-zA-Z0-9_]`) does
not include them, and neither does Orbit's. Under `PatternFlag.UNICODE`, Orbit's `\w`
uses Unicode `Alphabetic`, `Nd`, and `Pc` properties, which also exclude ZWNJ and ZWJ.

_Affected `KNOWN_FAILING_LINES`: 1847–1850._

### Perl group-reset semantics

In Perl, when a capturing group inside a repeated alternation does not participate in the
final iteration of the repetition, the group's value is reset to `undef`. In JDK and Orbit,
the group retains its last non-null value. The JDK behaviour is simpler to implement in a
single-pass NFA and is consistent with most non-Perl regex engines.

Example: `/(a)|(b)/ =~ "b"` — Perl sets `$1` to `undef`; JDK/Orbit set group 1 to `null`
(not matched, consistent with any other non-participating group).

The more subtle case is inside a loop: `/(?:(a)|(b))+/` matching `"ab"` — after the second
iteration, Perl resets `$1` to `undef` (since `a` did not participate in iteration 2);
JDK/Orbit leave `$1 = "a"` from the first iteration.

_Affected `KNOWN_FAILING_LINES`: 483, 506, 969, 970, 2141, 2144, 2145._

### `\N{N}` quantifier syntax

In Perl, `\N` matches any non-newline character, and `\N{N}` is a quantifier on `\N` meaning
"exactly N non-newline characters." In JDK and Orbit, `\N{...}` is a Unicode named character
escape: `\N{LATIN SMALL LETTER A}` matches `a`. When the braces contain a plain number (`\N{3}`),
no Unicode character name matches, so Orbit throws `PatternSyntaxException`.

_Affected `KNOWN_FAILING_LINES`: 42–44, 48–51._

### `defined($1)` test expressions

Several Perl `re_tests` rows use `defined($1)` as the evaluation expression — a Perl idiom
for checking whether a capturing group participated in the match (as distinct from matching
the empty string). There is no direct Java equivalent. These rows are permanently skipped.

_Affected `KNOWN_FAILING_LINES`: 1459._

---

## Line terminator semantics

Orbit follows JDK `java.util.regex` semantics. There is no Perl-compatibility mode. The
differences below affect `.`, `$`, `\Z`, and the MULTILINE anchors `^`/`$`.

### Recognised terminators

JDK recognises six line terminators. Perl/PCRE recognises three.

| Terminator | Unicode | JDK / Orbit | Perl/PCRE |
|---|---|---|---|
| Line feed | `\n` U+000A | Yes | Yes |
| Carriage return | `\r` U+000D | Yes | Yes |
| CRLF sequence | `\r\n` | Yes — treated as one unit | Yes — treated as one unit |
| Next line (NEL) | `\u0085` | Yes | No |
| Line separator | `\u2028` | Yes | No |
| Paragraph separator | `\u2029` | Yes | No |

`UNIX_LINES` restricts all six to `\n` only, affecting dot, `$`, `\Z`, `^`, and `$` under
`MULTILINE`. The flag is not a Perl-compatibility mode: for anchors it is stricter than
Perl, because `\r` is not a terminator at all under `UNIX_LINES`, whereas Perl still
treats `\r` as a line terminator for `$`, `^`, and `\Z`.

`PatternFlag.PERL_NEWLINES` is the closest Perl-compatible mode: dot excludes `\n` only,
and `\r`/`\r\n` are still recognised as line terminators for anchor purposes (unlike
`UNIX_LINES`, which drops `\r` entirely).

### Dot (`.`) behaviour

Without flags, `.` excludes all six terminators above (both `\r` and `\n` are individually
excluded). Perl's `.` excludes only `\n` by default.

| Flag combination | Characters excluded by `.` |
|---|---|
| No flags (JDK default) | `\n`, `\r`, `\u0085`, `\u2028`, `\u2029` |
| `UNIX_LINES` | `\n` only |
| `PERL_NEWLINES` | `\n` only |
| `DOTALL` | none — `.` matches every character |
| `DOTALL` + `UNIX_LINES` | none |
| Perl default | `\n` only |
| Perl `/s` | none |

`UNIX_LINES` and `PERL_NEWLINES` produce the same dot behaviour (both exclude `\n` only),
but diverge for anchors: under `UNIX_LINES`, `\r` is not a line terminator at all; under
`PERL_NEWLINES`, `\r` and `\r\n` remain valid anchor positions.

```java
// . does not match \u0085 (NEL) in JDK default mode.
// The pattern matches "test" because dot stops before the terminator.
Pattern.compile("....").matcher("test\u0085").find();   // true
Pattern.compile(".....").matcher("test\u0085").find();  // false — fifth char is NEL

// With UNIX_LINES, dot excludes only \n.
// NEL is now matchable.
Pattern.compile(".....", PatternFlag.UNIX_LINES).matcher("test\u0085").find(); // true
```

### `$` and `\Z` with CRLF

`$` (non-MULTILINE) and `\Z` match at absolute end of input or immediately before the
final line terminator. JDK treats the CRLF pair `\r\n` as a single two-character unit for
this purpose: `\Z` passes at the position before `\r` when `\r\n` is the trailing
sequence. It does not pass between `\r` and `\n`.

```java
// \Z passes before the \r of a trailing \r\n.
Pattern.compile("foo\\Z").matcher("foo\r\n").find();  // true

// \n\r is not a recognised unit; \Z does not pass before \r here.
Pattern.compile("foo\\Z").matcher("foo\n\r").find();  // false
```

Perl's `\Z` accepts only `\n` (or `\r\n` in builds with that support) before end of
string. It does not accept `\r` alone, NEL, `\u2028`, or `\u2029`. JDK and Orbit are
therefore more permissive than Perl when input contains those characters.

Under `UNIX_LINES`, `\Z` accepts only `\n` before end of string. `\r`, NEL, `\u2028`,
and `\u2029` are not treated as terminators.

### MULTILINE: `\r\n` vs `\n\r`

Under `MULTILINE`, `^` passes after a line terminator and `$` passes before one. JDK
treats `\r\n` as an indivisible pair: `^` does not pass between `\r` and `\n`, so `\r\n`
produces exactly one logical line boundary, not two.

The sequence `\n\r` is not a recognised unit. Each character is an independent terminator,
producing two logical line boundaries — and therefore an empty line between them.

```java
Pattern p = Pattern.compile("^.*$", PatternFlag.MULTILINE);

// CRLF: two matches, no empty line.
Matcher m = p.matcher("line1\r\nline2");
m.find(); m.group(); // "line1"
m.find(); m.group(); // "line2"

// LF+CR: three matches; an empty line appears between \n and \r.
m = p.matcher("line1\n\rline2");
m.find(); m.group(); // "line1"
m.find(); m.group(); // ""       — empty line between the two independent terminators
m.find(); m.group(); // "line2"
```

Under `UNIX_LINES`, both `^` and `$` respond only to `\n`. `\r`, NEL, `\u2028`, and
`\u2029` are not line boundaries. `\r\n` is therefore treated as two characters, and only
`\n` marks the line end.

### Flag reference for terminator behaviour

| `PatternFlag` | Effect on terminator recognition |
|---|---|
| _(none)_ | JDK default: `\n`, `\r`, `\r\n` (as unit), `\u0085`, `\u2028`, `\u2029` |
| `UNIX_LINES` | Restricts to `\n` only; affects `.`, `$`, `\Z`, `^`, `$` under `MULTILINE`. Not a Perl-compat mode: stricter than Perl for anchors (`\r` is not a terminator at all). |
| `PERL_NEWLINES` | Dot excludes `\n` only; `\r` and `\r\n` remain valid anchor terminators. Closest to Perl's default behaviour. |
| `MULTILINE` | `^`/`$` match at every line boundary; does not change which characters are terminators |
| `DOTALL` | `.` matches every character; does not affect anchor behaviour |
| `UNIX_LINES` + `MULTILINE` | `^`/`$` match at every `\n`; all other terminators ignored |
| `DOTALL` + `UNIX_LINES` | `.` matches everything; anchors restricted to `\n` |

`MULTILINE` and `UNIX_LINES` are independent axes. `MULTILINE` controls how many
positions `$` and `^` can match. `UNIX_LINES` controls which characters count as
terminators at each of those positions. `DOTALL` is orthogonal to both: it affects only
dot matching and has no effect on anchor evaluation.

---

## The `REGEX_INCOMPAT` error code

`REGEX_INCOMPAT` identifies any mismatch between orbit's output and the JDK reference. It
is the canonical error code for compatibility failures.

In the current suite, `REGEX_INCOMPAT` surfaces as a JUnit assertion failure message. The
message format is:

```
Pattern = <pattern>
Data = <input>
Expected = <jdk-result>
Actual   = <orbit-result>
```

When the structured JSON report is implemented (see `docs/compat-test-spec.md`, section 5),
every failure record will carry `"code": "REGEX_INCOMPAT"` alongside the test class name,
method name, and timestamp.

---

## Success criteria

The build passes the compatibility gate when:

1. The pass rate is ≥ 90 %, computed as `passed / (passed + failed)`. Tests skipped via
   `Assumptions.assumeFalse` or `@Disabled` are excluded from both counts.
2. No new `REGEX_INCOMPAT` failure type appears that was not present in the previous build.

These thresholds are targets for CI enforcement once the JSON report and its Maven
Failsafe listener are implemented. Until then, the build fails only if an enabled test
fails an assertion.

The current suite runs 3135 tests across all suites: 2452 passed, 0 failed, 683 skipped.
See `STATUS.md` for the per-suite breakdown.

---

## Running the suite locally

**Prerequisites.** Java 21 or later. Maven 3.9 or later.

**All compatibility tests:**

```
mvn test -pl orbit-core
```

**Specific class:**

```
mvn test -pl orbit-core -Dtest=BasicRegexCompatTest
mvn test -pl orbit-core -Dtest=DotNetCompatTest
mvn test -pl orbit-core -Dtest=SplitWithDelimitersCompatTest
mvn test -pl orbit-core -Dtest=GraphemeCompatTest
mvn test -pl orbit-core -Dtest=UnicodeCaseFoldingCompatTest
mvn test -pl orbit-core -Dtest=PerlRegexCompatTest
mvn test -pl orbit-core -Dtest=RegExCompatTest
mvn test -pl orbit-core -Dtest=AdvancedMatchingCompatTest
mvn test -pl orbit-core -Dtest=NamedGroupsCompatTest
mvn test -pl orbit-core -Dtest=PatternStreamCompatTest
mvn test -pl orbit-core -Dtest=ImmutableMatchResultCompatTest
mvn test -pl orbit-core -Dtest=Re2CompatTest
mvn test -pl orbit-core -Dtest=POSIXASCIICompatTest
mvn test -pl orbit-core -Dtest=POSIXUnicodeCompatTest
```

**Including integration tests (Failsafe `verify` phase):**

```
mvn verify -pl orbit-core
```

**From the repository root (all modules):**

```
mvn test
```

---

## Reading the test output

JUnit 5 reports test results in three categories:

- **Passed** — orbit produced the same result as the JDK reference.
- **Failed** — orbit produced a different result. The assertion message identifies the
  pattern, input, expected value, and actual value.
- **Skipped/Aborted** — the pattern exercises an unimplemented feature or the test is
  annotated `@Disabled`. The skip reason is recorded in the report.

A representative failure message from `BasicRegexCompatTest`:

```
org.opentest4j.AssertionFailedError:
Pattern = (a|b)+c
Data = aabbc
Expected = true aabbc 1 b
Actual   = true aabbc 1 a
```

A representative skipped test message:

```
Assumption failed: Skipping unimplemented feature: \p{Lu}
```

---

## JSON report format

No JSON report is generated by the current suite. The planned report format, once
`CompatibilityFailure` and `CompatibilityReport` are implemented in
`orbit-core/src/test/java/com/orbit/compat/model/`, is:

```json
{
  "generatedAt": "2026-04-19T10:15:30Z",
  "totalTests": 3081,
  "passed": 2374,
  "failed": 0,
  "skipped": 707,
  "passRate": 1.0,
  "failures": []
}
```

The report will be written to `target/compat-report.json` after the `verify` phase.
CI should be configured to fail the build when `passRate < 0.90`.

---

## Repository layout

```
orbit-core/
├── src/main/java/com/orbit/api/
│   ├── Pattern.java          // compile(), matcher(), split(), matches(), quote()
│   ├── Matcher.java          // find(), matches(), group(), group(String), start(), end(),
│   │                         //   results(), namedGroups(), reset(), hitEnd(), region(),
│   │                         //   usePattern(), useAnchoringBounds(), useTransparentBounds()
│   ├── ErrorToken.java       // record: message, start, end
│   ├── MatchToken.java       // record: type, value, start, end
│   └── Token.java            // sealed interface
└── src/test/java/com/orbit/
    ├── PatternCompatibilityIT.java              // side-by-side smoke test
    └── compat/
        ├── BasicRegexCompatTest.java            // data-driven from jdk-tests/TestCases.txt
        ├── DotNetCompatTest.java                // .NET-specific features (45 cases)
        ├── SplitWithDelimitersCompatTest.java   // split semantics (54 cases)
        ├── GraphemeCompatTest.java              // \X; parameterized test disabled
        ├── UnicodeCaseFoldingCompatTest.java    // case folding; 9/13 pass
        ├── NamedGroupsCompatTest.java           // named groups; 34/34 pass
        ├── ImmutableMatchResultCompatTest.java  // immutable result; 8/9 pass
        ├── PatternStreamCompatTest.java         // results() streaming; 45/47 pass
        ├── AppendReplaceCompatTest.java         // append/replace; 22/38 pass
        ├── UnicodeRegexCompatTest.java          // Unicode properties; 59/64 pass
        ├── AdvancedMatchingCompatTest.java      // JDK advanced; 103/111 pass
        ├── RegExCompatTest.java                 // JDK regression suite; 120/122 pass
        ├── Re2CompatTest.java                   // RE2_COMPAT mode; 11/11 pass
        ├── BranchResetTest.java                 // (?|...) branch reset; 8/8 pass
        ├── CaseInsensitiveLiteralTest.java      // CI literal encoding; 14/14 pass
        ├── KeepAssertionTest.java               // \K keep assertion; 7/7 pass
        ├── POSIXASCIICompatTest.java            // POSIX ASCII classes; 14/14 pass
        ├── POSIXUnicodeCompatTest.java          // POSIX Unicode classes; 20/35 pass
        └── PerlRegexCompatTest.java             // Perl re_tests; 1351/1974 pass

jdk-tests/
├── TestCases.txt               // pattern/input/expected triplets for BasicRegexCompatTest
├── GraphemeTestCases.txt       // grapheme break test data for GraphemeCompatTest
├── SplitWithDelimitersTest.java
├── CaseFoldingTest.java
├── NamedGroupsTests.java
├── PatternStreamTest.java
├── POSIX_ASCII.java            // not yet adapted
├── POSIX_Unicode.java          // not yet adapted
└── ImmutableMatchResultTest.java

orbit-core/src/test/resources/perl-tests/
└── re_tests                    // Perl source distribution test data
```
