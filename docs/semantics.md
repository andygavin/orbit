---
title: Semantic Layers
nav_order: 5
---
# Orbit Semantic Layers

This document is the authoritative reference for which behaviors Orbit provides in each
mode, where it diverges from JDK or RE2, and which extensions are always-on. Every
implementation decision that touches matching semantics — including the `RegExCompatTest`
Batch 3 fixes — must be evaluated against this document before writing code.

---

## Feature set hierarchy

RE2 is **stricter** than JDK, not an extension of it. The configurable layers, ordered
from most to least restrictive:

```
RE2_COMPAT  ⊂  Orbit default  ⊂  +UNICODE  ⊂  +PERL_NEWLINES
 (smallest)
```

| Mode | Activation | What changes |
|---|---|---|
| `RE2_COMPAT` | `PatternFlag.RE2_COMPAT` | Restricts to RE2 subset: no backrefs, no lookaround, no possessives, O(n) guarantee |
| **Orbit default** | no flags | JDK 21 behavioral parity. Also accepts syntax JDK rejects as PSE (see below). |
| `UNICODE` | `PatternFlag.UNICODE` | `\w`/`\d`/`\s`/`\b` and POSIX classes use Unicode properties instead of ASCII |
| `PERL_NEWLINES` | `PatternFlag.PERL_NEWLINES` | Dot and `$` exclude `\n` only; `\r` stays a terminator for anchors |

**Orbit's default mode is a superset of JDK's parser.** It accepts syntax that JDK
rejects with `PatternSyntaxException` — atomic groups `(?>...)`, balancing groups
`(?<name-name2>...)`, conditional subpatterns `(?(cond)yes|no)`, and Python-style named
groups `(?P<name>...)`. This is not a separate opt-in layer; it is simply Orbit's parser
being broader. No existing `java.util.regex` program uses these constructs (they would
have thrown PSE), so there is no compatibility risk. For matched patterns, matching
semantics are identical to JDK.

`UNICODE` and `PERL_NEWLINES` are opt-in relaxations toward Perl behavior, independent
and combinable.

The transducer API (`com.orbit.api.Transducer`) is architecturally separate from all
layers and is unaffected by `PatternFlag`.

---

## JDK default vs RE2: key behavioral differences

RE2 is designed for **untrusted input** with a guaranteed O(n) time bound. It rejects
or ignores anything that requires backtracking. JDK is Perl-like: feature-complete but
ReDoS-vulnerable without external budget enforcement (Orbit adds the BBE budget on top).

### Feature support

| Feature | JDK / Orbit default | RE2_COMPAT |
|---|---|---|
| Backreferences (`\1`, `\k<name>`) | Supported | `PatternSyntaxException` |
| Lookahead (`(?=...)`, `(?!...)`) | Supported | `PatternSyntaxException` |
| Lookbehind (`(?<=...)`, `(?<!...)`) | Fixed- and variable-length | `PatternSyntaxException` |
| Atomic groups `(?>...)` | Supported (BBE) | `PatternSyntaxException` |
| Possessive quantifiers `x*+` | Supported (BBE) | `PatternSyntaxException` |
| Balancing groups `(?<n-m>...)` | Supported (BBE) | `PatternSyntaxException` |
| Conditional subpatterns | Supported (BBE) | `PatternSyntaxException` |
| Character class intersection `[a&&[b]]` | Supported | `PatternSyntaxException` |
| `COMMENTS` flag | Supported | Not supported |
| `LITERAL` flag | Supported | Not supported |
| `UNICODE_CASE` flag | Supported | Not supported |
| `UNIX_LINES` flag | Supported | Not supported |
| `CANON_EQ` flag | Supported | Not supported |
| Region/anchoring API (`region()`, `useAnchoringBounds()`) | Supported | Not supported |
| Engine guarantee | Best-effort; BBE used for above features | Strictly O(n); DFA/PikeVM only |

### Behavioral differences for shared features

