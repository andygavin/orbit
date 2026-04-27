---
title: API Reference
nav_order: 3
---
# API Reference

All classes are in `com.orbit.api` unless stated otherwise.

---

## Pattern

```java
public final class Pattern implements Serializable
```

Immutable compiled representation of a regular expression. Thread-safe. Obtain instances
via the `compile` factory methods.

A static `ConcurrentHashMap` of initial capacity 512 caches `CompileResult` and
`onePassSafe` by `(regex, flagSet)`. Subsequent calls with the same arguments return the
cached result without re-running the pipeline.

### Factory methods

```java
public static Pattern compile(String regex)
```

Compiles `regex` with default flags.

**Throws:**
- `NullPointerException` — `regex` is null.
- `RuntimeException` wrapping `com.orbit.parse.PatternSyntaxException` — invalid regex syntax.

---

```java
public static Pattern compile(String regex, PatternFlag... flags)
```

Compiles `regex` with the specified flags.

**Throws:**
- `NullPointerException` — `regex` or `flags` is null.
- `RuntimeException` wrapping `PatternSyntaxException` — invalid regex syntax.

### Instance methods

```java
public Matcher matcher(CharSequence input)
```

Returns a new `Matcher` for `input`. The matcher starts with `from=0`, `to=input.length()`.

**Throws:**
- `NullPointerException` — `input` is null.

Not thread-safe: each thread must call `matcher()` separately on the shared `Pattern`.

---

```java
public boolean isOnePassSafe()
```

Returns true if this pattern was classified `ONE_PASS_SAFE` during HIR analysis. A true
value means `OnePassDfaEngine` is used at match time (or `LazyDfaEngine` if the
`PrecomputedDfa` state limit was exceeded during compile).

---

```java
public EngineHint engineHint()
```

Returns the engine-selection hint assigned at compile time. One of `ONE_PASS_SAFE`,
`DFA_SAFE`, `PIKEVM_ONLY`, or `NEEDS_BACKTRACKER`.

---

```java
public String pattern()
```

Returns the original regex string passed to `compile`.

---

```java
public String toString()
```

Returns the original regex string. Equivalent to `pattern()`. Included for
`java.util.regex.Pattern` compatibility.

---

```java
public PatternFlag[] flags()
```

Returns a copy of the flags used to compile this pattern.

---

```java
public Prog prog()
```

Returns the compiled `Prog`. Intended for engine and benchmark use.

### Static utility methods

```java
public static boolean matches(String regex, CharSequence input)
```

Returns true if `input` matches `regex` in its entirety. Equivalent to
`Pattern.compile(regex).matcher(input).matches()`.

---

```java
public static String quote(String s)
```

