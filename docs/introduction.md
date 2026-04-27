---
title: Introduction
nav_order: 1
---
# An Introduction to Orbit

## Why this exists

Two domains eat regex libraries alive: chemistry and linguistics.

In computational chemistry, you routinely need to scan large corpora of scientific text for
molecular entities. A compound like ammonium chloride might appear as `NH4Cl`, `[NH4+][Cl-]`,
`H4ClN`, or a dozen other valid SMILES representations. The element symbols follow a rule —
uppercase letter, optional lowercase letter — but the surrounding notation includes brackets,
charges (`+2`, `-`), bond symbols, ring closures, and stereochemistry markers. You are not
matching a word; you are matching a structured token embedded in a domain-specific notation
that most regex authors have never seen.

In linguistics the problem is quieter but just as real. Morphological analysis — the study
of how words are built from stems and affixes — requires matching patterns like "any Latin
second-declension noun stem followed by a nominative singular ending". IPA transcriptions
mix base characters, diacritics, and suprasegmental markers from across the Unicode
range. Inflectional paradigms have regular structure, but "regular" here means "regular in
the linguistic sense", which does not map cleanly onto what `grep` considers regular.

Both domains share the same frustration: the standard tools — `grep`, `java.util.regex`,
most language built-ins — handle the easy cases and then stop. They handle ASCII
well. They handle simple Unicode acceptably. They stumble on possessive quantifiers,
Unicode property classes, nested bracket matching, and anything that needs to simultaneously
match and transform input. And they have a performance cliff: a carefully constructed
adversarial input, or simply a user-supplied pattern that nobody thought to audit, can turn
a millisecond operation into one that runs for minutes.

Pattern matching sits at the intersection of formal language theory and practical
engineering. The theory is settled — we have known since the 1960s how to match regular
expressions in linear time. The gap between theory and most libraries is that linear-time
engines are harder to implement than backtracking engines, and backtracking engines work
fine until they do not. Orbit is an attempt to close that gap: a Java regex library that
guarantees bounded execution time for the broad class of patterns that do not require
backtracking, and that provides a budget-protected fallback for the ones that do.

---

## Regular expressions from first principles

If you already use regex fluently, skim this section — but the subsections on quantifier
modes and lookaround are worth checking even so. The terminology matters for what comes
later.

### Literals and character classes

The simplest pattern is a literal: the pattern `NaCl` matches the string `NaCl` and nothing
else. The engine compares characters left to right. When all characters match, the pattern
succeeds.

A **character class** matches any one character from a set. `[aeiou]` matches any lowercase
English vowel. `[A-Z]` matches any uppercase ASCII letter. `[A-Za-z0-9]` matches any ASCII
alphanumeric character. The complement of a class is written with `^` inside the brackets:
`[^0-9]` matches any character that is not an ASCII digit.

Some classes are common enough to have shorthand names. `\d` is equivalent to `[0-9]`.
`\w` is equivalent to `[A-Za-z0-9_]`. `\s` matches whitespace. The dot `.` matches any
character except a newline (unless you compile the pattern with `PatternFlag.DOTALL`).

`PatternFlag` values passed to `Pattern.compile` control which semantic layer the engine
operates in. Four flags matter for matching behaviour:

| Flag | What it changes |
|---|---|
| _(none)_ | Orbit default — JDK 21 parity; also accepts atomic groups, balancing groups, and conditionals that JDK rejects |
| `CASE_INSENSITIVE`, `MULTILINE`, `DOTALL`, `UNIX_LINES`, etc. | Standard JDK-compatible modifiers |
| `RE2_COMPAT` | Compile-time rejection of backreferences, lookaround, possessives, and other backtracking constructs; O(n) guarantee |
| `UNICODE` | `\w`/`\d`/`\s`/`\b` and POSIX classes use Unicode properties instead of ASCII ranges |
| `PERL_NEWLINES` | Dot and `$` exclude `\n` only; `\r` remains a line terminator (unlike `UNIX_LINES`) |

The key distinction for production use: `RE2_COMPAT` throws `PatternSyntaxException` at
compile time for any construct that requires backtracking — the rejection happens inside
`Pattern.compile()`, never during matching. `UNICODE` and `PERL_NEWLINES` produce no new
exceptions; they silently change what the shorthands and anchors match. The only match-time
exception Orbit throws is `MatchTimeoutException`, which signals that a bounded backtracker
pattern exhausted its operation budget.

In chemistry, element symbols follow the rule: one uppercase letter, optionally followed
by one lowercase letter. As a character class expression: `[A-Z][a-z]?`. The `?` is a
quantifier, explained next.

### Quantifiers

A quantifier says how many times the preceding element must appear.

| Quantifier | Meaning |
|---|---|
| `?` | Zero or one time |
| `*` | Zero or more times |
| `+` | One or more times |
| `{n}` | Exactly n times |
| `{n,m}` | Between n and m times (inclusive) |
| `{n,}` | At least n times |

The pattern `[A-Z][a-z]?` therefore reads: one uppercase letter, followed by an optional
lowercase letter. It matches `N`, `Na`, `Cl`, `C`, `Ca`, and so on — the valid element
symbol forms.

By default, quantifiers are **greedy**: they consume as many characters as possible while
still allowing the overall pattern to match. The `*` in `[A-Z][a-z]*` will match `Na`,
consuming both characters, not just `N`.

Adding `?` after a quantifier makes it **lazy** (also called reluctant): it consumes as few
characters as possible. The pattern `[A-Z][a-z]*?` on `Na` will match only `N`, leaving
`a` for whatever comes next in the pattern.

There is a third mode — **possessive** — which we will cover in the Orbit-specific section,
because it requires some background on how backtracking works.

### Alternation

The pipe symbol `|` means "this or that". The pattern `us|um|i` matches the strings `us`,
`um`, or `i` — the three most common endings in Latin second-declension nouns. Alternation
has the lowest precedence of any regex operator, so `stem-us|stem-um` means "the string
`stem-us` or the string `stem-um`", not "the string `stem-` followed by `us` or `um`". Use
groups (next section) to scope alternation.

