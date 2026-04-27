---
title: Performance
nav_order: 9
---
# Performance Guide

**Audience:** Performance Engineer, Library Integrator
**Purpose:** Explain what makes Orbit fast, how to measure performance, and what optimizations are planned.

## The Meta-Engine Architecture

Orbit analyzes each pattern at compile time and assigns an `EngineHint`. `MetaEngine`
reads that hint at match time and dispatches to the appropriate engine.

### Current engine routing

| EngineHint | Engine used |
|---|---|
| `DFA_SAFE` | LazyDfaEngine |
| `ONE_PASS_SAFE` | OnePassDfaEngine |
| `PIKEVM_ONLY` | PikeVmEngine |
| `NEEDS_BACKTRACKER` | BoundedBacktrackEngine |
| `GRAMMAR_RULE` | PikeVmEngine |

`DFA_SAFE` and `ONE_PASS_SAFE` patterns run in O(n) time. `MetaEngine.getEngine` routes
them to `LazyDfaEngine` and `OnePassDfaEngine` respectively.

### Time complexity

| Engine | Time complexity | Notes |
|---|---|---|
| LazyDfaEngine | O(n) amortised | 1,024-state cache; falls back to PikeVM on saturation |
| OnePassDfaEngine | O(n) strict | No forking, no second pass for captures |
| PikeVmEngine | O(n × \|NFA\|) | Universal fallback; handles all instruction types |
| BoundedBacktrackEngine | O(budget) | Default budget: 1,000,000 operations |

### Prefilter

`MetaEngine` runs the prefilter before invoking any engine. If the prefilter finds no
candidate position, `MetaEngine` returns no-match without calling an engine.

| Literal count | Prefilter | Algorithm |
|---|---|---|
| 0 | `NoopPrefilter` | Skipped at match time |
| 1–10 | `VectorLiteralPrefilter` | SIMD via `jdk.incubator.vector` (`ShortVector.SPECIES_PREFERRED`): broadcasts the literal's first character across lanes and calls `regionMatches` on hits; falls back to `String.indexOf` for inputs shorter than 32 chars |
| 11–500 | `AhoCorasickPrefilter` (NFA) | Multi-pattern automaton |
| > 500 | `AhoCorasickPrefilter` (DFA) | Determinised Aho-Corasick |

`NoopPrefilter` is used when `CASE_INSENSITIVE` is active or when the pattern's minimum
match length is 0 — cases where literal prefix scanning is not sound.

### SIMD scope: prefilter only

`VectorLiteralPrefilter` benefits from SIMD because its task is strictly data-parallel:
scan a buffer for a fixed byte pattern, applying the same operation to N characters per
cycle. That regularity is what SIMD requires.

The PikeVM cannot benefit. It runs a Thompson NFA simulation: for each input character,
up to |NFA| threads may be active, each carrying its own program counter and capture
registers. Every step involves data-dependent opcode dispatch, per-thread state updates,
and irregular memory access — structurally incompatible with lockstep SIMD computation.

The LazyDFA inner loop is a flat table walk (O(1) per character). The JIT's own
auto-vectorisation handles this well; explicit Vector API use would likely prevent the JIT
from applying its own optimisations.

SIMD investment in Orbit belongs at the prefilter boundary, not inside the engine cores.

### Inspecting the assigned hint

```java
import com.orbit.api.Pattern;
import com.orbit.util.EngineHint;

Pattern p = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]{2,4}");
EngineHint hint = p.engineHint(); // DFA_SAFE

Pattern q = Pattern.compile("(\\w+)\\1");
EngineHint hint2 = q.engineHint(); // NEEDS_BACKTRACKER (backreference)
```

## Compile-time optimizations

The HIR analysis phase performs five passes before compilation:

1. Build HIR tree
2. Extract literal prefix/suffix → `LiteralSet`
3. One-pass safety check
4. Output acyclicity and bounded-length check
5. Engine classification + prefilter selection

Five HIR rewrite rules run as dead-code elimination and simplification passes before the
compiler produces `Prog`. Min/max match length and anchor positions (`startAnchored`,
`endAnchored`) are computed during analysis and stored in `Metadata`. `MetaEngine` uses
anchor positions to short-circuit matching when the pattern is start-anchored and the
candidate position is not position 0.

### ProgOptimiser: EpsilonJump chain folding

`ProgOptimiser.foldEpsilonChains` runs once inside `Pattern.buildCompileResult`, after the
compiler produces its `Prog` and after `Metadata` is assembled. It rewrites the instruction
array in place of the original before the final `Prog` is constructed. No engine code
changes.