| Behavior | JDK / Orbit default | RE2_COMPAT |
|---|---|---|
| **Dot `.` exclusions** | `\n`, `\r`, `\u0085`, `\u2028`, `\u2029` | `\n` only |
| **`$` matches before** | `\n`, `\r`, `\u0085`, `\u2028`, `\u2029` (and before `\r` of `\r\n`) | `\n` only |
| **`\r\n` as unit** | Yes — indivisible; `$` before `\r`, `^` not between `\r` and `\n` | No — each character independent |
| **`\b` word characters** | `[a-zA-Z0-9_]` + Unicode `NON_SPACING_MARK` | ASCII only: `[a-zA-Z0-9_]` |
| **Alternation priority** | Leftmost-first (NFA); `matches()` backtracks to find full-length alternative | Leftmost-longest (DFA) |
| **Named capture syntax** | `(?<name>...)` | `(?P<name>...)` or `(?<name>...)` depending on port |
| **Case-insensitive backrefs** | `(a)\1` matches `"aA"` | No backrefs |
| **Special Unicode folds** | `UNICODE_CASE`+`CASE_INSENSITIVE`: dotless-i, long-S, Kelvin, Angstrom | Not applicable (flag unsupported) |

---

## JDK default vs Perl: additional context

The `PerlRegexCompatTest` suite runs Perl's `re_tests`. Many Perl features are **not**
JDK features and are not Orbit targets:

| Feature | Perl | JDK / Orbit |
|---|---|---|
| `\K` keep-out | Yes | No |
| Branch reset `(?|...)` | Yes | No |
| Lookahead inside character classes | Yes | No |
| `\b` is Unicode-aware by default | Yes | No (ASCII; needs UNICODE_CHARACTER_CLASS) |
| `POSIX` classes with Unicode semantics | Yes | Limited |

Orbit does not have a Perl compat mode. `PerlRegexCompatTest` skips are expected.

---

## Orbit extensions (JDK+ default layer)

These are available in Orbit's default mode. They are **not** available under
`RE2_COMPAT` because they all require `BoundedBacktrackEngine`. Existing
`java.util.regex` programs are unaffected because JDK rejects these as
`PatternSyntaxException` — no valid JDK program uses them.

| Feature | Syntax | Why safe to extend |
|---|---|---|
| Atomic groups | `(?>...)` | JDK throws PSE; no compat conflict |
| Balancing groups | `(?<name-name2>...)` | .NET only; JDK throws PSE |
| Conditional subpatterns | `(?(cond)yes\|no)` | JDK throws PSE |
| `(?P<name>...)` Python-style named groups | `(?P<name>...)` | JDK throws PSE; alias for `(?<name>...)` |

Note: **possessive quantifiers** (`x*+`, `x++`, etc.) are supported by JDK 17+ and are
therefore part of the JDK default layer, not an extension.

---

## RE2_COMPAT — implemented (§6.10)

`PatternFlag.RE2_COMPAT` is fully wired as of §6.10. At compile time, `Pattern.compile`
throws `PatternSyntaxException` for any non-RE2 construct — never at match time.

**Rejected at compile time:**
- Backreferences (`\1`–`\99`, `\k<name>`)
- Lookahead (`(?=...)`, `(?!...)`)
- Lookbehind (`(?<=...)`, `(?<!...)`)
- Atomic groups `(?>...)`
- Possessive quantifiers (`*+`, `++`, `?+`, `{n,m}+`)
- Balancing groups, conditional subpatterns
- Character class intersection (`[a&&[b]]`)
- Incompatible flags: `COMMENTS`, `LITERAL`, `UNICODE_CASE`, `UNIX_LINES`, `CANON_EQ`, `PERL_NEWLINES`

**Behavioral changes under `RE2_COMPAT`:**
- Dot `.` excludes only `\n` (not `\r`, `\u0085`, `\u2028`, `\u2029`)
- `^`/`$` use `\n`-only terminator set; `\r` is not treated as a line terminator
- Engine forced to `PikeVmEngine` (NFA semantics); patterns can never be routed to `BoundedBacktrackEngine`