### Groups

Parentheses `(...)` serve two purposes: they scope the contents as a unit for quantifiers
and alternation, and they **capture** the matched text so you can retrieve it afterwards.

The pattern `([A-Z][a-z]?)(\d*)` matches an element symbol followed by an optional
count — the kind of token that appears in a molecular formula like `C6H12O6`. Group 1
captures the element, group 2 captures the count. After a successful match, `matcher.group(1)`
returns `C` and `matcher.group(2)` returns `6`.

If you want the grouping but not the capture — because captures carry a small cost — use a
**non-capturing group**: `(?:...)`. The pattern `(?:us|um|i)$` groups the Latin endings for
the end-of-string anchor without recording which ending matched.

**Named groups** give captures readable labels rather than positional numbers:
`(?<element>[A-Z][a-z]?)(?<count>\d*)`. After a match, `matcher.group("element")` returns
the element symbol and `matcher.group("count")` returns the atom count. Named groups are
the right choice whenever you have more than two or three captures — position-based indexing
breaks every time you add a group earlier in the pattern.

### Anchors

An anchor matches a position in the input rather than a character. `^` matches the start of
the input (or the start of a line in multiline mode). `$` matches the end of the input (or
end of a line). `\b` matches a word boundary — the position between a word character and a
non-word character.

For morphological work, `\b` is your friend when you want to match a suffix only at the end
of a whole token: `\b\w+(?:tion|ment|ness)\b` matches whole words that end in common
English nominalising suffixes without also matching `mentioning` mid-string.

### Lookahead and lookbehind

A **lookahead** checks what follows the current position without consuming it. The pattern
`[A-Z][a-z]?(?=\d)` matches an element symbol only when it is immediately followed by a
digit. The digit is not part of the match — it is just a condition. Positive lookahead uses
`(?=...)`, negative lookahead uses `(?!...)`.

**Lookbehind** is the mirror: `(?<=\d)[A-Z][a-z]?` matches an element symbol only when
it is preceded by a digit. Positive lookbehind uses `(?<=...)`, negative uses `(?<!...)`.

In IPA transcription work, lookbehind is useful for matching diacritics that only occur
after certain base characters without consuming the base character as part of the match:
`(?<=[aeiouæøœɛɔ])ː` matches the IPA length mark only when it follows a vowel.

---

## Quick reference

The sections above cover the mechanics in prose. This section collects the same material
into lookup tables for when you remember what a feature does but not the exact syntax.

### Engine flavors

Every regex engine supports a slightly different syntax, called a *flavor*. Orbit's flavor
is a superset of Java's `java.util.regex`, extended with most .NET additions — possessive
quantifiers, atomic groups, and balancing groups. Patterns written for PCRE, Python `re`,
or JavaScript are mostly compatible and usually need no changes for common cases. The
exceptions are possessive quantifiers (`a++`, `a*+`) and balancing groups, which are
specific to Orbit, Java, and .NET — PCRE has possessives but not balancing groups, and
Python and JavaScript have neither.

### Anchors

Anchors match a position, not a character. They let you constrain where in the input a
match may occur — at a line boundary, at the start of a string, at the edge of a word.
`\b` in particular is invaluable in morphological work, where you want to match a suffix
as part of a complete token rather than anywhere it appears mid-string.

| Syntax | Description | Example pattern | Example matches | Example non-matches |
|---|---|---|---|---|
| `^` | Start of input; start of line in multiline mode | `^Na` | `NaCl` (at start of line) | `HNaCl` |
| `$` | End of input; end of line in multiline mode | `Cl$` | `NaCl` (at end of line) | `ClH` |
| `\A` | Start of entire input regardless of multiline mode | `\ANa` | `NaCl` | `HNaCl` |
| `\Z` | End of entire input regardless of multiline mode | `Cl\Z` | `NaCl` | `ClH` |
| `\b` | Word boundary — position between `\w` and `\W` (or at input edge) | `\btion\b` | `tion` as a whole word | `mention` (mid-word) |
| `\B` | Non-word boundary — position between two `\w` or two `\W` characters | `\Btion` | `mention` | `tion` (at word start) |

### Matching types of character

The dot and the backslash shorthands let you match a category of character without
enumerating every possibility. The dot is the broadest — anything except a newline — and
the shorthands narrow it to specific Unicode categories. Remember that `\d`, `\w`, and `\s`
in Orbit follow Java's `java.util.regex` definitions, which are ASCII-scoped by default;
use `\p{...}` properties for Unicode-aware matching.

