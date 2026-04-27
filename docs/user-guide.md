---
title: User Guide
nav_order: 2
---
# User Guide

## Basic usage

`Pattern.compile()` compiles a regex. `Pattern.matcher()` returns a `Matcher`.
`Matcher.find()` searches forward through the input.

```java
import com.orbit.api.Pattern;
import com.orbit.api.Matcher;

Pattern p = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]{2,4}");
Matcher m = p.matcher("contact: hello@example.com, info@orbit.io");

while (m.find()) {
    System.out.println(m.group()); // "hello@example.com", then "info@orbit.io"
}
```

`Matcher.matches()` checks whether the entire input matches:

```java
Pattern p = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
Matcher m = p.matcher("2026-03-23");
System.out.println(m.matches()); // true

Matcher m2 = p.matcher("2026-03-23 extra");
System.out.println(m2.matches()); // false
```

---

## Numbered capture groups

Groups are 1-based in `Matcher`. Group 0 is the whole match.

```java
Pattern p = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
Matcher m = p.matcher("Event on 2026-03-23.");
if (m.find()) {
    System.out.println(m.group(0)); // "2026-03-23"
    System.out.println(m.group(1)); // "2026"
    System.out.println(m.group(2)); // "03"
    System.out.println(m.group(3)); // "23"
    System.out.println(m.start(1)); // 9
    System.out.println(m.end(1));   // 13
}
```

A group that did not participate in the match (optional group that was skipped) returns
null from `group(int)` and −1 from `start(int)` and `end(int)`.

---

## Named capture groups

Named groups use `(?<name>...)` syntax. Access them with `group(String)`, `start(String)`,
and `end(String)`.

```java
Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
Matcher m = p.matcher("Filed: 2026-03-23");
if (m.find()) {
    System.out.println(m.group("year"));   // "2026"
    System.out.println(m.group("month")); // "03"
    System.out.println(m.group("day"));   // "23"
    System.out.println(m.start("year"));  // 7
    System.out.println(m.end("day"));     // 17
}
```

`group(String)` throws `IllegalArgumentException` if the name does not correspond to a
group in the pattern.

---

## PatternFlag usage

```java
import com.orbit.util.PatternFlag;

// Case-insensitive matching.
Pattern p = Pattern.compile("hello", PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("HELLO world").find()); // true

// MULTILINE: ^ and $ match at line boundaries.
Pattern q = Pattern.compile("^\\w+", PatternFlag.MULTILINE);
Matcher m = q.matcher("first\nsecond\nthird");
while (m.find()) {
    System.out.println(m.group()); // "first", "second", "third"
}

// DOTALL: . matches \n.
Pattern r = Pattern.compile("a.b", PatternFlag.DOTALL);
System.out.println(r.matcher("a\nb").find()); // true
```

Multiple flags:

```java
Pattern p = Pattern.compile(
    "hello",
    PatternFlag.CASE_INSENSITIVE,
    PatternFlag.MULTILINE
);
```

### LITERAL flag

`PatternFlag.LITERAL` treats the entire pattern string as a sequence of literal
characters. No metacharacter has special meaning: `(`, `)`, `[`, `]`, `*`, `+`, `?`,
`|`, `^`, `$`, `.`, and `\` are all matched verbatim.

```java
// Match the literal string "a+b*c?".
Pattern p = Pattern.compile("a+b*c?", PatternFlag.LITERAL);
System.out.println(p.matcher("a+b*c?").find()); // true
System.out.println(p.matcher("abc").find());     // false — no literal match

// Anchors and other metacharacters are literal.
Pattern q = Pattern.compile("^cat$", PatternFlag.LITERAL, PatternFlag.MULTILINE);
System.out.println(q.matcher("^cat$").find());           // true
System.out.println(q.matcher("abc^cat$def").find());     // true — substring match
System.out.println(q.matcher("cat").find());             // false — no "^" or "$"
```

`LITERAL` can be combined with `CASE_INSENSITIVE` and `UNICODE_CASE`. All other flags
(`MULTILINE`, `DOTALL`, `COMMENTS`, `UNIX_LINES`) are ignored because the resulting AST
contains no flag-sensitive nodes.

```java
// Case-insensitive literal match.
Pattern p = Pattern.compile("Hello!", PatternFlag.LITERAL, PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("HELLO!").find()); // true
```

`Pattern.quote(String s)` produces the same behaviour as `LITERAL` by wrapping `s` in
`\Q...\E`. Both approaches are equivalent in their match results.

### Dot and line terminators

By default, `.` excludes five characters: `\n`, `\r`, `` (NEL), ` ` (LS), and
` ` (PS). This differs from Perl, where `.` excludes only `\n`.

`UNIX_LINES` restricts all terminator recognition to `\n` only. Under this flag, `.`
excludes only `\n` — which coincides with Perl's default dot behaviour — but `UNIX_LINES`
is not a Perl-compatibility mode. For anchors (`$`, `^`, `\Z`), `UNIX_LINES` is stricter
than Perl: `\r` is ignored entirely, whereas Perl still treats `\r` as a line terminator.

```java
// Default: . does not match NEL ().
Pattern.compile("....").matcher("test").find();  // true  — matches "test"
Pattern.compile(".....").matcher("test").find(); // false — fifth char is NEL