Returns a copy of `s` with all regex metacharacters escaped with `\`. The returned string
matches `s` literally when used as a pattern.

---

```java
public static String[] split(String regex, CharSequence input)
```

Splits `input` around matches of `regex`. Equivalent to `split(regex, input, 0)`.

---

```java
public static String[] split(String regex, CharSequence input, int limit)
```

Splits `input` around matches of `regex`.

- `limit > 0` — caps the number of parts.
- `limit < 0` — keeps trailing empty strings.
- `limit == 0` — drops trailing empty strings.

An empty input returns an empty array. A zero-length match at position 0 drops the leading
empty token.

---

```java
public String[] split(CharSequence input)
public String[] split(CharSequence input, int limit)
```

Instance forms of `split`. Same semantics as the static forms.

---

## Matcher

```java
public class Matcher
```

Not thread-safe. Create one instance per thread per match operation via
`Pattern.matcher(CharSequence)`.

### Constructor

```java
public Matcher(Pattern pattern, CharSequence input)
```

Creates a matcher with `from=0`, `to=input.length()`. Direct construction is permitted;
`Pattern.matcher()` is the normal entry point.

### Matching methods

```java
public boolean matches()
```

Returns true if the entire region `[from, to)` matches the pattern exactly.

---

```java
public boolean find()
```

Finds the next match in the input starting from the current position.

- On the first call after construction or `reset()`, searches from position 0.
- After a zero-length match, advances `from` by 1 before the next call.
- Returns false when no further match exists.

---

```java
public boolean find(int start)
```

Resets the matcher and finds the next match starting at `start`.

- `start` must be in `[0, input.length()]`.

**Throws:**
- `IndexOutOfBoundsException` — `start` is out of range.

---

```java
public boolean lookingAt()
```

Matches the pattern against the beginning of the region `[from, to)` without requiring
the entire region to match.

---

```java
public Matcher reset()
```

Resets `from` to 0, `to` to `input.length()`, clears `lastResult`, and resets
`findFromStart` to true. Returns `this`.

### Position methods

```java
public int start()
public int end()
```

Return the start (inclusive) and end (exclusive) index of the previous match.

**Throws:**
- `IllegalStateException` — no match has been attempted.

---

```java
public int start(int group)
public int end(int group)
```

Return the start and end index of the subsequence captured by group `group`.

- `group == 0` addresses the overall match.
- Returns −1 if the group did not participate in the match.

**Throws:**
- `IllegalStateException` — no match is available.
- `IndexOutOfBoundsException` — `group` is out of range.

---

```java
public int start(String name)
public int end(String name)
```

Return the start and end index of the named capturing group `name`.

- Returns −1 if the group did not participate.

**Throws:**
- `IllegalStateException` — no match is available.
- `IllegalArgumentException` — `name` does not correspond to a named group.

### Region and bounds methods

```java
public Matcher region(int start, int end)
```

Sets the region of the input that `find`, `matches`, and `lookingAt` search. `start` and
`end` are indices into the full input string.

**Throws:**
- `IndexOutOfBoundsException` — `start` or `end` is out of range, or `start > end`.

---

```java
public int regionStart()
public int regionEnd()
```

Return the start and end of the current region.

---

```java
public Matcher useAnchoringBounds(boolean b)
public boolean hasAnchoringBounds()
```

Controls whether `^` and `$` (and `\A`, `\Z`, `\z`) match at the region boundaries
(`true`, the default) or only at the full input boundaries (`false`).

---

```java
public Matcher useTransparentBounds(boolean b)
public boolean hasTransparentBounds()
```

Controls whether lookaheads and lookbehinds can see beyond the region boundaries
(`true`) or are limited to the region (`false`, the default).

---

```java
public Matcher usePattern(Pattern newPattern)
```

Replaces this matcher's pattern without changing the input, position, or region. The
current match result is cleared.

**Throws:**
- `NullPointerException` — `newPattern` is null.

### Group content methods

```java
public String group()
```

Returns the input subsequence matched by the previous match.

**Throws:**
- `IllegalStateException` — no match has been attempted.

---

```java
public String group(int group)
```

Returns the subsequence captured by group `group`.

- `group == 0` returns the whole match.
- Returns null if the group did not participate.

**Throws:**
- `IllegalStateException` — no match is available.
- `IndexOutOfBoundsException` — `group` is out of range.

---

```java
public String group(String name)
```

Returns the subsequence captured by named group `name`.

- Returns null if the group did not participate.

**Throws:**
- `IllegalStateException` — no match is available.
- `IllegalArgumentException` — `name` does not correspond to a named group.

### Other methods

```java
public int groupCount()
```

Returns the number of capturing groups in this matcher's pattern. Fixed at compile time.

Backreferences use 1-based numbering: `\1` through `\99`. When parsing a multi-digit
backreference such as `\11`, the parser greedily reads digits and applies the JDK back-off
algorithm: if the accumulated number exceeds the group count, the last digit is pushed back
and treated as the next atom.

---

```java
public boolean hasMatch()
```

Returns true if a previous `find`, `matches`, or `lookingAt` call succeeded and the result
has not been cleared by `reset` or `usePattern`.

---

```java
public boolean hitEnd()
```

Returns true if the last match attempt reached the end of the input. When true, more input
could change a non-match into a match (or extend the current match).

---

```java
public String replaceAll(String replacement)
```

Replaces every match with `replacement`. Returns the input unchanged if no match is found.

**Throws:**
- `NullPointerException` — `replacement` is null.

---

```java
public String replaceAll(Function<MatchResult, String> replacer)
```

Replaces every match with the string returned by `replacer.apply(matchResult)`.

---

```java
public String replaceFirst(String replacement)
public String replaceFirst(Function<MatchResult, String> replacer)
```

Replace the first match only. Same semantics as the `replaceAll` forms.

---

```java
public Matcher appendReplacement(StringBuilder sb, String replacement)
public Matcher appendReplacement(StringBuffer sb, String replacement)
```

Appends the input from `lastAppendPos` to the start of the current match to `sb`, then
appends the expanded `replacement`. `$N` in `replacement` substitutes group N; `\$` is a
literal `$`. Advances `lastAppendPos` to `this.end()`.

`StringBuffer` is the Java 1.4 form; `StringBuilder` is the Java 9 form. Both are
provided for JDK compatibility.

**Throws:**
- `IllegalStateException` — no successful match since the last reset.
- `NullPointerException` — `sb` is null.
- `IllegalArgumentException` — `replacement` ends with an unescaped `\`.

---

```java
public StringBuilder appendTail(StringBuilder sb)
public StringBuffer appendTail(StringBuffer sb)
```

Appends the input from `lastAppendPos` to the end of the input to `sb`. Call once after
the final `appendReplacement`.

---

```java
public java.util.stream.Stream<MatchResult> results()
```

Returns a stream of non-overlapping `MatchResult` snapshots for all matches in the input,
in order. Equivalent to calling `find()` and `toMatchResult()` in a loop.

---

```java
public java.util.Map<String, Integer> namedGroups()
```

Returns a map from named group name to 1-based group index for all named groups in this
matcher's pattern.

---

```java
public java.util.regex.MatchResult toMatchResult()
```

Returns an immutable `java.util.regex.MatchResult` snapshot of the current match state.

**Throws:**
- `IllegalStateException` — no match has been performed.

---

## PatternFlag

```java
public enum PatternFlag   // in com.orbit.util
```

| Flag | What it changes |
|---|---|
| `CASE_INSENSITIVE` | Case-insensitive matching. Forces `NoopPrefilter` (literal prefilter is case-sensitive). |
| `MULTILINE` | `^` and `$` match at line boundaries as well as input boundaries. |
| `DOTALL` | `.` matches any character including all line terminators. |
| `UNICODE_CASE` | Case-insensitive matching uses Unicode rules (partially implemented). |
| `CANON_EQ` | Canonical equivalence. Not implemented. |
| `UNIX_LINES` | Restricts all line-terminator recognition to `\n` only. Affects `.`, `^`, `$`, and `\Z`; all other terminators (`\r`, ``, ` `, ` `) are treated as ordinary characters. |
| `LITERAL` | The pattern string is matched verbatim. No metacharacter has special meaning. Only `CASE_INSENSITIVE` and `UNICODE_CASE` are meaningful in combination; all other flags are ignored. Equivalent to wrapping the entire string in `\Q...\E`. |
| `COMMENTS` | Unescaped ASCII whitespace is ignored. An unescaped `#` begins a comment extending to the next line terminator. `\#` is a literal `#`. |
| `RE2_COMPAT` | RE2 compatibility mode. Rejects all non-RE2 constructs at compile time (backreferences, lookaround, possessives, atomic groups, `&&` intersection, incompatible flags). Dot and anchors use `\n`-only semantics. Forces `PikeVmEngine`. |
| `PERL_NEWLINES` | Perl newline semantics: dot excludes `\n` only; `\r` and `\r\n` remain line terminators for anchors. Distinct from `UNIX_LINES`. |
| `UNICODE` | Unicode-aware character classes: `\w`/`\d`/`\s`/`\b` use Unicode properties; POSIX classes cover Unicode ranges; case folding extended to dotless-i, long-s, Kelvin, Ångström. Implies `UNICODE_CASE`. Does not change dot or anchor behaviour. |
| `STREAMING` | Streaming mode. Not implemented. |
| `NO_PREFILTER` | Suppresses prefilter construction; forces `NoopPrefilter`. |