| Syntax | Description | Example pattern | Example matches | Example non-matches |
|---|---|---|---|---|
| `.` | Any character except newline (any character including newline with `DOTALL`) | `N.Cl` | `NaCl`, `N2Cl` | `N\nCl` (without DOTALL) |
| `\d` | ASCII digit: `[0-9]` | `\d+` | `6`, `12`, `500` | `six` |
| `\D` | Any character that is not an ASCII digit | `\D+` | `NaCl`, `dissolve` | `123` |
| `\w` | Word character: `[A-Za-z0-9_]` | `\w+` | `NaCl`, `H2O` | `[NH4+]` |
| `\W` | Any character that is not a word character | `\W+` | `[`, `+]`, ` ` | `NaCl` |
| `\s` | Whitespace: space, tab, newline, carriage return, form feed | `\s+` | ` `, `\t`, `\n` | `NaCl` |
| `\S` | Any character that is not whitespace | `\S+` | `NaCl`, `250g` | `   ` |
| `\metacharacter` | Literal metacharacter — escapes `.`, `*`, `+`, `?`, `(`, `)`, `[`, `]`, `{`, `}`, `\|`, `^`, `$`, `\` | `\[NH4\+\]` | `[NH4+]` | `NH4` |

### Character classes

A character class matches exactly one character from a defined set. Classes are the right
tool when the category you need does not have a shorthand — consonant clusters, specific
punctuation, domain symbol sets like SMILES bond characters. Note that most metacharacters
lose their special meaning inside `[...]`; the exceptions are `]`, `\`, `^` (at the
start), and `-` (between two characters).

| Syntax | Description | Example pattern | Example matches | Example non-matches |
|---|---|---|---|---|
| `[xy]` | Any one character listed | `[HCNOPSFIcnops]` | `H`, `C`, `c` | `Na` (two characters) |
| `[x-y]` | Any one character in the range x through y | `[A-Z]` | `H`, `C`, `N` | `a`, `1` |
| `[^xy]` | Any one character *not* listed | `[^0-9]` | `N`, `a`, `+` | `6`, `0` |
| `[\^\-]` | Literal `^` or `-` inside a class (escaped or repositioned) | `[\^\-+]` | `^`, `-`, `+` | `a` |

### Repetition

Quantifiers covered in the earlier section above, but for quick reference. A quantifier
follows any single element — a literal character, a shorthand, a class, or a group — and
says how many times that element must appear. Greedy quantifiers consume as much input as
possible; lazy variants (suffixed `?`) consume as little as possible while still producing
a match.

| Syntax | Description | Example pattern | Example matches | Example non-matches |
|---|---|---|---|---|
| `x*` | Zero or more times (greedy) | `[a-z]*` | `` (empty), `l`, `la`, `latin` | — |
| `x+` | One or more times (greedy) | `\d+` | `1`, `12`, `500` | `` (empty) |
| `x?` | Zero or one time (greedy) | `[A-Z][a-z]?` | `N`, `Na`, `Ca` | `Na2` (the `2` is beyond `x?`) |
| `x{m}` | Exactly m times | `[A-Z]{2}` | `Cl`, `Na` | `C`, `Cal` |
| `x{m,}` | At least m times | `\d{2,}` | `12`, `500`, `6022` | `6` |
| `x{m,n}` | Between m and n times (inclusive) | `[a-z]{2,4}` | `la`, `lat`, `lati` | `l`, `latin` |
| `x*?` | Zero or more times (lazy) | `[A-Z][a-z]*?` | `N` from `Na` | — |
| `x+?` | One or more times (lazy) | `[a-z]+?` | `l` from `latin` | — |

### Capturing, alternation, and backreferences

Groups were covered in more detail above. This table is the concise reference. Named groups
are the right default whenever a pattern has more than two captures — positional indices
break silently when you add a group elsewhere in the pattern. Backreferences in the
`NEEDS_BACKTRACKER` tier (patterns with `\1`, `\k<name>`) route through the bounded
backtracker; see the engine tier section below.

| Syntax | Description | Example pattern | Example matches |
|---|---|---|---|
| `(x)` | Capturing group — records the matched text, accessible by index | `([A-Z][a-z]?)(\d*)` | Group 1: `Na`, Group 2: `2` from `Na2` |
| `(?:x)` | Non-capturing group — scopes for alternation or quantifiers, no capture overhead | `(?:us\|um\|i)$` | `us`, `um`, `i` at end of string |
| `(?<name>x)` | Named capturing group — accessible by name | `(?<element>[A-Z][a-z]?)` | `matcher.group("element")` → `Na` |
| `(x\|y)` | Alternation scoped within a group | `(tion\|ment\|ness)` | `tion`, `ment`, `ness` |
| `\n` | Backreference by index — matches the same text captured by group n | `([a-z]+)\1` | `lala`, `bonbon` |
| `\k<name>` | Backreference by name — matches the same text captured by the named group | `(?<syl>[a-z]+)\k<syl>` | `lala`, `bonbon` |

### Lookahead and lookbehind

Lookaround assertions match a position based on what surrounds it, without consuming
characters. They are indispensable for matching context-dependent patterns — element
symbols that appear only before counts, suffixes that appear only after specific stems, IPA
diacritics that appear only after certain base characters. Negative forms are equally
useful: match a word boundary *not* preceded by a digit, or a letter *not* followed by a
combining mark.

| Syntax | Description | Example pattern | Example matches | Example non-matches |
|---|---|---|---|---|
| `(?=x)` | Positive lookahead — position must be followed by x | `[A-Z][a-z]?(?=\d)` | `Na` in `Na2`, `C` in `C6` | `Na` in `NaCl` (not followed by digit) |
| `(?!x)` | Negative lookahead — position must not be followed by x | `[A-Z][a-z]?(?!\d)` | `Na` in `NaCl` | `Na` in `Na2` |
| `(?<=x)` | Positive lookbehind — position must be preceded by x | `(?<=\d)[A-Z][a-z]?` | `O` in `2O` | `O` in `NaO` |
| `(?<!x)` | Negative lookbehind — position must not be preceded by x | `(?<!\d)[A-Z][a-z]?` | `Na` in `NaCl` | `O` in `2O` |

### Modifiers

Modifiers change how the engine interprets the pattern. They can be placed inline at any
position — `(?i)` turns on case-insensitive matching from that point forward — or scoped
to a subexpression: `(?i:NaCl)` makes only that group case-insensitive, leaving the rest
of the pattern unchanged. The scoped form is the safer default in longer patterns.

| Syntax | Description | Example |
|---|---|---|
| `(?i)` | Case-insensitive matching | `(?i)nacl` matches `NaCl`, `NACL`, `nacl` |
| `(?m)` | Multiline mode — `^` and `$` match start/end of each line | `(?m)^\d` matches a digit at the start of any line in a multi-line string |
| `(?s)` | DOTALL mode — `.` matches newline characters | `(?s).+` matches a string containing newlines |
| `(?x)` | Free-spacing (verbose) mode — unescaped whitespace and `#`-to-end-of-line comments are ignored | Lets you lay out a complex pattern across multiple lines with inline comments |
| `(?-i)` | Remove a previously set flag — turns case-insensitive matching back off | `(?i)Na(?-i)Cl` — `Na` is case-insensitive, `Cl` is not |
| `\Q...\E` | Quotemeta block — everything between `\Q` and `\E` is treated as a literal, metacharacters included | `\Q[NH4+]\E` matches the literal string `[NH4+]` |