// UNIX_LINES: . excludes only \n; NEL is matchable.
Pattern.compile(".....", PatternFlag.UNIX_LINES).matcher("test").find(); // true

// DOTALL: . matches everything.
Pattern.compile(".....").matcher("test").find(); // false
Pattern.compile(".....", PatternFlag.DOTALL).matcher("test").find();     // true
```

For full details of `\r\n` CRLF handling, MULTILINE line-boundary counting, and the
difference between JDK and Perl terminator sets, see
`docs/compatibility.md` — "Line terminator semantics".

---

## Matching modes and flags

Orbit's matching behaviour is controlled by `PatternFlag` values passed to `Pattern.compile`. The flags form a hierarchy of semantic layers, ordered from most to least restrictive:

```
RE2_COMPAT  ⊂  Orbit default  ⊂  +UNICODE  ⊂  +PERL_NEWLINES
```

### Orbit default (no flags)

With no flags, Orbit targets JDK 21 behavioural parity. `\d` matches `[0-9]`, `\w` matches `[A-Za-z0-9_]`, dot excludes five characters (`\n`, `\r`, ``, ` `, ` `), and `$` matches before any of those same five.

Orbit's parser is a strict superset of JDK's: it also accepts atomic groups `(?>...)`, balancing groups `(?<n-m>...)`, conditional subpatterns `(?(cond)yes|no)`, and Python-style named groups `(?P<name>...)` — all of which JDK rejects with `PatternSyntaxException`. This is not a separate opt-in mode; Orbit's parser is simply broader. No existing `java.util.regex` program uses these constructs (they would have thrown), so there is no compatibility risk.

### Active flags

These flags are wired and take effect immediately:

```java
import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import com.orbit.util.PatternFlag;

// CASE_INSENSITIVE: ASCII letters match regardless of case.
Pattern p = Pattern.compile("nacl", PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("NaCl").matches()); // true

// MULTILINE: ^ and $ match at each line boundary, not only at the
// start and end of the whole input.
Pattern q = Pattern.compile("^\\w+", PatternFlag.MULTILINE);
Matcher m = q.matcher("first\nsecond\nthird");
while (m.find()) {
    System.out.println(m.group()); // "first", "second", "third"
}

// DOTALL: . matches every character including newline.
Pattern r = Pattern.compile("begin.end", PatternFlag.DOTALL);
System.out.println(r.matcher("begin\nend").find()); // true

// UNIX_LINES: . excludes \n only (not \r, NEL, LS, PS).
// $ and ^ also recognise \n only.
// Note: \r is dropped entirely — it is not a terminator under this flag.
Pattern s = Pattern.compile(".*end$", PatternFlag.UNIX_LINES);
System.out.println(s.matcher("lineend").find()); // true ( matchable)
System.out.println(s.matcher("line\nend").find());     // false (\n not matchable by .)
```

### RE2_COMPAT

`PatternFlag.RE2_COMPAT` restricts the engine to the RE2 subset, which guarantees O(n) execution. `Pattern.compile` throws `PatternSyntaxException` at compile time — never at match time — for any of the following constructs:

- Backreferences: `\1`–`\99`, `\k<name>`
- Lookahead: `(?=...)`, `(?!...)`
- Lookbehind: `(?<=...)`, `(?<!...)`
- Atomic groups: `(?>...)`
- Possessive quantifiers: `x*+`, `x++`, `x?+`, `x{n,m}+`
- Balancing groups and conditional subpatterns
- Character class intersection: `[a&&[b]]`
- Incompatible flags: `COMMENTS`, `LITERAL`, `UNICODE_CASE`, `UNIX_LINES`, `CANON_EQ`

Under `RE2_COMPAT`, dot `.` excludes only `\n`, and `^`/`$` recognise only `\n` as a line terminator. The engine is forced to `PikeVmEngine` (NFA semantics matching RE2's behaviour).

```java
import com.orbit.api.Pattern;
import com.orbit.util.PatternFlag;

