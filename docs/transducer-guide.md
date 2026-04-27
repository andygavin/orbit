---
title: Transducer
nav_order: 11
has_children: true
---
# Orbit Transducer Guide

**Audience:** Application developers who know Java and regular expressions.

---

## What a Transducer Is

A transducer is a pattern that both matches input text and produces output text. Where a
plain regex answers "does this string match?", a transducer answers "if this string matches,
what string does it produce?"

The syntax is `input-pattern:output-template`. Everything left of `:` is the input side —
standard Orbit regex syntax. Everything right of `:` is the output side — a template of
literal text and backreferences to groups captured on the input side.

```java
Transducer t = Transducer.compile("(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)");
t.applyUp("2026-03-23");    // returns "2026/03/23"
t.applyDown("2026/03/23");  // returns "2026-03-23"
```

`applyUp` runs the transducer forward: input string in, output string out. `applyDown` runs
it in reverse, treating the output side as the new input. Both directions require a full
string match — the entire argument must match the pattern, not just a substring of it.

A transducer compiled from a plain regex with no `:` is an **identity transducer**: `applyUp`
returns the matched substring unchanged. Identity transducers are useful with `tokenize` to
find and label spans without rewriting them.

---

## Syntax Reference

### Input side

Full Orbit regex syntax. All constructs are supported: capturing groups `()`, named groups
`(?<name>...)`, character classes `[...]`, quantifiers `*`, `+`, `?`, `{n,m}`, anchors `^`
and `$`, alternation `|`, and all other Orbit-supported constructs.

### Output side

The output side appears after `:`.

| Syntax | Meaning | Example expression | Output for `"2026-03-23"` |
|---|---|---|---|
| Plain text | Literal output | `(\d{4})-(\d{2})-(\d{2}):(date)` | `date` |
| `\1`, `\2`, ... | Numbered backreference — emits group N's substring | `(\d{4})-(\d{2})-(\d{2}):(\1/\2/\3)` | `2026/03/23` |
| `${name}` | Named backreference — emits the named group's substring | `(?<y>\d{4})-(?<m>\d{2})-(?<d>\d{2}):(${d}.${m}.${y})` | `23.03.2026` |
| (no `:`) | Identity — `applyUp` returns the matched substring | `\d{4}-\d{2}-\d{2}` | `2026-03-23` |

### Output side restrictions

The output side must be a deterministic sequence of literals and backreferences. Two
constructs are rejected at compile time:

- **Alternation** (`|`) in the output expression.
- **Quantifiers** (`*`, `+`, `{n,m}`) in the output expression.

The **acyclicity requirement** also applies: the output must be bounded in length for any
finite input. The expression `(a*):(b*)` would produce unbounded output and is rejected at
compile time with a `PatternSyntaxException`.

---

## Worked Examples

### 1. Date reformatting

Rewrite ISO dates to slash-separated format, and back.

```java
import com.orbit.api.Transducer;
import com.orbit.api.Token;
import com.orbit.api.OutputToken;
import com.orbit.api.MatchToken;
import java.util.List;
import java.util.Optional;

Transducer dateFmt =
    Transducer.compile("(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)");

// Forward: ISO → slash
dateFmt.applyUp("2026-03-23");       // "2026/03/23"

// Returns Optional.empty() rather than throwing when input does not match
Optional<String> r = dateFmt.tryApplyUp("not a date");  // Optional.empty()

// Reverse: slash → ISO
dateFmt.applyDown("2026/03/23");     // "2026-03-23"

// Tokenize a sentence — finds all ISO dates, leaves gaps intact
List<Token> tokens = dateFmt.tokenize("Today is 2026-03-23, tomorrow 2026-03-24.");
// tokens:
//   MatchToken ("gap",   "Today is ",       0,  9)
//   OutputToken("match", "2026-03-23",       9, 19, "2026/03/23")
//   MatchToken ("gap",   ", tomorrow ",     19, 30)
//   OutputToken("match", "2026-03-24",      30, 40, "2026/03/24")
//   MatchToken ("gap",   ".",               40, 41)
```

`tokenize` scans left to right, taking the longest match at each position. It partitions the
entire input: every character appears in exactly one token, as either a match or a gap.

### 2. Named groups

Named groups make long output templates readable.