### Unicode

The `\p{...}` properties were introduced above in the Unicode property classes section. The
table below collects the full set of forms Orbit supports. Unicode properties are the
correct tool for any non-ASCII matching: they are defined by the Unicode standard, are
updated with each Unicode release, and are far more readable than manually enumerated
code-point ranges.

| Syntax | Description | Example |
|---|---|---|
| `\p{Lu}` | Uppercase letter in any script | Matches `N`, `Δ`, `Ж` |
| `\p{L}` | Any letter in any script | Matches `a`, `α`, `ا` |
| `\p{InGreek}` | Any character in the Greek Unicode block (`U+0370`–`U+03FF`) | Matches `α`, `β`, `Δ` |
| `\p{IsLatin}` | Any character in the Latin script | Matches `a`–`z`, `à`, `ñ` |
| `\P{L}` | Negation — any character that is *not* a letter (`\P` is uppercase) | Matches `2`, `+`, `[` |
| `\X` | Extended grapheme cluster — a base character plus all combining marks that follow it. **Not yet implemented in Orbit.** | Would match `e` + combining acute as a single unit |
| `\u00e8` | Unicode code point literal — the character at the given hex code point | Matches `è` (U+00E8, Latin small letter e with grave) |

---

## Replacement strings

`replaceAll`, `replaceFirst`, and `appendReplacement` accept a replacement string that is
scanned for group references and escape sequences before being written to output. The
scanning rules match `java.util.regex` exactly.

### Group references

`$N` expands to the text captured by group N. `$0` expands to the entire match. `${name}`
expands to the text captured by the named group `name`.

```java
Pattern p = Pattern.compile("(\\w+)@(\\w+\\.\\w+)");
Matcher m = p.matcher("contact us at user@example.com today");
String result = m.replaceAll("[$1 at $2]");
// result: "contact us at [user at example.com] today"
```

Group references expand to an empty string when the group is defined in the pattern but did
not participate in the current match (an optional group that did not match). This is not an
error.

`$0` always expands to the full match text:

```java
Pattern p = Pattern.compile("\\d+");
String result = p.matcher("order 42 item 7").replaceAll("(#$0)");
// result: "order (#42) item (#7)"
```

### The back-off rule for multi-digit references

The scanner reads digit characters greedily. When the accumulated number exceeds the group
count, it applies back-off only for multi-digit references: it drops the last digit and
retreats the cursor by one position, repeating until either the reference fits or the
reference becomes single-digit.

| Replacement | `groupCount()` | Expansion |
|---|---|---|
| `$1` | 3 | Group 1 |
| `$5` | 3 | `IndexOutOfBoundsException` — single-digit reference, no back-off |
| `$15` | 3 | Group 1, then literal `5` — back-off dropped the `5` |
| `$11` | 11 | Group 11 — no back-off needed |

The back-off rule exists because the scanner cannot know in advance how many digits form
the group number. It reads as many digits as possible, then retreats until the number is
valid.

### Escape sequences

`\\` in a replacement string produces a single literal backslash. `\$` produces a literal
dollar sign. Any other character following `\` is appended literally — `\n` produces the
letter `n`, not a newline.

```java
// Replace "1+1" with the literal string "result = $1"
Pattern p = Pattern.compile("1\\+1");
String result = p.matcher("1+1").replaceAll("result = \\$1");
// result: "result = $1"
```

A trailing `\` or `$` with nothing following it throws `IllegalArgumentException`.

### Error conditions

| Condition | Exception |
|---|---|
| Trailing `\` with no following character | `IllegalArgumentException` |
| Trailing `$` with no following character | `IllegalArgumentException` |
| `$` followed by a character that is neither a digit nor `{` | `IllegalArgumentException` |
| `${name}` where `name` is not a named group in the pattern | `IllegalArgumentException` |
| `${...}` with no closing `}` | `IllegalArgumentException` |
| `$N` where N exceeds `groupCount()` and no back-off applies | `IndexOutOfBoundsException` |

### `Matcher.quoteReplacement(String)`

`quoteReplacement` escapes `\` as `\\` and `$` as `\$`, producing a string that
`replaceAll` and `appendReplacement` treat as entirely literal. Use it when the replacement
text comes from user input or any source where `\` and `$` must not be interpreted as
escape or group-reference characters:

```java
String userInput = "price: $5.00";
Pattern p = Pattern.compile("placeholder");
String result = p.matcher("placeholder").replaceFirst(Matcher.quoteReplacement(userInput));
// result: "price: $5.00"   — the $5 is not interpreted as a group reference
```

`quoteReplacement` returns the original string unchanged when it contains no `\` or `$`.

### `appendReplacement` and `appendTail`

`appendReplacement(StringBuilder, String)` and `appendReplacement(StringBuffer, String)`
are interchangeable. `appendTail(StringBuilder)` and `appendTail(StringBuffer)` are
similarly paired. Use `StringBuffer` variants only when callers require `StringBuffer`
compatibility; `StringBuilder` is preferred.

The replacement string passed to `appendReplacement` is processed by the same scanning
rules as `replaceAll`. To pass a literal string as the replacement, wrap it with
`quoteReplacement`:

```java
StringBuffer sb = new StringBuffer();
Matcher m = pattern.matcher(input);
while (m.find()) {
    m.appendReplacement(sb, Matcher.quoteReplacement(computeReplacement(m)));
}
m.appendTail(sb);
String result = sb.toString();
```

`appendReplacement` throws `IllegalStateException` when called before a successful
`find()` or `matches()`. `appendTail` does not require a preceding match; it appends the
remainder of the input from the last append position to the end.

### Functional overloads are not affected

`replaceAll(Function<MatchResult, String>)` and `replaceFirst(Function<MatchResult, String>)`
use the string returned by the function verbatim. The function's return value is not
scanned for `$N` or `\\` sequences. The standard pattern is to construct the final string
inside the function:

```java
// The function returns the replacement directly — no group-reference scanning applied.
String result = pattern.matcher(input).replaceAll(mr -> "[" + mr.group(1) + "]");
```

---

## Beyond basic matching: what Orbit adds

### Atomic groups and possessive quantifiers

To understand why these matter, you need to understand what backtracking costs.

Consider the pattern `(a+)+b` applied to the input `aaaaaaa!`. The outer group tries to
match one or more repetitions of the inner `a+`. The inner `a+` greedily consumes all seven
`a` characters. Then the pattern looks for `b` — and finds `!`. The engine backtracks:
it tries releasing one character from the inner `a+`, making it match six `a` characters,
and tries another repetition of the outer group, which greedily matches the remaining `a`.
Now it again looks for `b` and finds `!`. It backtracks again. And again. The number of
ways to partition seven `a` characters across the inner and outer groups grows
exponentially. On input of length n, this pattern takes O(2^n) time on a backtracking
engine.

```
Input: aaaaaaa!