// Backreference rejected at compile time.
try {
    Pattern p = Pattern.compile("(\\w+)\\1", PatternFlag.RE2_COMPAT);
} catch (java.util.regex.PatternSyntaxException e) {
    System.err.println(e.getMessage()); // "backreference not supported in RE2_COMPAT mode"
}

// Lookahead rejected at compile time.
try {
    Pattern p = Pattern.compile("foo(?=bar)", PatternFlag.RE2_COMPAT);
} catch (java.util.regex.PatternSyntaxException e) {
    System.err.println(e.getMessage()); // "lookahead not supported in RE2_COMPAT mode"
}

// Valid RE2 pattern compiles and matches normally.
Pattern p = Pattern.compile("[A-Za-z]+\\d+", PatternFlag.RE2_COMPAT);
System.out.println(p.matcher("abc123").find()); // true
```

### UNICODE

`PatternFlag.UNICODE` widens character class semantics so that `\w`, `\d`, `\s`, `\b`, and POSIX classes use Unicode properties rather than ASCII ranges. It also extends case folding to cover Turkish dotless-i, long-s/ß, and Kelvin/Ångström. `UNICODE` implies `UNICODE_CASE`.

| Shorthand | Default | With `UNICODE` |
|---|---|---|
| `\w` | `[A-Za-z0-9_]` | Unicode letter, digit, or connector punctuation |
| `\d` | `[0-9]` | Unicode decimal digit (`\p{Nd}`) |
| `\s` | ASCII whitespace | `\p{Z}` union ASCII whitespace |
| `\b` | ASCII word chars + NON_SPACING_MARK | `Character.isLetterOrDigit()` or `'_'` |
| `[:alpha:]` | ASCII only | `\p{L}` |

```java
import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import com.orbit.util.PatternFlag;

// \w matches Unicode letters without UNICODE.
Pattern ascii = Pattern.compile("\\w+");
System.out.println(ascii.matcher("café").find());  // true — matches "caf", stops at 'é'
System.out.println(ascii.matcher("café").group()); // "caf"

// With UNICODE, \w covers all Unicode letters.
Pattern uni = Pattern.compile("\\w+", PatternFlag.UNICODE);
System.out.println(uni.matcher("café").find());  // true — matches "café"
System.out.println(uni.matcher("café").group()); // "café"

// \d matches Unicode decimal digits.
Pattern digits = Pattern.compile("\\d+", PatternFlag.UNICODE);
System.out.println(digits.matcher("١٢٣").matches()); // true — Arabic-Indic digits

// Extended case folding: dotless-i.
Pattern fold = Pattern.compile("\\u0130", PatternFlag.UNICODE, PatternFlag.CASE_INSENSITIVE);
System.out.println(fold.matcher("i").find()); // true — U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE
```

`UNICODE` does not change dot or anchor behaviour; use `PERL_NEWLINES` for that.

### PERL_NEWLINES

`PatternFlag.PERL_NEWLINES` matches Perl's default newline semantics: dot and `$` exclude `\n` only, and `\r` remains a line terminator for anchors. This differs from `UNIX_LINES`, which drops `\r` from all terminator recognition:

| Behaviour | Default | `UNIX_LINES` | `PERL_NEWLINES` |
|---|---|---|---|
| `.` excludes | `\n \r     ` | `\n` only | `\n` only |
| `$` matches before | same 5 chars | `\n` only | `\n` only |
| `\r` as line terminator for `$` | yes | **no** | **yes** |
| `\r\n` as unit | yes | no | yes |

```java
import com.orbit.api.Pattern;
import com.orbit.util.PatternFlag;

// Default: \r is a line terminator, so . does not match it.
Pattern def = Pattern.compile(".+");
System.out.println(def.matcher("a\rb").find());
System.out.println(def.matcher("a\rb").group()); // "a"

// PERL_NEWLINES: . excludes only \n — \r is matchable by dot.
Pattern perl = Pattern.compile(".+", PatternFlag.PERL_NEWLINES);
System.out.println(perl.matcher("a\rb").find());
System.out.println(perl.matcher("a\rb").group()); // "a\rb"