### Inline flag groups

The inline flag group `(?flags)`, without a body, applies additions and removals to the
active flag set for the remainder of the enclosing scope. `(?-i)` removes
`CASE_INSENSITIVE`; `(?i)` adds it. A pattern compiled with `PatternFlag.CASE_INSENSITIVE`
can restore case-sensitive matching for a suffix by embedding `(?-i)`.

The scoped form `(?flags:body)` limits the modification to `body` and restores the outer
flag state at the closing `)`.

```java
// 'a' matches case-insensitively; 'b' matches case-sensitively.
Pattern p = Pattern.compile("a(?-i)b", PatternFlag.CASE_INSENSITIVE);
p.matcher("Ab").matches()  // true  — 'A' matched with CASE_INSENSITIVE, 'b' matched exactly
p.matcher("AB").matches()  // false — 'B' does not equal 'b'
```

---

## EngineHint

```java
public enum EngineHint   // in com.orbit.util
```

Assigned at compile time by HIR analysis pass 8. Read-only at match time. Inspect it via
`Pattern.engineHint()`.

| Value | Engine | Conditions |
|---|---|---|
| `ONE_PASS_SAFE` | `OnePassDfaEngine` | No backreferences, no lookarounds; one-pass safety check passed. O(n) per match. |
| `DFA_SAFE` | `LazyDfaEngine` | No backreferences, no lookarounds, no balancing groups, no conditionals. O(n) amortised. |
| `PIKEVM_ONLY` | `PikeVmEngine` | Captures or features not compatible with DFA (lookarounds, alternation, complex quantifiers, transducers). O(n × \|NFA\|). |
| `NEEDS_BACKTRACKER` | `BoundedBacktrackEngine` | Backreferences, balancing groups, possessive quantifiers, atomic groups, or conditional subpatterns. O(budget) worst case; budget default 1,000,000. |