**What it does.** The Thompson compiler inserts `EpsilonJump` instructions as connective
tissue between segments: after a quantifier body, around group boundaries, and between
alternation arms. Each `EpsilonJump` does nothing but advance `pc` to its `next` field,
but it still pays the full BBE dispatch cost: a bounds check, a `getInstruction` call, and
a megamorphic `switch` arm. `ProgOptimiser` eliminates these from the execution path by
replacing every PC field in every other instruction with the first non-`EpsilonJump`
address reachable from that field. The `EpsilonJump` nodes remain in the array at their
original indices — the array length is unchanged — but no reachable instruction points to
them after the pass.

**The `resolve` function.** For a given starting PC, `resolve` follows the `EpsilonJump`
chain until it reaches either a non-epsilon instruction or a cycle. Cycle detection uses a
per-call `boolean[]` visited array; a cycle head is returned as its own target (the engine
budget exhausts on such degenerate programs before any infinite loop occurs). `resolve`
runs in O(n) time per call where n is the instruction count; the full fold pass is O(n²)
worst case, which is acceptable for patterns compiled once and cached.

**Sub-program recursion.** `Lookahead`, `LookaheadNeg`, `LookbehindPos`, `LookbehindNeg`,
and `ConditionalBranchInstr` each carry an embedded `Prog body` — an independently
compiled sub-program for the assertion. `ProgOptimiser` recurses into each non-null body
via `foldProg`, applying the same pass. This means lookahead and lookbehind sub-programs
benefit from the same reduction even though they are executed by `runSubProg`, not the
outer BBE `rec()` loop.

**Patterns that benefit most.**

| Pattern structure | Why it benefits |
|---|---|
| Quantifiers (`a+`, `(ab)+`, `\w{3,8}`) | Quantifier bodies compile to loop structures connected by `EpsilonJump` chains |
| Alternation (`a\|b\|c\|d`) | Each arm is separated from the next by `EpsilonJump` links |
| Nested groups | Group open/close boundaries emit `EpsilonJump` connectors |
| Lookahead / lookbehind bodies | Sub-program chains are folded recursively |

Patterns without these structures — bare literals, single character classes — produce few
or no `EpsilonJump` instructions and see no measurable change.

**Expected gain.** The SPECIFICATION.md acceptance criterion requires at least two of the
four `BacktrackerBenchmark` scenarios to improve by ≥ 5% against the Session 3 baseline,
with no scenario regressing by more than 5%. The `backref_short` and `backref_sentence`
patterns (backreference with quantifier) are the primary targets; the spec projects
15–30% fewer dispatch iterations for such patterns.

**Idempotence.** Running `foldEpsilonChains` twice on its own output produces the same
result. `Pattern.compile` caches its output in a `ConcurrentHashMap` keyed by
`(regex, flags)`, so the pass executes at most once per unique pattern regardless.

## JMH Benchmarks

The `orbit-benchmarks` module provides a JMH harness. Build once, then run any combination
of benchmark class and scenario:

```bash
mvn package -pl orbit-benchmarks -am
```

### Benchmark classes

#### `PerformanceBenchmark` — latency, short inputs (~100–200 chars)

Measures average nanoseconds per `find()` / `findAll()` call. Compares Orbit, JDK, and re2j.
Matchers are created in `@Setup`; only execution is timed.

| Scenario | Pattern | Engine hint | Engine |
|---|---|---|---|
| `literal` | `hello` | `DFA_SAFE` | LazyDfaEngine |
| `phone` | `\d{3}-\d{3}-\d{4}` | `ONE_PASS_SAFE` | OnePassDfaEngine |
| `email` | `[a-zA-Z0-9._%+\-]+@…` | `DFA_SAFE` | LazyDfaEngine |
| `alternation` | `error\|warn\|fatal\|…` | `DFA_SAFE` | LazyDfaEngine + AhoCorasick prefilter |
| `ip_address` | `(\d{1,3}\.){3}\d{1,3}` | **`PIKEVM_ONLY`** | **PikeVmEngine** |

#### `ThroughputBenchmark` — throughput, large corpus (~1 MB)

Measures ops/sec scanning the full corpus with `findAll()`. Compares Orbit, JDK, and re2j.
Matchers are created in `@Setup`; corpus generation is excluded from measurement.