// PERL_NEWLINES + MULTILINE: ^ and $ recognise \r, \n, and \r\n.
Pattern ml = Pattern.compile("^\\w+$", PatternFlag.PERL_NEWLINES, PatternFlag.MULTILINE);
Matcher m = ml.matcher("first\rsecond\r\nthird");
while (m.find()) {
    System.out.println(m.group()); // "first", "second", "third"
}
```

---

## Error handling

### PatternSyntaxException

`PatternSyntaxException` (from `java.util.regex`) is thrown inside `Pattern.compile()` when the pattern string contains a syntax error. It is always a compile-time exception — the match methods (`find`, `matches`, `lookingAt`) never throw it.

```java
try {
    Pattern.compile("(unclosed");
} catch (java.util.regex.PatternSyntaxException e) {
    System.err.println(e.getDescription()); // "Unclosed group"
    System.err.println(e.getIndex());       // character position in pattern
    System.err.println(e.getPattern());     // "(unclosed"
}
```

Under `RE2_COMPAT`, `Pattern.compile` also throws `PatternSyntaxException` for syntactically valid patterns that use backtracking-requiring constructs — backreferences, lookaround, possessives, atomic groups, balancing groups, conditionals, character class intersection, and the incompatible flags listed in the `RE2_COMPAT` section above.

### MatchTimeoutException

`MatchTimeoutException` (from `com.orbit.engine`) is thrown at match time, not compile time. It signals that a pattern routed to `BoundedBacktrackEngine` exceeded its backtrack budget (default: 1,000,000 operations). It is wrapped in a `RuntimeException`.

Only patterns with `engineHint() == EngineHint.NEEDS_BACKTRACKER` can produce this exception. Patterns routed to `OnePassDfaEngine`, `LazyDfaEngine`, or `PikeVmEngine` run in guaranteed linear time and never time out.

```java
import com.orbit.engine.MatchTimeoutException;

Pattern p = Pattern.compile("(a+)+b"); // catastrophic backtracking pattern
Matcher m = p.matcher("a".repeat(25)); // no trailing 'b'

try {
    m.find();
} catch (RuntimeException e) {
    if (e.getCause() instanceof MatchTimeoutException mte) {
        System.err.println("Budget exhausted. Input length: " + mte.getInputLength()
            + ", budget: " + mte.getBudget());
    }
}
```

### IllegalArgumentException

`group(String name)` throws `IllegalArgumentException` when `name` does not correspond to any capturing group in the pattern.

`group(int index)`, `start(int index)`, and `end(int index)` throw `IllegalArgumentException` when `index` is negative or greater than the number of capturing groups.

```java
Pattern p = Pattern.compile("(?<year>\\d{4})");
Matcher m = p.matcher("2026");
m.find();
m.group("year");    // "2026"
m.group("month");  // IllegalArgumentException: no group named "month"
m.group(2);        // IllegalArgumentException: index 2 out of range (only group 0 and 1 exist)
```

### IllegalStateException

`toMatchResult()`, `group()`, `start()`, `end()`, and related methods throw `IllegalStateException` when called before any match attempt or after a failed match (i.e., when `find()` or `matches()` returned `false`).

```java
Pattern p = Pattern.compile("\\d+");
Matcher m = p.matcher("abc");

// No match attempt yet — throws IllegalStateException.
// m.group();

boolean found = m.find(); // false — "abc" contains no digits
if (!found) {
    // m.group() would throw IllegalStateException here too.
    System.out.println("no match");
}
```

---

## Inspecting engine selection

`pattern.engineHint()` returns the `EngineHint` assigned at compile time. This is fixed
and does not change between calls.

```java
import com.orbit.util.EngineHint;

Pattern a = Pattern.compile("[a-z]+");
System.out.println(a.engineHint()); // ONE_PASS_SAFE or DFA_SAFE

Pattern b = Pattern.compile("(\\w+)\\s+(\\w+)");
System.out.println(b.engineHint()); // DFA_SAFE

Pattern c = Pattern.compile("(a+)\\1");
System.out.println(c.engineHint()); // NEEDS_BACKTRACKER
```

The engine is transparent to the caller — `find()`, `group()`, and all other methods
work identically regardless of which engine runs.

---

## Handling MatchTimeoutException

Patterns with `NEEDS_BACKTRACKER` use `BoundedBacktrackEngine`, which enforces a budget
of 1,000,000 operations. When the budget is exceeded, the engine throws `RuntimeException`
wrapping `MatchTimeoutException`. This protects against ReDoS.

```java
import com.orbit.engine.MatchTimeoutException;
import com.orbit.util.EngineHint;

// Backreference forces NEEDS_BACKTRACKER.
Pattern p = Pattern.compile("(a+)\\1");
Matcher m = p.matcher("a".repeat(30));            // 30 'a' chars, no repeated group

try {
    boolean found = m.find();
    System.out.println("found: " + found);
} catch (RuntimeException e) {
    if (e.getCause() instanceof MatchTimeoutException mte) {
        System.err.println("Budget exhausted on input of length "
            + mte.getInputLength()
            + ", budget was " + mte.getBudget());
    } else {
        throw e;
    }
}
```

To avoid triggering the budget on untrusted input, verify the engine hint before applying
the pattern:

```java
if (p.engineHint() == EngineHint.NEEDS_BACKTRACKER) {
    // Do not apply to arbitrary untrusted input.
}
```

---

## Thread safety

`Pattern` is immutable and thread-safe. One compiled `Pattern` can be shared across any
number of threads.

`Matcher` is not thread-safe. Create one `Matcher` per thread.

```java
// Correct: shared Pattern, per-thread Matcher.
Pattern p = Pattern.compile("(foo|bar)+");

