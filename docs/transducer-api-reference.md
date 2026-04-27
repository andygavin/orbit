---
title: Transducer API
parent: Transducer
nav_order: 1
---
# Transducer API Reference

**Module:** `orbit-core`
**Package:** `com.orbit.api`
**Class:** `Transducer`

Cross-references:
- Engine dispatch: `docs/engines/README.md`
- Architecture: `docs/architecture.md`
- `EngineHint` values: `com.orbit.util.EngineHint`
- `ProgOptimiser` (epsilon folding): `com.orbit.prog.ProgOptimiser`
- `MatchResult` fields: `com.orbit.prog.MatchResult`

---

## Class: `Transducer`

`public final class Transducer`

An immutable, thread-safe compiled transducer. A `Transducer` holds a `Prog` instruction
array and, when compiled from a `Pair` expression, the original `Pair` AST node required for
`invert()`.

Two `Transducer` objects compiled from the same expression are behaviourally equivalent but
no `equals` or `hashCode` contract is defined. Do not use identity comparison or hash-based
containers to deduplicate transducers.

---

## Static Factory

### `Transducer.compile`

```java
public static Transducer compile(String transducerExpr, TransducerFlag... flags)
```

Parses and compiles a transducer expression. Returns an immutable `Transducer`.

The expression is parsed by the Orbit parser. The `:` separator, when present, is handled
as a `Pair` production in the grammar — it is not a string split. A colon inside a character
class such as `[a-z:]` is not treated as the input/output boundary.

If no `:` is present, the resulting transducer is an **identity transducer**: `applyUp`
returns the matched substring.

**Preconditions:**
- `transducerExpr` is not null.
- `flags` is not null (the varargs array itself; individual elements may be any `TransducerFlag` value).

**Postconditions:**
- The returned `Transducer` is immutable and safe to share across threads.
- The returned `Transducer` retains the original `Pair` AST (if present) for use by `invert()`.

**Throws:**
- `NullPointerException` — `transducerExpr` or `flags` is null.
- `RuntimeException` wrapping `PatternSyntaxException` — malformed input expression, cyclic output expression, or output side contains alternation or quantifiers.

---

## Instance Methods

### `applyUp`

```java
public String applyUp(String input)
```

Applies the transducer to `input`. The entire string must match the input pattern
(full-string match, not find-first). Returns the produced output string.

For an identity transducer, returns the matched substring — which for a full-string match
is `input` itself.

**Preconditions:**
- `input` is not null.

**Postconditions:**
- The returned string is the accumulated output from all `TransOutput` instructions executed during the match.
- The returned string is never null.

**Throws:**
- `NullPointerException` — `input` is null.
- `Transducer.TransducerException` — `input` does not fully match the input pattern.

**Thread safety:** Yes. Each call creates its own execution context.

---

### `tryApplyUp`

```java
public Optional<String> tryApplyUp(String input)
```

Same as `applyUp` but returns `Optional.empty()` instead of throwing on no match.

**Preconditions:**
- `input` is not null.

**Postconditions:**
- Returns `Optional.of(output)` when the input matches.
- Returns `Optional.empty()` when the input does not match.
- Never returns a `Optional` containing null.

**Throws:**
- `NullPointerException` — `input` is null.

**Thread safety:** Yes.

---

### `applyDown`

```java
public String applyDown(String output)
```

Applies the transducer in reverse. Equivalent to `this.invert().applyUp(output)`. Requires
the transducer to be invertible — i.e., compiled directly from a `Pair` expression, not
produced by `compose()`.

**Preconditions:**
- `output` is not null.
- The transducer was compiled directly from a `Pair` expression (not via `compose()`).

**Throws:**
- `NullPointerException` — `output` is null.
- `Transducer.NonInvertibleTransducerException` — the transducer was produced by `compose()`.
- `Transducer.TransducerException` — `output` does not match the inverted input pattern.

**Thread safety:** Yes.

---

### `invert`

```java
public Transducer invert()
```

Returns a new `Transducer` with input and output sides swapped. The returned transducer maps
what the original produces back to what it consumes.

Inversion recompiles the transducer from `Pair(originalPair.output(), originalPair.input(),
weight)` and re-runs all nine HIR analysis passes, including acyclicity validation on the
new output side.

**Preconditions:**
- The transducer was compiled directly from a `Pair` expression.

**Postconditions:**
- The returned `Transducer` is immutable and independent of the original.
- `original.applyUp(s)` returns `r` if and only if `original.invert().applyUp(r)` returns `s`
  (for inputs where both succeed).

**Throws:**
- `Transducer.NonInvertibleTransducerException` — the transducer was produced by `compose()`.

**Thread safety:** Yes. The new `Transducer` is created without modifying `this`.

---

### `tokenize`

```java
public List<Token> tokenize(String input)
```