See `ROADMAP.md` §6.10 for full implementation notes.

---

## Closing the Perl gap (`PerlRegexCompatTest`)

664 of 1974 Perl `re_tests` cases are currently skipped. The skips fall into three
independent buckets — each has a different fix strategy.

### Bucket 1 — Feature gaps (no flag needed)

These constructs are absent from Orbit's parser/compiler but are compatible with JDK-default
or JDK-extension semantics. Implementing them does not require a new mode flag.

| Feature | Skip count (approx) | Effort | Status |
|---|---|---|---|
| `\x{NNN}` braced hex escape | ~10 | Easy | **Done (§6.7.1)** |
| `\c` control-char escape | ~5 | Easy | **Done (§6.7.1)** |
| `\h`/`\H` horizontal whitespace, `\v`/`\V` vertical whitespace | ~20 | Easy | **Done (§6.7.2)** |
| `\o{...}` braced octal escape | ~5 | Easy | **Done (§6.7.1)** |
| `{,N}` leading-comma quantifier (= `{0,N}`) | ~5 | Easy | **Done (§6.7.1)** |
| `\R` any Unicode line break | ~15 | Medium | **Done (§6.7.2 / Batch B1)** |
| `\g` relative/named backreference | ~10 | Medium | **Done (§6.7.2)** |
| `(?|...)` branch reset group | ~30 | Medium | **Done (§6.7.3 F1)** |
| `\K` keep assertion (reset match start) | ~20 | Medium | **Done (§6.7.3 F2)** |
| Named conditional `(?(<name>)...)` | ~5 | Medium | **Done (§6.7.2)** |
| `\N{NAME}` Unicode named char escape | ~few | Medium | **Done (§6.7.5)** |
| `\X` extended grapheme cluster | ~10 | Hard | Not scheduled |
| Recursion `(?R)`, `(?N)`, `(?&name)` | ~30 | Hard | Not planned |
| POSIX collating `[[.x.]]`, `[[=x=]]` | ~5 | Hard | Not planned |

### Bucket 2 — `UNICODE` flag (Unicode-aware character classes) — **Implemented (§6.8)**

`PatternFlag.UNICODE` is fully wired. It changes the following behaviors:

| Behavior | JDK default | `UNICODE` |
|---|---|---|
| `\w` | `[a-zA-Z0-9_]` | Unicode letter/digit/connector punctuation |
| `\d` | `[0-9]` | Unicode decimal digit (`\p{Nd}`) |
| `\s` | ASCII whitespace | `\p{Z}` union ASCII whitespace |
| `\b` word boundary | ASCII + NON_SPACING_MARK | `Character.isLetterOrDigit()` or `'_'` |
| `[:alpha:]` POSIX | ASCII only | `\p{L}` Unicode letters |
| `[:digit:]` POSIX | `[0-9]` | `\p{Nd}` |
| `[:lower:]` POSIX | `[a-z]` | `\p{Ll}` |
| `[:upper:]` POSIX | `[A-Z]` | `\p{Lu}` |
| `[:space:]` POSIX | ASCII whitespace | Unicode whitespace |
| `\p{Word}` POSIX | Letter/digit/`_` | Also includes CONNECTOR_PUNCTUATION |
| Case folding | ASCII + JDK special | Full Unicode case folding (dotless-i, long-s, Kelvin, Ångström) |

`UNICODE` implies `UNICODE_CASE`. It does not change dot or anchor behavior.

### Bucket 3 — `PERL_NEWLINES` flag (dot/anchor newline set) — **Implemented (§6.9)**

`PatternFlag.PERL_NEWLINES` is fully wired. It implements Perl's actual newline semantics,
distinct from `UNIX_LINES`:

| Behavior | JDK default | `UNIX_LINES` | `PERL_NEWLINES` |
|---|---|---|---|
| `.` excludes | `\n \r \u0085 \u2028 \u2029` | `\n` only | `\n` only |
| `$` matches before | same 5 chars | `\n` only | `\n` only |
| `^` in MULTILINE | any of 5 | `\n` only | `\n` only |
| `\r` as line terminator for `$` | yes | **no** | **yes** |
| `\r\n` as unit | yes | no | yes |