Runnable task = () -> {
    Matcher m = p.matcher("foobarfoo");
    while (m.find()) {
        System.out.println(m.group());
    }
};

Thread[] threads = new Thread[20];
for (int i = 0; i < threads.length; i++) {
    threads[i] = new Thread(task);
    threads[i].start();
}
```

---

## Capture groups with the DFA engines

Capturing groups work regardless of which engine runs. For `ONE_PASS_SAFE` patterns,
captures are recorded by the `OnePassDfaEngine` during the table walk at no additional
cost. For `DFA_SAFE` patterns with capturing groups, `LazyDfaEngine` determines the match
boundaries and then `PikeVmEngine` re-runs on the matched substring to extract group
values (hybrid mode). The result is identical; the hybrid step is internal.

```java
// DFA_SAFE with captures — hybrid mode applies internally.
Pattern p = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
Matcher m = p.matcher("Event on 2026-03-22.");
if (m.find()) {
    System.out.println(m.group(1)); // "2026"
    System.out.println(m.group(2)); // "03"
    System.out.println(m.group(3)); // "22"
}
```

---

## Splitting

```java
Pattern p = Pattern.compile(",\\s*");
String[] parts = p.split("one, two,   three");
// ["one", "two", "three"]
```

The static form:

```java
String[] parts = Pattern.split(",\\s*", "one, two, three");
```

With a limit:

```java
// limit=2: at most 2 parts; remainder is unsplit tail.
String[] parts = Pattern.split(",", "a,b,c,d", 2);
// ["a", "b,c,d"]
```

`limit == 0` (default) drops trailing empty strings. `limit < 0` keeps them.

---

## Replacing

```java
Pattern p = Pattern.compile("\\b\\d+\\b");
Matcher m = p.matcher("there are 3 cats and 12 dogs");
String result = m.replaceAll("N");
// "there are N cats and N dogs"
```

For incremental replacement, use `appendReplacement` and `appendTail`. Both `StringBuilder`
(Java 9+) and `StringBuffer` (Java 1.4+) overloads are available:

```java
Pattern p = Pattern.compile("(\\w+)");
Matcher m = p.matcher("one two three");
StringBuilder sb = new StringBuilder();
while (m.find()) {
    m.appendReplacement(sb, m.group(1).toUpperCase());
}
m.appendTail(sb);
System.out.println(sb); // "ONE TWO THREE"
```

The `StringBuffer` form has identical semantics and is provided for compatibility with
code written against `java.util.regex.Matcher` prior to Java 9.

---

## Resetting a Matcher

```java
Matcher m = p.matcher(input);
m.find();               // first call: finds first match
m.find();               // second call: advances past first match
m.reset();              // reset: next find() starts from position 0 again
m.find();               // finds first match again
```

---

## DotNet-extended syntax

The following constructs are implemented and routed to `BoundedBacktrackEngine`:

**Balancing groups** (match nested pairs):

```java
// Match balanced parentheses using balancing groups.
Pattern p = Pattern.compile(
    "\\((?:[^()]*(?<open>\\())*(?:[^()]*(?<-open>\\)))*[^()]*\\)(?(open)(?!))");