Scans `input` for all non-overlapping matches (left to right, longest first) and returns a
list of `Token` objects that together partition the entire input string.

- `OutputToken("match", matchedText, start, end, producedOutput)` — for each match.
- `MatchToken("gap", gapText, start, end)` — for each unmatched span between matches.

Indices are half-open `[start, end)` into the original input string. Adjacent tokens are
contiguous: `tokens.get(i).end() == tokens.get(i+1).start()` for all `i`. The first
token's `start` is 0 and the last token's `end` equals `input.length()`.

If no match is found anywhere in `input`, returns a single `MatchToken` covering the full
input.

**Preconditions:**
- `input` is not null.

**Postconditions:**
- The returned list is non-empty.
- The tokens partition `input` completely with no gaps or overlaps.

**Throws:**
- `NullPointerException` — `input` is null.

**Thread safety:** Yes. Creates a new execution context per call.

---

### `tokenizeIterator`

```java
public Iterator<Token> tokenizeIterator(java.io.Reader input)
```

Lazy variant of `tokenize` for streaming input. Reads from `input` and emits tokens on
demand. Use this instead of `tokenize` when the full token list does not fit in memory.

**Throws:**
- `NullPointerException` — `input` is null.
- `java.io.UncheckedIOException` — wraps any `IOException` thrown by `input` during iteration.

**Thread safety:** No. The returned `Iterator` is stateful. Use it on one thread only.

---

### `tokenizeStream`

```java
public Stream<Token> tokenizeStream(java.io.Reader input)
```

Streaming variant wrapping `tokenizeIterator`. The returned stream is sequential and lazy.

Closing the stream does not close the underlying `Reader`. The caller is responsible for
closing the `Reader` after the stream is consumed.

**Throws:**
- `NullPointerException` — `input` is null.
- `java.io.UncheckedIOException` — wraps any `IOException` from `input` during terminal operations.

**Thread safety:** No. The returned `Stream` is stateful. Use it on one thread only.

---

### `compose`

```java
public Transducer compose(Transducer other)
```

Returns a new transducer whose `applyUp(s)` is equivalent to
`other.applyUp(this.applyUp(s))`.

When both transducers have literal-only output sides (no backreferences), `compose` uses
`TransducerGraph` for structural composition. The result is a graph-backed transducer: it
is fully invertible and can be composed further.

When either transducer has backreferences in its output side, `compose` creates a runtime
chain that delegates both `applyUp` calls at execution time. That result has no structural
representation. Calling `invert()`, `applyDown()`, or `compose()` on it throws
`NonInvertibleTransducerException`.

**Preconditions:**
- `other` is not null.

**Postconditions:**
- `result.applyUp(s) == other.applyUp(this.applyUp(s))` for all inputs `s` that match.

**Throws:**
- `NullPointerException` — `other` is null.
- `Transducer.TransducerCompositionException` — the transducers are structurally incompatible. Not thrown for well-typed inputs.

**Thread safety:** Yes. The composed transducer delegates to immutable sub-transducers.

---

## Token Types

`Token` is a sealed interface. Its three permitted implementations are records.

### `OutputToken`

```java
record OutputToken(String type, String value, int start, int end, String output)
    implements Token
```

Represents a matched span.

| Field | Type | Value |
|---|---|---|
| `type` | `String` | Always `"match"` when produced by `tokenize`. |
| `value` | `String` | The matched substring from the input. Never null. |
| `start` | `int` | Inclusive start index into the original input string. |
| `end` | `int` | Exclusive end index into the original input string. |
| `output` | `String` | The transducer output for this match. Never null. |

### `MatchToken`

```java
record MatchToken(String type, String value, int start, int end)
    implements Token
```

Represents an unmatched gap between matches.

| Field | Type | Value |
|---|---|---|
| `type` | `String` | Always `"gap"` when produced by `tokenize`. |
| `value` | `String` | The unmatched text. Never null; may be empty for zero-length gaps. |
| `start` | `int` | Inclusive start index into the original input string. |
| `end` | `int` | Exclusive end index into the original input string. |

### `ErrorToken`

```java
record ErrorToken(String message, int start, int end)
    implements Token
```

Represents a span that `tokenize` could not categorise. Not produced under normal operation.

| Field | Type | Value |
|---|---|---|
| `message` | `String` | Diagnostic description of the error condition. |
| `start` | `int` | Inclusive start index. |
| `end` | `int` | Exclusive end index. |

---

## Exception Taxonomy

All three exception types are `static` nested classes of `Transducer` and extend
`RuntimeException`. All are unchecked.

| Exception | Thrown by | Condition |
|---|---|---|
| `Transducer.TransducerException` | `applyUp` | The input string does not fully match the input pattern. |
| `Transducer.NonInvertibleTransducerException` | `invert()`, `applyDown()` | The transducer was produced by a non-graph-eligible `compose()` and has no structural representation to invert from. |
| `Transducer.TransducerCompositionException` | `compose()` | The transducers are structurally incompatible. Not thrown for well-typed inputs. |