| Scenario | Pattern | Engine hint | Engine |
|---|---|---|---|
| `digits` | `\d+` | `ONE_PASS_SAFE` | OnePassDfaEngine |
| `quoted` | `"[^"]*"` | `DFA_SAFE` | LazyDfaEngine + literal prefilter |
| `ip_address` | `(\d{1,3}\.){3}\d{1,3}` | **`PIKEVM_ONLY`** | **PikeVmEngine** |
| `log_request` | `(GET\|POST\|…) \S+ HTTP/…` | **`PIKEVM_ONLY`** | **PikeVmEngine** |
| `dna_motif` | `ACGT+AC` | `DFA_SAFE` | LazyDfaEngine |

#### `MatchingBenchmark` — Orbit only, various operations

Covers `matches()`, `find()`, `findAll()`, `replaceAll()`, `split()`.
**Note:** this class creates a new `Matcher` inside each `@Benchmark` method, so numbers
include `Matcher` allocation overhead — do not use for engine execution comparisons.

#### `BacktrackerBenchmark` — BoundedBacktrackEngine vs PikeVmEngine, backtracker patterns

Directly compares `BoundedBacktrackEngine` and `PikeVmEngine` on patterns that require
backtracking semantics. JDK `java.util.regex` is included as a reference baseline.
Engines are instantiated once in `@Setup`; only `execute()` is timed.

| Scenario | Pattern | Input | Expected winner |
|---|---|---|---|
| `backref_short` | `(\w+)\s+\1` | `"hello hello"` (11 chars) | BoundedBacktrackEngine |
| `backref_sentence` | `(\w+)\s+\1` | Sentence with `"the the"` near end | BoundedBacktrackEngine |
| `backref_long` | `(\w+)\s+\1` | ~10 KB access-log corpus + seeded match | PikeVM expected to tie or win |
| `possessive` | `\w++\s++\w++` | `"hello world"` | BoundedBacktrackEngine; JDK skipped (unsupported syntax) |

**Session 3 baseline** (2026-03-26, JMH AverageTime ns/op, single fork, 5 warmup + 10 × 1 s):

| Scenario | BBE | PikeVM | JDK |
|---|---|---|---|
| `backref_short` | 316.5 ± 15 | 1492.8 ± 183 | 43.8 ± 2.6 |
| `backref_sentence` | 16987.7 ± 1461 | 17551.4 ± 2235 | 794.9 ± 92 |
| `backref_long` | 423132 ± 39960 | 428237 ± 39173 | 33675 ± 3013 |
| `possessive` | 492.8 ± 48 | 1556.1 ± 110 | N/A† |

†JDK `possessive` executes `bh.consume(0)`; `jdkPattern` is null for that scenario.

`backref_short` and `backref_sentence` exercise the BBE sweet spot: short inputs where DFS
avoids PikeVM's per-step thread management overhead.
`backref_long` reveals the crossover point where corpus size dominates.
`possessive` is the only scenario that exercises the `PossessiveSplit` / `AtomicCommit`
instruction path — neither `PerformanceBenchmark` nor `ThroughputBenchmark` cover it.

#### `EngineBenchmark` — engine dispatch overhead

Measures `MetaEngine.getEngine()`, `engine.execute()`, and the full pipeline.
All scenarios hardcode `engineHint = DFA_SAFE` — **PikeVM is never exercised here**.
Useful only for measuring LazyDfaEngine dispatch and MetaEngine overhead.

---

### Running benchmarks

#### Standard run (all scenarios in a class)

```bash
java --enable-preview --add-modules jdk.incubator.vector \
  -jar orbit-benchmarks/target/orbit-benchmarks.jar \
  ThroughputBenchmark
```

#### Single scenario

```bash
java --enable-preview --add-modules jdk.incubator.vector \
  -jar orbit-benchmarks/target/orbit-benchmarks.jar \
  ThroughputBenchmark -p scenario=ip_address
```

#### With async-profiler (flamegraph output)

```bash
java --enable-preview \
  -jar orbit-benchmarks/target/orbit-benchmarks.jar \
  PerformanceBenchmark \
  -prof async:output=flamegraph \
  -p scenario=literal \
  -wi 1 -w 1m \
  -i 1 -r 1s \
  -f 1 \
  2>&1
```

`-w 1m` gives the JIT a full minute to stabilise before measurement begins.
Flamegraph HTML files are written to the working directory alongside `summary-cpu.txt`.

---

### Session 1 gate — PikeVM baseline

Session 1 optimises `PikeVmEngine`. The relevant scenarios are those with
`PIKEVM_ONLY` classification. Capture the following before starting:

```bash
JAR=orbit-benchmarks/target/orbit-benchmarks.jar

# Latency baseline (short input)
java --enable-preview \
  -jar $JAR PerformanceBenchmark \
  -prof async:output=flamegraph \
  -p scenario=ip_address \
  -wi 1 -w 1m -i 10 -r 10s -f 1 2>&1

# Throughput baseline (large corpus — ip_address and log_request both exercise PikeVM)
java --enable-preview \
  -jar $JAR ThroughputBenchmark \
  -prof async:output=flamegraph \
  -p scenario=ip_address \
  -wi 1 -w 1m -i 10 -r 10s -f 1 2>&1

java --enable-preview \
  -jar $JAR ThroughputBenchmark \
  -prof async:output=flamegraph \
  -p scenario=log_request \
  -wi 1 -w 1m -i 10 -r 10s -f 1 2>&1
```

Commit the flamegraph HTML and `summary-cpu.txt` output before applying any changes.

---

### Session 3 gate — BoundedBacktrackEngine baseline

**Status: complete.** Baseline captured 2026-03-26. Optimisations OPT-1, OPT-2, and OPT-3
applied. See `benchmark-backtracker-baseline.txt` in the project root for the full JMH
output (JMH 1.37, JDK 21.0.10, single fork, 5 warmup + 10 measurement iterations × 1 s).

The baseline commands used were:

```bash
JAR=orbit-benchmarks/target/orbit-benchmarks.jar

# Baseline — all four backtracker scenarios
java --enable-preview \
  -jar $JAR BacktrackerBenchmark \
  -prof async:output=flamegraph \
  2>&1 | tee benchmark-backtracker-baseline.txt

# Single scenario with flamegraph (repeat for each scenario of interest)
java --enable-preview \
  -jar $JAR BacktrackerBenchmark \
  -prof async:output=flamegraph \
  -p scenario=backref_short \
  2>&1 | tee benchmark-backtracker-backref_short.txt
```

#### Session 3 results

All figures are JMH AverageTime (ns/op).

| Scenario | BBE baseline | PikeVM baseline | JDK baseline | BBE target | Minimum improvement |
|---|---|---|---|---|---|
| `backref_short` | 316.5 ± 15 | 1492.8 ± 183 | 43.8 ± 2.6 | ≤ 220 | 1.4× |
| `backref_sentence` | 16987.7 ± 1461 | 17551.4 ± 2235 | 794.9 ± 92 | ≤ 11000 | 1.5× |
| `backref_long` | 423132 ± 39960 | 428237 ± 39173 | 33675 ± 3013 | ≤ 340000 | 1.25× |
| `possessive` | 492.8 ± 48 | 1556.1 ± 110 | N/A† | ≤ 540 | no regression |

†The JDK `possessive` figure (~0.36 ns/op) is not a real measurement: `jdkPattern` is null
for that scenario and the benchmark body executes `bh.consume(0)`.

Three optimisations were applied:

- **OPT-3**: `ArrayDeque` pre-sized to `splitCount + 1` (count of `Split` and
  `PossessiveSplit` instructions), eliminating resize copies for normal patterns.
- **OPT-1**: Copy-on-write flag (`capturesDirty`) eliminates the capture-array clone at
  every backtrack restore. The clone is deferred until the first `SaveCapture` write after
  a restore. Reduces capture-array allocations by approximately 50% on backtrack-heavy
  paths.
- **OPT-2**: `initialCaptures` int[], backtrack stack, and balance stacks are hoisted
  outside the start-position loop and reset (`Arrays.fill` / `clear()`) at the top of each
  iteration, eliminating O(n) per-start-position allocation for `backref_sentence` (~43
  positions) and `backref_long` (>10,000 positions).

## Planned optimizations

- Thread pooling and capture array reuse in PikeVM
- `ByteMatcher` for byte-array inputs
- Lookahead/lookbehind sub-engine allocation: `runSubProg` allocates a fresh
  `BoundedBacktrackEngine` per assertion evaluation; fixing this requires a
  context-passing design and is deferred to a later session.
- Capture-array pooling across `execute()` calls in `BoundedBacktrackEngine`: OPT-2
  eliminated per-start-position allocation within a single `execute()` call; pooling the
  arrays between calls would reduce pressure further for callers that invoke `execute()`
  in a tight loop.

## Common anti-patterns

- Creating a new `Pattern` per request for the same regex string — compile once at
  startup and reuse.
- Sharing a `Matcher` across threads — `Matcher` is not thread-safe; create one per thread
  from a shared `Pattern`.
- Using `NEEDS_BACKTRACKER` patterns on adversarial user input without a timeout
  strategy — `MatchTimeoutException` is thrown when the budget is exceeded, but callers
  must catch it.