```java
Transducer named = Transducer.compile(
    "(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2}):(${day}.${month}.${year})");

named.applyUp("2026-03-23");   // "23.03.2026"
```

Named backreferences (`${name}`) and numbered backreferences (`\1`) can be mixed freely in
the output side.

### 3. Identity transducer

A transducer compiled from a plain regex with no `:` passes matched text through unchanged.

```java
Transducer digits = Transducer.compile("\\d+");

digits.applyUp("42");           // "42"

List<Token> tokens = digits.tokenize("a1b22c333");
// MatchToken ("gap",   "a",   0, 1)
// OutputToken("match", "1",   1, 2, "1")
// MatchToken ("gap",   "b",   2, 3)
// OutputToken("match", "22",  3, 5, "22")
// MatchToken ("gap",   "c",   5, 6)
// OutputToken("match", "333", 6, 9, "333")
```

Identity transducers are useful for tokenizing and labelling spans without rewriting their
content.

### 4. Unicode input

The engine handles Unicode by default. A transducer can match any Unicode code point on
the input side.

```java
Transducer accent = Transducer.compile("\u00e9:(e)");  // é → e

accent.applyUp("\u00e9");      // "e"
```

No flag is needed to enable Unicode support. The `UNICODE` flag is accepted for forward
compatibility but has no effect.

### 5. Composition

`compose(other)` chains two transducers: calling `applyUp` on the result is equivalent to
calling `this.applyUp` and then passing the result to `other.applyUp`.

```java
Transducer isoToSlash = Transducer.compile(
    "(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)");

Transducer slashToText = Transducer.compile(
    "(\\d{4})/(\\d{2})/(\\d{2}):(Year \\1, Month \\2, Day \\3)");

Transducer chain = isoToSlash.compose(slashToText);
chain.applyUp("2026-03-23");   // "Year 2026, Month 03, Day 23"
```

When both transducers have literal-only output sides (no backreferences), `compose` uses
`TransducerGraph` for structural composition. The result is a fully invertible transducer
— `invert()`, `applyDown()`, and further `compose()` all work on it.

When either transducer has backreferences in its output side, `compose` creates a runtime
chain that delegates both `applyUp` calls at execution time. That result is **one-shot**:
calling `invert()`, `applyDown()`, or `compose()` on it throws
`NonInvertibleTransducerException`. See the Gotchas section for details.

---

## Gotchas

### 1. Colon inside a character class is not the separator

The `:` separator is recognised by the Orbit parser, not by naive string splitting. A colon
inside a character class `[a-z:]` is treated as a literal character, not as the
input/output boundary.

```java
Transducer t = Transducer.compile("[a-z:]+:(found)");
t.applyUp("hello:world");    // "found" — the colon matched as a character
```

### 2. `applyUp` requires a full match; `tokenize` does not

`applyUp` requires the entire input string to match the pattern. `tokenize` finds any number
of non-overlapping matches anywhere in the input.

```java
Transducer digits = Transducer.compile("\\d+:(NUM)");

digits.applyUp("123");               // "NUM"
digits.applyUp("abc123");            // throws TransducerException — "abc" is not matched

digits.tokenize("abc123").stream()
    .filter(t -> t instanceof OutputToken)
    .count();                         // 1
```

### 3. Composed transducers with backreferences are one-shot

When both transducers have literal-only output sides, `compose()` uses `TransducerGraph`
for structural composition. The result is invertible and can be composed further.

When either transducer has backreferences in its output side, `compose()` creates a runtime
chain: it delegates both `applyUp` calls at execution time. That result has no structural
representation. Calling `invert()`, `applyDown()`, or `compose()` again on it throws
`NonInvertibleTransducerException`.

If you need an invertible pipeline with backreference transducers, perform all inversions
before composing.

### 4. Output side restrictions

The output expression must be a concatenation of literals and backreferences. Alternation
and quantifiers in the output are rejected at compile time.

```java
// Rejected at compile time — alternation in output
Transducer.compile("(a):(b|c)");

// Rejected at compile time — quantifier in output
Transducer.compile("(a):(b+)");
```

### 5. `invert()` requires an invertible transducer