Attempt 1:  (aaaaaaa)  → no b
Attempt 2:  (aaaaaa)(a)  → no b
Attempt 3:  (aaaaa)(aa)  → no b
Attempt 4:  (aaaaa)(a)(a)  → no b
... continues exponentially
```

This is catastrophic backtracking, and it is the mechanism behind ReDoS (Regular Expression
Denial of Service) attacks. User-supplied patterns are the obvious risk, but well-intentioned
patterns written under deadline pressure have the same problem.

An **atomic group** `(?>...)` commits to its match and discards the backtrack points inside
it. Once `(?>a+)` matches `aaaaaaa`, the engine will not revisit that decision. If the rest
of the pattern fails, the atomic group fails as a unit — it does not try shorter and shorter
matches. The pattern `(?>a+)+b` on `aaaaaaa!` tries one way, fails, and returns immediately
rather than exploring the exponential tree.

A **possessive quantifier** is atomic by another name: `a++` is equivalent to `(?>a+)` for
the case of a single element. The `+` after the quantifier means "no backtracking into this
quantifier". Use it when you know that the greedy match is the only correct one — when
backtracking would never produce a valid overall match anyway. Possessive quantifiers are a
signal that you understand your pattern's structure well enough to make a commitment.

### Unicode property classes

Standard character classes like `[A-Z]` cover only ASCII. For any work involving natural
language, that is not enough. Orbit supports POSIX-style Unicode property classes:

- `\p{Lu}` — uppercase letters in any script (Latin, Greek, Cyrillic, Arabic, ...)
- `\p{Ll}` — lowercase letters
- `\p{L}` — all letters
- `\p{N}` — all numeric characters
- `\p{InGreek}` — characters in the Greek Unicode block
- `\p{IsLatin}` — characters in the Latin script

For IPA work, `\p{IsLatin}` combined with negation can filter out the Latin-extended
range used for phonetic symbols. For matching script-specific text in multilingual corpora,
`\p{InGreek}` is far more correct than a manually enumerated range like `[\u0370-\u03FF]`
— the property class is defined by the Unicode standard and will not silently miss
characters added in later Unicode versions.

The complement is `\P{...}` (uppercase P): `\P{L}` matches any character that is not a
letter.

### Balancing groups

A balancing group extends the pattern syntax to track a counter, allowing you to match
nested or balanced structures that strict regular expressions cannot describe.

Consider matching nested morphological brackets in a dependency annotation:
`[NP [Det the] [N [A big] dog]]`. The nesting depth is not fixed, so `[^]]+` will not
work — it can be fooled by a bracket in the wrong place. A balancing group maintains a
stack counter: it pushes when it sees `[` and pops when it sees `]`, and the match
succeeds only when the counter returns to zero.

The syntax follows the .NET convention: `(?<push>[)` pushes a state named `push` onto the
stack, and `(?<-push>])` pops it. A condition `(?(push)fail)` asserts that the stack is
empty at the end of the match.

Balancing groups route to `BoundedBacktrackEngine` — they are not amenable to linear-time
simulation. Use them for genuinely nested structures. For flat patterns, they are unnecessary
overhead.

### Transducers (preview)

A **transducer** extends a pattern with an output template: it simultaneously matches input
and produces transformed output, in a single pass. The syntax separates the input pattern
from the output template with a colon:

```java
// Compile a transducer that normalises date format.
// Left of ':' is the match pattern; right is the output template.
Transducer t = Transducer.compile("(?<y>\\d{4})-(?<m>\\d{2})-(?<d>\\d{2}):${d}/${m}/${y}");
```

A transducer that recognises element notation could normalise case, expand abbreviations,
or substitute systematic names — all in the same operation that performs the match.
Transducers compose: the output of one can feed the input of the next, building a
transformation pipeline from a chain of patterns.

Transducer evaluation is fully implemented in Phase 6. `Transducer.compile()`, `applyUp()`,
`tryApplyUp()`, `applyDown()`, `tokenize()`, `invert()`, and `compose()` all work. See
`docs/transducer-guide.md` for usage examples and `docs/transducer-api-reference.md` for
method contracts.

---

## How expressions scale: the complexity story

### The backtracking trap

The `(a+)+b` example above is not pathological in the sense of being contrived. Patterns
with this shape appear naturally when people write `(\w+\s*)+` to match a phrase, or
`([^,]+,?)+` to match a comma-separated list. The structure — a quantifier wrapping a
quantifier — is what creates the exponential search space.

The fundamental problem with traditional backtracking engines is that they are optimistic.
They try a match, and when it fails, they undo their most recent decision and try something
else. On adversarial input — or even on legitimate input that simply does not end with the
expected character — the "try something else" path can branch exponentially.

### The three engine tiers

Orbit assigns each pattern to one of three tiers at compile time, based on static analysis
of the pattern's structure. You can inspect the assignment with `pattern.engineHint()`.

**Tier 1: DFA-based engines — O(n) time, guaranteed.**

Patterns without backreferences, lookarounds, or balancing groups receive a `DFA_SAFE` or
`ONE_PASS_SAFE` hint. These patterns can be evaluated by a deterministic finite automaton,
which reads each input character exactly once and never revisits it. There is no backtracking
and no exponential case — the engine runs in time proportional to the length of the input,
regardless of the pattern.

`ONE_PASS_SAFE` is a stricter classification: it identifies patterns where captures can be
populated in a single forward pass without any disambiguation. `DFA_SAFE` patterns with
captures use a hybrid approach where the DFA finds the match boundaries and the PikeVM
(below) fills in the group values.

Note: the DFA engines are implemented but are not yet the active path for `DFA_SAFE`
patterns — those currently route through PikeVM as well. When wired, patterns with these
hints will see a throughput improvement.

**Tier 2: NFA simulation (PikeVM) — O(n × |NFA|) time.**

Patterns that need captures, or that use features the DFA cannot handle, receive a
`PIKEVM_ONLY` hint and run through the PikeVM. Rather than taking one path through the NFA
and backtracking on failure, the PikeVM runs all possible NFA threads simultaneously. At
each input position, every active thread advances by one character. Threads that reach the
same NFA state are merged. The result is that each input position is processed at most once
per NFA state — giving O(n × |NFA|) time, not O(2^n).

This is the workhorse for the broad middle of the pattern space: any pattern with named
groups, lookaheads, or transducer syntax routes here. The `|NFA|` factor is the number of
states in the compiled NFA, which is proportional to the size of the pattern — typically
small compared to the input length in any real application.

**Tier 3: Bounded backtracker — budget-protected.**

Patterns with backreferences (`\1`, `\2`, ...) or balancing groups receive a
`NEEDS_BACKTRACKER` hint and run through `BoundedBacktrackEngine`. This engine does use
backtracking, so it is not linear-time in theory. However, it counts every operation and
throws `MatchTimeoutException` if it exceeds a budget of 1,000,000 operations. The engine
cannot loop forever.

The budget is fixed at 1,000,000 at compile time. There is no runtime API to change it.
If `MatchTimeoutException` is thrown, simplify the pattern, reduce the input size, or
both.

### The prefilter

Before any engine runs, Orbit's meta-engine applies a prefilter. The prefilter scans the
input for a literal substring that must be present in any match. If the pattern contains
a fixed string — `CH3`, `tion`, `NaCl` — the engine extracts it at compile time and uses
it as a gate.

If the pattern is `[A-Z][a-z]?(?<count>\d*)Cl` then `Cl` must appear in any match. The
prefilter scans for `Cl` first. On input with no `Cl`, the prefilter returns immediately
— no match, no engine invocation, essentially free. The match engine only runs at positions
where the prefilter found a candidate.

For patterns with multiple alternation branches, Orbit builds an Aho-Corasick automaton
over the extracted literals, scanning for all of them simultaneously in a single pass. The
prefilter does not guarantee a match — it only rules out positions — so false positives
are harmless; they just cause the engine to verify a position that turns out not to match.

The practical effect on a large corpus scan is significant. If your pattern has a mandatory
five-character literal and the literal occurs in one percent of input positions, the engine
runs at roughly one percent of positions, even if the input is gigabytes long.

### When to reach for which

For most patterns, you do not choose the engine — Orbit chooses it. What you control is
how you write the pattern.

**Simple validation patterns** — checking that an input is a valid date, an element symbol,
or a molecular formula — will compile to `DFA_SAFE` or `ONE_PASS_SAFE`. No worry needed.

**Extraction with named groups** — pulling element symbols, counts, and amounts out of
text — routes to `PIKEVM_ONLY` and runs in O(n × |NFA|) time. Still safe for
high-throughput use.

**Nested or balanced structures** — matching paired brackets in a morphological annotation,
or verifying balanced parentheses in a chemical formula — requires `NEEDS_BACKTRACKER`.
Keep these patterns narrow: the more constrained the input, the fewer backtracks occur
before the budget is relevant.

**High-throughput scanning of large corpora** — let the prefilter do the heavy lifting.
Write patterns with mandatory literal fragments where the domain allows it. `NaCl` is
a much better prefilter anchor than `[A-Z][a-z]?[A-Z][a-z]?`.

You can inspect Orbit's decision at any time:

```java
import com.orbit.api.Pattern;
import com.orbit.util.EngineHint;