---

## Flags

`TransducerFlag` is an enum in `com.orbit.util`. Flags are passed to `Transducer.compile`.

| Flag | Behaviour |
|---|---|
| `WEIGHTED` | Accepted; no effect. Weighted semiring semantics are not implemented. |
| `INVERTIBLE` | Accepted; no effect. All directly-compiled transducers are invertible by default. |
| `STREAMING` | Accepted; no effect. |
| `UNICODE` | Accepted; no effect. The engine handles Unicode correctly by default. |
| `RE2_COMPAT` | Accepted; no effect. RE2 compatibility is handled at the pattern level. |

---

## Thread-Safety Model

| Object | Thread-safe? | Notes |
|---|---|---|
| `Transducer` (any) | Yes | Immutable after `compile()`. |
| `applyUp` | Yes | Each call creates its own execution context; no shared mutable state. |
| `tryApplyUp` | Yes | Same as `applyUp`. |
| `applyDown` | Yes | Delegates to `invert().applyUp`; no shared mutable state. |
| `tokenize` | Yes | Creates a new execution context per call. |
| `tokenizeIterator` | No | The `Iterator` is stateful. One thread per iterator. |
| `tokenizeStream` | No | The `Stream` is stateful. One thread per stream. |
| Composed `Transducer` (from `compose()`) | Yes | Delegates to immutable sub-transducers. |

---

## Performance Characteristics

**Compilation cost.** `Transducer.compile` runs the full HIR analysis pipeline (nine passes)
and Thompson-construction codegen. Store compiled `Transducer` objects in a `static final`
field or inject them as dependencies. Never recompile inside a loop.

**`applyUp` time complexity.** O(n × |NFA|) where n is the input length and |NFA| is the
number of instructions in the compiled `Prog`. For typical text-rewriting transducers this
is linear in practice.

**Output accumulation.** `applyUp` appends to a `StringBuilder` internally and returns a
new `String` on acceptance. Avoid wrapping the output in further string operations in tight
loops; accumulate directly into your own builder if possible.

**`tokenize` vs. `tokenizeStream`.** `tokenize` builds the complete token list in memory.
For inputs with many short matches, use `tokenizeStream` to avoid the allocation.

---

## Compilation Pipeline

When `Transducer.compile(expr)` is called:

1. `Parser.parse(expr)` produces a `Pair(inputExpr, outputExpr, weight)` AST node, or a
   plain `Expr` if no `:` separator is present.
2. `AnalysisVisitor.analyze(expr)` runs nine HIR analysis passes. Pass 7 checks output
   acyclicity and throws `PatternSyntaxException` for cyclic expressions. Pass 8 assigns
   `EngineHint.PIKEVM_ONLY` to any pattern containing a `Pair` node.
3. `Pattern.buildProg(expr, flags)` compiles the AST to a `Prog` instruction array. For
   `Pair` nodes, the compiler emits input-matching instructions first, then emits
   `TransOutput` instructions for the output side. Each `TransOutput` instruction carries a
   delta string: either a literal or a backreference token (`"$1"`, `"${name}"`).
4. `ProgOptimiser.foldEpsilonChains()` folds epsilon-jump chains in the instruction array,
   including in programs containing `TransOutput` instructions.
5. A `Transducer` object is constructed holding the `Prog`, the original `Pair` AST (for
   `invert()`), and the flags.

At match time, `PikeVmEngine` executes the `Prog`. When it encounters a `TransOutput`
instruction during epsilon-closure computation, it appends the delta to an output buffer,
resolving backreferences against the current capture state. On `Accept`, the buffer becomes
`MatchResult.output()`.

All transducer patterns carry `EngineHint.PIKEVM_ONLY`. `MetaEngine` routes them to
`PikeVmEngine` unconditionally, bypassing `LazyDfaEngine` and `OnePassDfaEngine`.

See `docs/architecture.md` — "Transducer Compilation Path" for the annotated flowchart.

---

## Known Limitations

1. The output side of a `Pair` expression must be a sequence of literals and backreferences.
   Alternation and quantifiers in the output are rejected at compile time.
2. `compose()` on two transducers whose output sides contain backreferences produces a
   one-shot result: `invert()`, `applyDown()`, and further `compose()` calls all throw
   `NonInvertibleTransducerException`. `compose()` on two literal-output transducers
   produces a graph-backed result that is fully invertible.
3. `invert()` on a non-graph-eligible composed transducer throws
   `NonInvertibleTransducerException` — it has no `Pair` AST and no graph to invert.
4. `TransducerFlag.WEIGHTED` does not activate weighted semantics. It is accepted without
   error and has no effect.