```java
Pattern p = Pattern.compile("(a+)\\1");
p.engineHint();   // NEEDS_BACKTRACKER

Pattern q = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]{2,4}");
q.engineHint();   // ONE_PASS_SAFE or DFA_SAFE
```

---

## Transducer

```java
public final class Transducer implements Serializable
```

An immutable, thread-safe compiled transducer. A `Transducer` both matches input text and
produces output text. The expression syntax is `input-pattern:output-template`. See
`docs/transducer-guide.md` for usage examples and `docs/transducer-api-reference.md` for
full method contracts.

### Factory method

```java
public static Transducer compile(String transducerExpr, TransducerFlag... flags)
```

Parses and compiles a transducer expression. If no `:` separator is present, the result is
an **identity transducer**: `applyUp` returns the matched substring.

**Throws:**
- `NullPointerException` — `transducerExpr` or `flags` is null.
- `RuntimeException` wrapping `PatternSyntaxException` — malformed input, cyclic output,
  or output side contains alternation or quantifiers.

### Instance methods

```java
public String applyUp(String input)
```

Applies the transducer forward. The entire `input` string must match the input pattern.
Returns the produced output string.

**Throws:**
- `NullPointerException` — `input` is null.
- `Transducer.TransducerException` — `input` does not fully match.

---

```java
public Optional<String> tryApplyUp(String input)
```

Same as `applyUp` but returns `Optional.empty()` instead of throwing on no match.

---

```java
public String applyDown(String output)
```

Applies the transducer in reverse. Equivalent to `invert().applyUp(output)`. Requires the
transducer to be invertible — compiled directly from a `Pair` expression, not from
`compose()`.

**Throws:**
- `NullPointerException` — `output` is null.
- `Transducer.NonInvertibleTransducerException` — transducer was produced by `compose()`.
- `Transducer.TransducerException` — `output` does not match the inverted input pattern.

---

```java
public Transducer invert()
```

Returns a new `Transducer` with input and output sides swapped.

**Throws:**
- `Transducer.NonInvertibleTransducerException` — transducer was produced by `compose()`.

---

```java
public List<Token> tokenize(String input)
```

Scans `input` for all non-overlapping matches (left to right, longest first). Returns a
list of `Token` objects that partition the entire input string: every character appears in
exactly one token, as either a match or a gap.

**Throws:**
- `NullPointerException` — `input` is null.

---

```java
public Iterator<Token> tokenizeIterator(java.io.Reader input)
public Stream<Token> tokenizeStream(java.io.Reader input)
```