Pattern p = Pattern.compile("(?<element>[A-Z][a-z]?)(?<count>\\d*)");
System.out.println(p.engineHint()); // PIKEVM_ONLY
System.out.println(p.isOnePassSafe()); // false

Pattern q = Pattern.compile("[A-Z][a-z]?");
System.out.println(q.engineHint()); // DFA_SAFE
```

---

## A worked example end to end

The input is a fragment of lab notebook text:

```
Dissolve 2.5g of NaCl and 1.0g of CaCO3 in 500mL of H2O.
```

The goal is to extract each chemical formula together with the preceding quantity and unit.
A formula in this context is a sequence of element tokens — an uppercase letter followed
by an optional lowercase letter, followed by an optional digit count — with no spaces
between them.

The pattern, with named groups:

```java
String regex =
    "(?<amount>[0-9]+(?:\\.[0-9]+)?)"    // quantity: integer or decimal
  + "(?<unit>g|mL|L|mol)"               // unit
  + "\\s+of\\s+"                        // separator
  + "(?<formula>(?:[A-Z][a-z]?\\d*)+)"; // one or more element tokens
```

Compile it once and reuse the `Pattern` object across calls — `Pattern` is immutable and
thread-safe, so one instance is safe across all threads:

```java
import com.orbit.api.Pattern;
import com.orbit.api.Matcher;