### Bucket 4 — Won't fix (intentional Perl divergence)

| Behavior | Reason |
|---|---|
| Group reset to `null` vs Perl `undef` | Language-level difference; Java has no `undef` |
| ZWNJ / ZWJ as `\w` | Orbit follows JDK intentionally |
| Octal `\400`–`\777` → Unicode U+0100–U+01FF | Perl-specific; not planned |
| Self-referential backreferences `(a\1?)` | Not planned |
| `(?a)`, `(?l)`, `(?u)`, `(?d)` Perl inline flags | Not planned |

---

## How this constrains Batch 3 (`RegExCompatTest`) fixes

All Batch 3 fixes target **JDK default** semantics. Their relationship to future
`RE2_COMPAT` implementation:

| Group | Fix | RE2_COMPAT impact |
|---|---|---|
| A — Case-insensitive backrefs | `regionMatches(ignoreCase=true)` in BBE | Backrefs are PSE in RE2; fix lives only inside BBE, which RE2 never reaches |
| B — `matches()` full-match backtrack | `Accept` only succeeds when `pos == requiredEnd` | RE2's leftmost-longest naturally produces full-match semantics; gate fix behind `!re2Compat` |
| C — LITERAL + DOTALL | Fix parser LITERAL guard | LITERAL is PSE in RE2; no impact |
| D — Dot excludes `\u2028`/`\u2029` | Fix char class ranges in `Parser.java` | RE2 overrides to `\n`-only; make dot construction flag-aware so RE2 path uses a single exclusion |
| E — `\b` + NON_SPACING_MARK | Add Unicode category to `isWordChar` | RE2 uses ASCII-only; make `isWordChar` flag-aware |
| F — `\p{javaXxx}` | Add 10 properties to `UnicodeProperties` | `javaXxx` is JDK-specific; RE2 would throw PSE at parse time |
| G — Special Unicode folds | Extend case-fold table | `UNICODE_CASE` is PSE in RE2; no impact |
| H — Variable-length lookbehind | `min/maxBodyLength` in `LookbehindPos`/`LookbehindNeg` | All lookbehind is PSE in RE2; guard in parser |

For **D** and **E**, implement the JDK behavior using the flags already available in
`CompileResult` / `PatternFlag` set rather than hardcoding. This makes the future RE2
branch a clean `if/else` rather than a structural change.

---

## Behavior that must not regress

Run this before and after every Batch 3 change:

```
mvn test -pl orbit-core -Dtest="DotNetCompatTest,BasicRegexCompatTest,RegExCompatTest,TransducerTest"
```

| Feature | Test class | Why it could break |
|---|---|---|
| Transducer API (`applyUp/Down/tokenize/invert/compose`) | `TransducerTest` | Architecturally separate but shares `Prog`/`CompileResult` |
| Atomic groups `(?>...)` | `DotNetCompatTest` | BBE changes from Group B could affect atomic commit |
| Balancing groups | `DotNetCompatTest` | Same |
| Conditional subpatterns | `DotNetCompatTest` | Same |
| Possessive quantifiers | `DotNetCompatTest`, `BasicRegexCompatTest` | BBE stack changes |
| `ProgOptimiser` EpsilonJump folding | all suites | Performance pass; must not alter match semantics |
| ReDoS budget (`MatchTimeoutException`) | `DotNetCompatTest` | Budget check must survive any BBE changes |
| `\G` anchor | `RegExCompatTest` | `lastMatchEnd` wiring must survive `matches()` changes (Group B) |

---

## Document maintenance

Update this file when:
- A new flag is added to `PatternFlag` or `TransducerFlag`
- A behavior intentionally diverges from JDK
- `RE2_COMPAT` implementation begins (move "intended" to "implemented")
- A new Orbit extension is added

Do not add Perl-specific behaviors to the JDK default layer.