Lazy variants of `tokenize` for streaming input. Not thread-safe; use each on one thread
only. Closing the stream does not close the underlying `Reader`.

**Throws:**
- `NullPointerException` — `input` is null.
- `java.io.UncheckedIOException` — wraps any `IOException` from `input`.

---

```java
public Transducer compose(Transducer other)
```

Returns a new transducer whose `applyUp(s)` equals `other.applyUp(this.applyUp(s))`.

The composed transducer is one-shot: calling `invert()`, `applyDown()`, or `compose()`
on it throws `NonInvertibleTransducerException`. Plan the pipeline so that `compose` is
the last step before application.

**Throws:**
- `NullPointerException` — `other` is null.

### Exception types

All three are `static` nested classes of `Transducer`, extend `RuntimeException`, and are
unchecked.

| Exception | Thrown by | Condition |
|---|---|---|
| `Transducer.TransducerException` | `applyUp` | The input does not fully match the input pattern. |
| `Transducer.NonInvertibleTransducerException` | `invert()`, `applyDown()` | The transducer was produced by `compose()` and has no `Pair` AST. |
| `Transducer.TransducerCompositionException` | `compose()` | The transducers are structurally incompatible. |

---

## Token types

`Token` is a sealed interface. Its three permitted implementations are records.

```java
public sealed interface Token permits MatchToken, OutputToken, ErrorToken
```

Methods: `int start()`, `int end()`.

---

```java
public record OutputToken(String type, String value, int start, int end, String output)
    implements Token
```

A matched span produced by `tokenize`.

| Field | Value |
|---|---|
| `type` | Always `"match"` when produced by `tokenize`. |
| `value` | The matched substring from the input. Never null. |
| `start` | Inclusive start index into the original input string. |
| `end` | Exclusive end index into the original input string. |
| `output` | The transducer output for this span. Never null. |

---

```java
public record MatchToken(String type, String value, int start, int end)
    implements Token
```

An unmatched gap between matches.

| Field | Value |
|---|---|
| `type` | Always `"gap"` when produced by `tokenize`. |
| `value` | The unmatched text. Never null; may be empty for zero-length gaps. |
| `start` | Inclusive start index. |
| `end` | Exclusive end index. |

---

```java
public record ErrorToken(String message, int start, int end)
    implements Token
```

A span that `tokenize` could not categorise. Not produced under normal operation.

| Field | Value |
|---|---|
| `message` | Diagnostic description of the error condition. |
| `start` | Inclusive start index. |
| `end` | Exclusive end index. |

---

## TransducerFlag

```java
public enum TransducerFlag   // in com.orbit.util
```

Flags passed to `Transducer.compile`. All are accepted without error; none activate
additional behaviour in the current implementation.

| Flag | Future use |
|---|---|
| `WEIGHTED` | Weighted semiring semantics (not yet implemented). |
| `INVERTIBLE` | All directly-compiled transducers are invertible by default. |
| `STREAMING` | Reserved. |
| `UNICODE` | Unicode handling is active by default. |
| `RE2_COMPAT` | RE2 compatibility is handled at the pattern level. |

---

## Thread safety summary

| Object | Thread-safe | Notes |
|---|---|---|
| `Pattern` | Yes | Immutable after `compile()`. |
| `Matcher` | No | Create one per thread from a shared `Pattern`. |
| `Transducer` | Yes | Immutable after `compile()`. |
| `Matcher.find()`, `matches()`, `lookingAt()` | No | Called on a per-thread `Matcher`. |
| `Transducer.applyUp()`, `applyDown()`, `tokenize()` | Yes | Each call creates its own execution context. |
| `Transducer.tokenizeIterator()`, `tokenizeStream()` | No | The `Iterator`/`Stream` is stateful. |
| `LazyDfaEngine` DFA state cache | Yes | Uses `ConcurrentHashMap` and `AtomicBoolean`/`AtomicInteger`. |
| `Pattern` compile cache | Yes | Static `ConcurrentHashMap`. |
| `UnicodeProperties` cache | Yes | Static `ConcurrentHashMap`; BMP scan may run twice in a race but produces identical results. |
