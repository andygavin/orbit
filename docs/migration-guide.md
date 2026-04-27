---
title: Migration Guide
nav_order: 7
---
# Migration Guide

This guide explains how to migrate code from `java.util.regex` to Orbit.

## Drop-in API compatibility

Orbit's `Pattern` and `Matcher` classes mirror `java.util.regex.Pattern` and
`java.util.regex.Matcher` for the features listed below. Existing code that uses only
these methods compiles and runs against Orbit without changes.

| `java.util.regex` method | Orbit equivalent |
|---|---|
| `Pattern.compile(String)` | `com.orbit.api.Pattern.compile(String)` |
| `Pattern.compile(String, int flags)` | `Pattern.compile(String, PatternFlag...)` |
| `pattern.matcher(CharSequence)` | `pattern.matcher(CharSequence)` |
| `pattern.matches(CharSequence)` | `Pattern.matches(String, String)` |
| `pattern.split(CharSequence, int)` | `pattern.split(CharSequence, int)` |
| `Pattern.quote(String)` | `Pattern.quote(String)` |
| `pattern.toString()` | `pattern.toString()` |
| `matcher.find()` | `matcher.find()` |
| `matcher.find(int start)` | `matcher.find(int start)` |
| `matcher.lookingAt()` | `matcher.lookingAt()` |
| `matcher.matches()` | `matcher.matches()` |
| `matcher.group()` | `matcher.group()` |
| `matcher.group(int)` | `matcher.group(int)` |
| `matcher.group(String)` | `matcher.group(String)` |
| `matcher.start()` / `end()` | `matcher.start()` / `matcher.end()` |
| `matcher.start(int)` / `end(int)` | `matcher.start(int)` / `matcher.end(int)` |
| `matcher.start(String)` / `end(String)` | `matcher.start(String)` / `matcher.end(String)` |
| `matcher.groupCount()` | `matcher.groupCount()` |
| `matcher.replaceAll(String)` | `matcher.replaceAll(String)` |
| `matcher.replaceFirst(String)` | `matcher.replaceFirst(String)` |
| `matcher.appendReplacement(StringBuilder, String)` | `matcher.appendReplacement(StringBuilder, String)` |
| `matcher.appendReplacement(StringBuffer, String)` | `matcher.appendReplacement(StringBuffer, String)` |
| `matcher.appendTail(StringBuilder)` | `matcher.appendTail(StringBuilder)` |
| `matcher.appendTail(StringBuffer)` | `matcher.appendTail(StringBuffer)` |
| `Matcher.quoteReplacement(String)` | `Matcher.quoteReplacement(String)` |
| `matcher.reset()` | `matcher.reset()` |

## Flags

`java.util.regex` flags (`CASE_INSENSITIVE`, `MULTILINE`, `DOTALL`, `COMMENTS`,
`LITERAL`) map to `com.orbit.util.PatternFlag` enum values. Pass them as varargs:

```java
// java.util.regex
Pattern.compile("hello", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

// Orbit
Pattern.compile("hello", PatternFlag.CASE_INSENSITIVE, PatternFlag.MULTILINE);
```

Inline flag syntax (`(?i)`, `(?m)`, `(?s)`, `(?x)`) works identically.

## What is not yet implemented

The following `java.util.regex` APIs or features have no Orbit equivalent in the current release:

| Feature | Status |
|---|---|
| `Matcher.requireEnd()` | Not implemented |
| `UNICODE_CHARACTER_CLASS` inline flag `(?u)` | Not implemented; use `PatternFlag.UNICODE` at compile time instead |
| Extended grapheme clusters `\X` | Not implemented |
| Supplementary code points > U+FFFF | Engine is BMP-only; surrogate pairs are not handled |
| Unbounded variable-length lookbehind (`.*` inside lookbehind) | Not implemented; bounded repetition (`{n,m}`) inside lookbehind works |
| `PatternFlag.CANON_EQ` | Not implemented |
| `PatternFlag.STREAMING` | Not implemented |

## Orbit-specific extensions

Orbit provides `pattern.engineHint()` and `pattern.isOnePassSafe()` for diagnosing
engine selection:

```java
EngineHint hint = pattern.engineHint(); // DFA_SAFE, NEEDS_BACKTRACKER, etc.
boolean onePass = pattern.isOnePassSafe();
```

The `Transducer` class (`com.orbit.api.Transducer`) is fully implemented in Phase 6.
`applyUp`, `tryApplyUp`, `applyDown`, `tokenize`, `invert`, and `compose` all work. See
`docs/transducer-guide.md` for usage and `docs/transducer-api-reference.md` for method
contracts.

## Replacement strings

Orbit's replacement-string scanning matches `java.util.regex` exactly. Code that passes
replacement strings to `replaceAll`, `replaceFirst`, or `appendReplacement` requires no
changes.

`$N` expands to captured group N. `$0` expands to the entire match. `${name}` expands to
the named group. `\\` produces a literal backslash; `\$` produces a literal dollar sign. A
group that did not participate contributes an empty string.

The back-off rule for multi-digit references matches JDK behaviour: `$15` with three groups
expands to group 1 followed by the literal character `5`; `$5` with three groups throws
`IndexOutOfBoundsException`.

`Matcher.quoteReplacement(String)` is available. It escapes `\` and `$` so the result is
treated as a literal string by `replaceAll` and `appendReplacement`.

See `docs/introduction.md` — "Replacement strings" — for the full reference including the
error-case table.

## `toMatchResult()` on a no-match `Matcher`

Calling `toMatchResult()` on a `Matcher` that has never matched, or after a `find()` call
that returned `false`, returns a snapshot `MatchResult`. In the current release, calling
`start()`, `start(int)`, `start(String)`, `end()`, `end(int)`, `end(String)`, `group()`,
`group(int)`, or `group(String)` on that snapshot throws `IllegalStateException`. This
matches the behaviour specified in JDK Bug 8074678.

`groupCount()`, `hasMatch()`, `namedGroups()`, and `toString()` remain callable on a
no-match snapshot.

Earlier Orbit builds returned `-1` or `null` from these accessors on a no-match snapshot.
Code that relied on that behaviour must be updated to check `hasMatch()` before calling
positional accessors.

## Known behaviour differences

- `{abc}` (non-numeric brace content): `java.util.regex` throws `PatternSyntaxException`;
  Orbit treats it as a literal. Tests that encounter this divergence are skipped.
- Lazy unbounded quantifiers with backreferences: Orbit compiles the lazy quantifier as
  greedy in some configurations. Tests exercising this combination are skipped.

## Troubleshooting

**Pattern compiles in `java.util.regex` but fails in Orbit.**
Check `docs/compatibility.md` — the pattern may use a feature listed in "Known gaps".

**Match result differs from `java.util.regex`.**
File a compatibility issue. Include the pattern, input string, flags, expected result
(from JDK), and Orbit's actual result.

**`MatchTimeoutException` thrown.**
The pattern has a backreference and the input caused the backtrack budget
(`1_000_000` operations) to be exceeded. Either simplify the pattern or reduce the
input size. See `docs/security-guide.md`.