private static final Pattern FORMULA =
    Pattern.compile(
        "(?<amount>[0-9]+(?:\\.[0-9]+)?)(?<unit>g|mL|L|mol)\\s+of\\s+"
      + "(?<formula>(?:[A-Z][a-z]?\\d*)+)"
    );
```

`Matcher` is not thread-safe — create one per match operation:

```java
String text = "Dissolve 2.5g of NaCl and 1.0g of CaCO3 in 500mL of H2O.";
Matcher m = FORMULA.matcher(text);

while (m.find()) {
    System.out.println("Amount:  " + m.group("amount"));
    System.out.println("Unit:    " + m.group("unit"));
    System.out.println("Formula: " + m.group("formula"));
    System.out.println();
}
```

Running this against the input text produces three matches:

```
Amount:  2.5
Unit:    g
Formula: NaCl

Amount:  1.0
Unit:    g
Formula: CaCO3

Amount:  500
Unit:    mL
Formula: H2O
```

The pattern contains named capturing groups, so Orbit assigns it a `PIKEVM_ONLY` hint.
The PikeVM runs in O(n × |NFA|) time where n is the length of the input text and |NFA|
is the number of compiled NFA states — proportional to the pattern size. For a pattern
of this length, |NFA| is roughly 30–50 states. On a document of 10,000 characters, the
engine performs on the order of 300,000–500,000 operations. That is fast enough for
interactive use and for batch processing of moderately sized corpora.

The prefilter also helps here. The mandatory literal `of` appears in the pattern, and Orbit
extracts it as a prefilter anchor. The engine only runs at positions where `of` appears in
the input — roughly every few hundred characters in typical prose — skipping the rest of
the document without invoking the NFA simulation at all.

`m.group("amount")` returns the string captured by the `amount` group, or `null` if the
group did not participate in the match. `m.group("formula")` returns the full formula
string — `NaCl`, `CaCO3`, `H2O` — as a single value. If you need to split the formula
into individual element tokens, apply a second pattern to the captured formula string.

---

## Practical advice

**Compile once.** `Pattern.compile()` parses the regex, builds the NFA, runs the static
analyzer, extracts prefilter literals, and caches the result. It is not free. Call it at
startup or class-loading time and store the result in a `static final` field. The `Pattern`
object is immutable and thread-safe; share it freely.

**Create `Matcher` per operation.** `Matcher` holds mutable match state — current position,
last result, group captures. Do not share it between threads or reuse it across logically
independent match operations without calling `matcher.reset()` first.

**Use possessive quantifiers when you know the match is unambiguous.** If you are matching
an element symbol at a position and you know there is no way a shorter match would allow
the rest of the pattern to succeed, write `[A-Z][a-z]?+` instead of `[A-Z][a-z]?`. The
possessive form tells the engine not to revisit the decision. This is a meaningful hint for
patterns running in `NEEDS_BACKTRACKER` mode, and it is harmless in other modes.

**Reach for `\p{...}` properties for any Unicode work.** Manually enumerating Unicode
ranges is fragile — ranges shift between Unicode versions, and ranges like `[\u0300-\u036F]`
are hard to read and easy to get slightly wrong. `\p{InCombiningDiacriticalMarks}` is
self-documenting and correct by construction.

**Treat `MatchTimeoutException` as a real signal.** If the engine throws it, the backtrack
budget was exceeded. The right response is not to retry. Either restructure the pattern
(possessive quantifiers and atomic groups often solve the problem), or reduce the input
length, or reject the input as out of scope. Retrying the same pattern on the same input
will produce the same exception.

**If you accept user-supplied patterns, check `engineHint()` before running them on large
input.** A pattern that returns `NEEDS_BACKTRACKER` will be budget-protected, but 1,000,000
operations is still a non-trivial amount of work if you are calling it in a tight loop on
thousands of inputs. Consider rejecting `NEEDS_BACKTRACKER` patterns in contexts where
untrusted input drives both the pattern and the input text.

```java
Pattern p = Pattern.compile(userSuppliedPattern);
if (p.engineHint() == EngineHint.NEEDS_BACKTRACKER) {
    throw new IllegalArgumentException("Pattern requires backtracking; not permitted here.");
}
```

The gap between "it works on my test cases" and "it works at scale" is, more often than
not, a performance assumption that held in testing and failed in production. The engine
tier and the prefilter are designed to make that assumption explicit, inspectable, and
safe by default.

---

## Regex in the age of LLMs

Large language models are, architecturally speaking, the opposite of a regex engine. A
regex engine is a finite automaton: deterministic, formally specified, guaranteed to
terminate, auditable from source to output. An LLM is a stochastic function over token
sequences: given the same prompt twice, it may produce different outputs, and there is no
formal proof it will not say a particular thing — only varying degrees of probabilistic
confidence that it probably will not.

For many applications that gap does not matter. For regulated domains — medical diagnosis
support, legal document analysis, financial compliance screening, chemical hazard
classification — "probably fine" is not a contract that upstream systems can accept. The
same applies to security-sensitive pipelines: a prompt injection attempt does not become
safe because the model is usually robust against it.

The practical consequence is that LLMs and deterministic pattern matchers are not
competing tools. They occupy different layers of the same pipeline.

### The guardrail pattern

The structure is straightforward. Before a user's input reaches the model, run it through
a set of compiled patterns that check for known-bad content. Prompt injection typically
relies on a small vocabulary of phrases — role-playing instructions, delimiter exploits,
meta-instructions like "ignore previous instructions" — that can be encoded as patterns
and rejected at the door. If the input matches, the LLM call never happens.

```java
private static final Pattern INJECTION_GUARD = Pattern.compile(
    "(?i)ignore\\s+(all\\s+)?previous\\s+instructions?"
    + "|(?i)you\\s+are\\s+now\\s+(?:a|an)\\s+\\w+"
    + "|(?i)act\\s+as\\s+(?:if\\s+you\\s+(?:are|were)\\s+)?(?:a|an)\\s+\\w+"
    + "|(?i)disregard\\s+(?:your\\s+)?(?:system\\s+prompt|instructions?)"
);