Matcher m = p.matcher("(a(b)c)");
System.out.println(m.find()); // true
```

**Conditional subpatterns**:

```java
// Match "ab" or "b" (with optional leading "a" tracked via group 1).
Pattern p = Pattern.compile("(a)?(?(1)b|c)");
Matcher m = p.matcher("ab");
System.out.println(m.find()); // true — group 1 set, so 'b' branch taken
```

**Atomic groups** (no backtracking into the group once it matches):

```java
Pattern p = Pattern.compile("(?>a+)b");
System.out.println(p.matcher("aaab").find()); // true
System.out.println(p.matcher("aaa").find());  // false — a+ is atomic, cannot retry
```

**Possessive quantifiers**:

```java
Pattern p = Pattern.compile("\\w++");    // possessive: no backtrack
System.out.println(p.matcher("hello").find()); // true
```

---

## Unicode properties

```java
Pattern p = Pattern.compile("\\p{Lu}");          // uppercase letter
Pattern q = Pattern.compile("\\p{InGreek}");     // Greek block character
Pattern r = Pattern.compile("\\p{IsLatin}");     // Latin script character
Pattern s = Pattern.compile("\\P{Alpha}");       // not alphabetic (complement)
```

Coverage is BMP only (U+0000–U+FFFF). `\X` (grapheme clusters) and `(?u` (Unicode
character class mode) are not implemented.

---

## POSIX bracket classes

POSIX bracket class syntax places a named class inside a bracket expression using the form
`[[:name:]]`. Orbit's POSIX classes are Unicode-aware by default: `[[:alpha:]]` matches
any Unicode letter, not only the ASCII range `[A-Za-z]`.

| Name | Matches |
|---|---|
| `alpha` | Unicode letters (`Character.isLetter`) |
| `alnum` | Unicode letters and digits (`Character.isLetterOrDigit`) |
| `digit` | Unicode decimal digits (`Character.isDigit`) |
| `upper` | Unicode uppercase letters (`Character.isUpperCase`) |
| `lower` | Unicode lowercase letters (`Character.isLowerCase`) |
| `space` | Unicode whitespace (`Character.isWhitespace` plus non-breaking space variants) |
| `blank` | Space and tab only (U+0020 and U+0009) |
| `ascii` | Code points U+0000–U+007F |
| `word` | Letters, digits, underscore, and connector punctuation |
| `graph` | Printable non-whitespace characters |
| `print` | Printable characters, including space |
| `punct` | Punctuation characters |
| `cntrl` | Control characters |
| `xdigit` | Hexadecimal digits (0–9, A–F, a–f) |

`[[:ascii:]]` is the only class that is strictly ASCII-only by definition. All others
delegate to `java.lang.Character` methods and cover the full Unicode BMP.

The negation form `[[:^name:]]` matches any character that the named class does not match.

### Basic use

```java
// [[:alpha:]]+ matches a run of Unicode letters, including non-ASCII.
Pattern p = Pattern.compile("[[:alpha:]]+");
Matcher m = p.matcher("café résumé");
while (m.find()) {
    System.out.println(m.group()); // "café", then "résumé"
}
```

### Negation

```java
// [[:^digit:]] matches any character that is not a Unicode decimal digit.
Pattern p = Pattern.compile("[[:^digit:]]+");
Matcher m = p.matcher("abc123def");
while (m.find()) {
    System.out.println(m.group()); // "abc", then "def"
}
```

### Combining with other bracket expression elements

A bracket expression can mix POSIX classes with literal characters, ranges, and `\p{}`
properties. All elements are unioned.

```java
// [[:alpha:]_] matches any Unicode letter or underscore.
Pattern p = Pattern.compile("[[:alpha:]_]+");
System.out.println(p.matcher("_café_").find());  // true
System.out.println(p.matcher("_café_").group()); // "_café_"

// [[:upper:][:digit:]] matches any uppercase letter or digit.
Pattern q = Pattern.compile("[[:upper:][:digit:]]+");
System.out.println(q.matcher("A1B2").find());  // true
System.out.println(q.matcher("A1B2").group()); // "A1B2"
```

### Case-insensitive matching with non-ASCII

Under `CASE_INSENSITIVE`, a POSIX class expands to include both cases of every matched
character. Non-ASCII characters are case-expanded using Unicode mappings.

```java
// [[:lower:]] with CASE_INSENSITIVE matches uppercase non-ASCII letters.
// Ā (U+0100, Latin Capital Letter A with Macron) is the uppercase form of ā.
Pattern p = Pattern.compile("[[:lower:]]", PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("Ā").find()); // true — Ā is the uppercase of a [:lower:] char
System.out.println(p.matcher("ā").find()); // true — ā is directly in [:lower:]
```

---

## Supported expression syntax

This reference covers every construct the engine accepts. It is organised by category.

### Quantifiers

| Syntax | Meaning |
|---|---|
| `*` | Zero or more (greedy) |
| `*?` | Zero or more (lazy) |
| `*+` | Zero or more (possessive — no backtracking) |
| `+` | One or more (greedy) |
| `+?` | One or more (lazy) |
| `++` | One or more (possessive) |
| `?` | Zero or one (greedy) |
| `??` | Zero or one (lazy) |
| `?+` | Zero or one (possessive) |
| `{n}` | Exactly n times |
| `{n,}` | At least n times (greedy) |
| `{n,}?` | At least n times (lazy) |
| `{n,}+` | At least n times (possessive) |
| `{n,m}` | Between n and m times (greedy) |
| `{n,m}?` | Between n and m times (lazy) |
| `{n,m}+` | Between n and m times (possessive) |

### Anchors

| Syntax | Meaning |
|---|---|
| `^` | Start of line (start of string without `MULTILINE`) |
| `$` | End of line (end of string without `MULTILINE`) |
| `\A` | Start of string (never affected by `MULTILINE`) |
| `\z` | End of string |
| `\Z` | End of string or before a final newline |
| `\b` | Word boundary (between `\w` and `\W`, or at string edges) |
| `\B` | Non-word boundary |
| `\G` | Position where the last match ended (chaining matches) |

### Character class shortcuts

| Syntax | Matches |
|---|---|
| `.` | Any character except newline (with `DOTALL`: any character) |
| `\d` | Decimal digit `[0-9]` |
| `\D` | Non-digit `[^\d]` |
| `\w` | Word character `[a-zA-Z0-9_]` |
| `\W` | Non-word character `[^\w]` |
| `\s` | Whitespace (space, tab, newline, carriage return, form feed, vertical tab) |
| `\S` | Non-whitespace |
| `\h` | Horizontal whitespace (space, tab, and Unicode horizontal spaces) |
| `\H` | Non-horizontal whitespace |
| `\v` | Vertical whitespace (newline, carriage return, form feed, vertical tab, `\x85`, ` `, ` `) |
| `\V` | Non-vertical whitespace |
| `\R` | Any line-break sequence (LF, CR, CRLF, and Unicode line terminators) |
| `\N` | Any character except newline (unaffected by `DOTALL`) |

### Escape sequences

| Syntax | Character |
|---|---|
| `\t` | Tab (U+0009) |
| `\n` | Line feed (U+000A) |
| `\r` | Carriage return (U+000D) |
| `\f` | Form feed (U+000C) |
| `\a` | Bell (U+0007) |
| `\e` | Escape (U+001B) |
| `\cX` | Control character (e.g. `\cK` = vertical tab) |
| `\xHH` | Character with hex value HH (exactly two hex digits) |
| `\x{HH…}` | Character with hex value (any number of hex digits; BMP only) |
| `\0OOO` | Character with octal value OOO |
| `\N{NAME}` | Unicode named character (e.g. `\N{LATIN SMALL LETTER A}` = `a`; BMP only) |
| `\Q…\E` | Literal quoting — all characters between `\Q` and `\E` are treated as literals |

### Character classes (bracket expressions)

| Syntax | Meaning |
|---|---|
| `[abc]` | Any of a, b, or c |
| `[^abc]` | Any character except a, b, or c |
| `[a-z]` | Character range |
| `[[:alpha:]]` | POSIX class inside brackets — Unicode-aware by default; see [POSIX bracket classes](#posix-bracket-classes) for the full list of names |
| `[[:^digit:]]` | Negated POSIX class — matches any character not in the named class |
| `[\p{Lu}]` | Unicode property inside a bracket expression |
| `[a&&b]` | Intersection — characters in both a and b |

### Groups and captures

| Syntax | Meaning |
|---|---|
| `(pattern)` | Capturing group (1-based; group 0 is the whole match) |
| `(?:pattern)` | Non-capturing group |
| `(?<name>pattern)` | Named capturing group |
| `(?P<name>pattern)` | Named capturing group (Python/PCRE syntax) |
| `(?'name'pattern)` | Named capturing group (Perl single-quote syntax) |
| `(?>pattern)` | Atomic group — no backtracking into the group once it matches |
| `(?|pattern)` | Branch reset group — all alternatives share the same group numbers |

### Backreferences

| Syntax | Meaning |
|---|---|
| `\1` … `\99` | Numbered backreference (up to the number of capturing groups in the pattern) |
| `\k<name>` | Named backreference |
| `\k{name}` | Named backreference (alternative syntax) |
| `\k'name'` | Named backreference (Perl single-quote syntax) |
| `\g{N}` | Relative or absolute backreference by number |
| `(?P=name)` | Named backreference (PCRE/Python syntax) |

Multi-digit backreferences follow JDK disambiguation rules. `\11` is parsed as a reference
to group 11 when the pattern contains at least 11 capturing groups; otherwise the parser
backs off to `\1` (reference to group 1) followed by the literal digit `1`.

```java
// 10 groups: \11 backs off to \1 + literal "1"
Pattern p10 = Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\11");
System.out.println(p10.matcher("abcdefghija1").find()); // true

// 11 groups: \11 refers to group 11
Pattern p11 = Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)\\11");
System.out.println(p11.matcher("abcdefghijkk").find()); // true
```

### Lookahead and lookbehind

| Syntax | Meaning |
|---|---|
| `(?=pattern)` | Positive lookahead — asserts pattern matches ahead without consuming |
| `(?!pattern)` | Negative lookahead |
| `(?<=pattern)` | Positive lookbehind — asserts pattern matches behind without consuming |
| `(?<!pattern)` | Negative lookbehind |

### Alternation and comments

| Syntax | Meaning |
|---|---|
| `a\|b` | Alternation — matches a or b (left to right) |
| `(?#text)` | Inline comment — text is ignored |
| `# text` | Line comment when `COMMENTS` flag is active |

#### COMMENTS mode (`(?x)` / `PatternFlag.COMMENTS`)

When `COMMENTS` is active:

- Unescaped ASCII whitespace (space, tab, form feed, carriage return, line feed) is
  ignored.
- An unescaped `#` begins a comment that extends to the next line terminator. The
  recognised terminators are `\n`, `\r`, `\r\n` (treated as one unit), `` (NEL),
  ` ` (LS), and ` ` (PS). This matches JDK semantics.
- `\#` is a literal `#` — it does not start a comment.

```java
Pattern p = Pattern.compile(
    "(?x)"
    + "\\d{4}   # year\n"
    + "-\\d{2}  # month\n"
    + "-\\d{2}  # day"
);
System.out.println(p.matcher("2026-03-30").matches()); // true

// \# is a literal hash, not a comment start.
Pattern q = Pattern.compile("a\\#b", PatternFlag.COMMENTS);
System.out.println(q.matcher("a#b").matches()); // true
```

### Inline flags

Flags can be toggled for a portion of a pattern using `(?flags)` or scoped with `(?flags:pattern)`.

| Syntax | `PatternFlag` equivalent | Effect |
|---|---|---|
| `(?i)` | `CASE_INSENSITIVE` | Case-insensitive matching |
| `(?m)` | `MULTILINE` | `^`/`$` match at line boundaries |
| `(?s)` | `DOTALL` | `.` matches newlines |
| `(?x)` | `COMMENTS` | Whitespace and `#` comments ignored in pattern |
| `(?-i)` | — | Disables case-insensitivity for the remainder of the enclosing scope |
| `(?-i:pattern)` | — | Scoped removal: disables case-insensitivity inside the group only |
| `(?i:pattern)` | — | Scoped addition: flag applies only inside the group |

`(?-i)` inside a pattern compiled with `PatternFlag.CASE_INSENSITIVE` correctly disables
case-insensitive matching for all atoms that follow it in the same scope. Atoms before
`(?-i)` continue to match case-insensitively.

```java
// 'a' is case-insensitive; after (?-i), 'b' is case-sensitive.
Pattern p = Pattern.compile("a(?-i)b", PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("Ab").matches()); // true
System.out.println(p.matcher("AB").matches()); // false — 'B' != 'b'
```

To limit the negation to a specific region, use the scoped form:

```java
// 'a' and 'c' are case-insensitive; 'b' is case-sensitive.
Pattern p = Pattern.compile("a(?-i:b)c", PatternFlag.CASE_INSENSITIVE);
System.out.println(p.matcher("AbC").matches()); // false — 'B' != 'b'
System.out.println(p.matcher("AbC".replace('B','b')).matches()); // true
```

### Conditional subpatterns (DotNet extension, requires backtracking engine)

| Syntax | Meaning |
|---|---|
| `(?(1)yes\|no)` | Matches `yes` if group 1 participated, else `no` |
| `(?(<name>)yes\|no)` | Same, by named group |
| `(?(condition)yes)` | Omit `no` branch to match empty string on false |

### Unicode properties

Properties use `\p{…}` (match) or `\P{…}` (complement). Coverage is BMP (U+0000–U+FFFF).

| Syntax | Matches |
|---|---|
| `\p{Lu}` | Uppercase letter |
| `\p{Ll}` | Lowercase letter |
| `\p{L}` | Any letter |
| `\p{N}` | Any number |
| `\p{P}` | Any punctuation |
| `\p{Z}` | Any separator |
| `\p{InGreek}` | Characters in the Greek Unicode block |
| `\p{IsLatin}` | Characters in the Latin script |
| `\p{Alpha}`, `\p{Digit}`, `\p{Lower}`, `\p{Upper}`, `\p{Space}`, `\p{Punct}`, `\p{Alnum}`, `\p{Graph}`, `\p{Print}`, `\p{Blank}`, `\p{Cntrl}`, `\p{XDigit}` | POSIX-style property names |
| `\p{javaLowerCase}`, `\p{javaUpperCase}`, `\p{javaWhitespace}`, `\p{javaMirrored}` | Java character class properties |

---

## Features not implemented

For the full list, see the "Won't fix / not yet scheduled" table in `ROADMAP.md`. Notable gaps:

- Supplementary code points (> U+FFFF) and `\X` grapheme clusters — engine is BMP-only.
- `PatternFlag.STREAMING`, `PatternFlag.CANON_EQ` — not yet designed.
- `Grammar` class (recursive grammars via `orbit-grammar` module) — not yet implemented.