`invert()` works by recompiling the transducer with the `Pair` AST's input and output sides
swapped. Transducers compiled directly from a `Pair` expression always support inversion.
Transducers produced by `compose()` on two graph-eligible (literal-output) transducers also
support inversion — the result is backed by a `TransducerGraph`. Transducers produced by
`compose()` when either operand has backreferences in its output have no `Pair` AST, so
`invert()` throws `NonInvertibleTransducerException`.

### 6. Unmatched optional groups produce empty output

If a backreference in the output side refers to a capturing group that did not participate
in the match, that backreference contributes an empty string. This matches
`java.util.regex` behaviour.

```java
Transducer t = Transducer.compile("(a)?(b):(\\1\\2)");

t.applyUp("b");    // group 1 unmatched → "" + "b" = "b"
t.applyUp("ab");   // group 1 = "a"     → "a" + "b" = "ab"
```

### 7. The `WEIGHTED` flag does nothing

`TransducerFlag.WEIGHTED` is accepted without error, but it does not activate weighted
semantics. Do not rely on weighted output behaviour.

---

## Streaming tokenization

For large inputs, build the full token list with `tokenize` or avoid it by using the lazy
variants:

```java
import java.io.FileReader;
import java.util.stream.Stream;

Transducer dateFmt = Transducer.compile("(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)");

try (FileReader reader = new FileReader("/var/log/events.log")) {
    Stream<Token> stream = dateFmt.tokenizeStream(reader);
    stream.filter(t -> t instanceof OutputToken)
          .map(t -> ((OutputToken) t).output())
          .forEach(System.out::println);
    // Do not close the stream — closing does not close the underlying Reader
}
```

`tokenizeStream` is sequential and lazy: tokens are produced on demand. The iterator variant
`tokenizeIterator` gives the same behaviour without the `Stream` wrapper. Neither is
thread-safe; use each on one thread only.

---

## Performance notes

Compile once and reuse. `Transducer.compile` runs the full HIR analysis and codegen
pipeline. Recompiling the same expression in a loop wastes that work:

```java
// Wrong — recompiles on every call
void processLine(String line) {
    Transducer.compile("(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)").applyUp(line);
}

// Correct — compile once, reuse the immutable Transducer
private static final Transducer DATE_FMT =
    Transducer.compile("(\\d{4})-(\\d{2})-(\\d{2}):(\\1/\\2/\\3)");

void processLine(String line) {
    DATE_FMT.applyUp(line);
}
```

`applyUp`, `tryApplyUp`, `applyDown`, and `tokenize` are thread-safe and create no shared
mutable state. A single `Transducer` instance can be shared across threads without
synchronisation.

---

## Glossary

| Term | Definition |
|---|---|
| Transducer | A pattern of the form `input-side:output-side` that both matches and transforms text. |
| Identity transducer | A transducer with no `:` separator. `applyUp` returns the matched substring. |
| `applyUp` | Forward application: maps an input string to an output string. Requires a full match. |
| `applyDown` | Reverse application: maps an output string back to an input string. Requires an invertible transducer. |
| `invert()` | Returns a new transducer with input and output sides swapped. |
| `tokenize` | Scans the input for all non-overlapping matches and returns a list of `Token` objects partitioning the input. |
| `OutputToken` | A `Token` representing a matched span; carries the transducer's output for that span. |
| `MatchToken` | A `Token` representing an unmatched gap between matches. |
| Acyclic output | An output expression whose length is bounded for any finite input match. Required for compilation. |
| `Pair` | The AST node for a transducer pattern: `Pair(inputExpr, outputExpr, weight)`. |
| One-shot composed transducer | A transducer returned by `compose()` where either operand has backreferences in its output side. It delegates `applyUp` at runtime and cannot be inverted or recomposed. |
| `EngineHint.PIKEVM_ONLY` | The routing tag assigned to all patterns containing a `Pair` node. All transducer execution goes through `PikeVmEngine`. |
| Tier 1 transducer | A `Prog`-backed, regex-integrated transducer with direct `invert()` support via `Pair` AST recompilation. |
| Tier 2 transducer | A graph-backed FST using `TransducerGraph` for structural compose and invert. Implemented for literal-output transducers (no backreferences on the output side). |
| Delta | The string carried by a `TransOutput` bytecode instruction: either a literal or a backreference token such as `$1` or `${name}`. |