public Response handle(String userInput) {
    if (INJECTION_GUARD.matcher(userInput).find()) {
        return Response.reject("Input failed safety check.");
    }
    return callModel(userInput);
}
```

The check runs in microseconds. The LLM call takes tens to hundreds of milliseconds, plus
the token cost. Prefiltering input with a known-bad pattern list is therefore also a cost
control: you pay for the model only when the input passes inspection.

A real injection guard will have more branches than the example above, but the structure
stays the same. Pattern objects are compiled once, stored in `static final` fields, and
shared across threads — the `Pattern` object is immutable and thread-safe; the `Matcher`
is not, so create one per request.

### Output validation

After the model responds, the output is another string that can be checked before it
reaches downstream code. If the pipeline contract says the model will return a JSON object
containing an ISO 8601 date and a two-letter country code, verify both before parsing:

```java
private static final Pattern RESPONSE_SHAPE = Pattern.compile(
    "\\{\\s*\"date\"\\s*:\\s*\"(?<date>\\d{4}-\\d{2}-\\d{2})\""
    + "\\s*,\\s*\"country\"\\s*:\\s*\"(?<country>[A-Z]{2})\""
    + "\\s*\\}"
);

public ParsedResult validate(String modelOutput) throws ValidationException {
    Matcher m = RESPONSE_SHAPE.matcher(modelOutput);
    if (!m.matches()) {
        throw new ValidationException("Model output did not match expected schema.");
    }
    return new ParsedResult(m.group("date"), m.group("country"));
}
```

This is cheaper and more precise than catching a `JsonParseException` three method calls
later, and it produces an error message that names the actual contract violation rather
than an internal parsing state.

### PII redaction

LLMs trained on general text will, under the right conditions, reproduce personal
information that appeared in their context window: email addresses from example inputs,
phone numbers from documents passed as context, credit card patterns from financial
documents. Before any model output reaches a user interface or is written to a log, a
redaction pass can mask these patterns:

```java
private static final Pattern PII = Pattern.compile(
    "(?<email>[\\w.+-]+@[\\w-]+\\.[A-Za-z]{2,})"
    + "|(?<phone>\\b(?:\\+?\\d[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b)"
    + "|(?<card>\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b)"
);

public String redact(String modelOutput) {
    StringBuffer sb = new StringBuffer();
    Matcher m = PII.matcher(modelOutput);
    while (m.find()) {
        String replacement = m.group("email") != null  ? "[EMAIL]"
                           : m.group("phone") != null  ? "[PHONE]"
                           : "[CARD]";
        m.appendReplacement(sb, replacement);
    }
    m.appendTail(sb);
    return sb.toString();
}
```

The pattern above is illustrative rather than exhaustive — a production redaction layer
will cover national ID formats, passport numbers, and domain-specific identifiers. The
point is that the scan is a single linear pass over the output string, and the set of
patterns is maintained and audited separately from the model itself.

### The chemistry angle

The document opened with chemistry as a motivating domain. The connection to LLMs is
direct: a model may correctly identify that a passage describes ammonium chloride, but
produce its notation as `NH4Cl`, `[NH4+][Cl-]`, or the IUPAC name depending on context
and training data. Downstream processing — a cheminformatics tool, a hazard database
lookup, a reaction simulator — requires a canonical form.

A transducer (once implemented) handles this naturally: match the model's output fragment
against the set of known notations for a compound, and emit the canonical SMILES
representation. The pattern does the normalisation; the calling code receives a uniform
string without needing to know which notation the model happened to produce.

### Dynamically generated patterns

LLMs can generate regex patterns as part of a tool call — asked to "write a pattern that
matches UK postcodes", a capable model will produce a reasonable one. The risk is that the
generated pattern might contain backreferences or nested quantifiers that route to
`NEEDS_BACKTRACKER`, and then get applied to untrusted user content.

The `engineHint()` check from the practical advice section above is directly applicable
here. Before accepting a model-generated pattern for use in a pipeline, gate on its engine
tier:

```java
Pattern p = Pattern.compile(modelGeneratedPattern);
if (p.engineHint() == EngineHint.NEEDS_BACKTRACKER) {
    throw new IllegalArgumentException(
        "Generated pattern requires backtracking and cannot be used in this context.");
}
```

If the pattern does need to run through the backtracker — because the use case genuinely
requires backreferences — `MatchTimeoutException` is the safety net. The engine counts
operations and throws rather than looping. That is not a substitute for the gate above in
high-throughput paths, but it means a model-generated pattern cannot hang the JVM.

### Unicode in multilingual applications

LLM applications are not ASCII-only. A model serving users across language boundaries
will receive input in Greek, Arabic, Han, Devanagari, and dozens of other scripts. PII
patterns that assume ASCII phone number separators will miss localised formats.
Injection-guard patterns written in English will not catch equivalent attempts in other
languages.

The `\p{L}` property classes and block selectors like `\p{InGreek}` exist precisely for
this. A pattern that needs to detect any word-like token across scripts writes `\p{L}+`
rather than `[A-Za-z]+`. A multilingual PII detector uses script-aware boundaries rather
than ASCII word characters. The Unicode support is not a nice-to-have for global
deployments — it is the baseline.

### What this amounts to

The properties that made regex libraries feel limited in the era of "just use a neural
network for everything" — strict syntax, explicit semantics, guaranteed termination — are
exactly the properties that make them useful as a layer around systems that lack those
properties. The model handles the open-ended understanding; the pattern matcher handles
the formal contract. Each does what the other cannot.

The gap between "the model thinks this is fine" and "the system can prove this is fine"
is where a well-engineered pattern matcher lives.
