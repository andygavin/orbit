---
title: Engines
nav_order: 12
has_children: true
permalink: /engines/
---
# Orbit Execution Engines

Orbit compiles a regex or transducer pattern once into a `Prog` — an immutable array of
typed instructions. At match time, `MetaEngine` selects one of several execution strategies
and runs it against the input. This document describes why multiple engines exist, how
selection works, and what each engine does.

## Why multiple engines?

No single matching algorithm is optimal for all pattern shapes:

- A pure DFA runs in O(n) time with no capture overhead, but cannot track which NFA paths
  contributed to a match, so it cannot fill capture groups on its own.
- A Pike/Thompson NFA simulation is O(n × |NFA|) and tracks captures, but carries more
  per-character work than a DFA for patterns where captures are not needed.
- Bounded backtracking handles constructs — backreferences, possessive quantifiers, atomic
  groups — that neither a DFA nor a pure NFA simulation can express, at the cost of
  potentially non-linear time bounded by an explicit budget.

Orbit analyses each pattern at compile time and annotates it with an `EngineHint`. At match
time `MetaEngine` reads that hint and dispatches to the cheapest engine that is correct for
the pattern.

## Compilation pipeline

```
Pattern string
    │
    ▼
Parser  ──→  AST (Expr)
    │
    ▼
AnalysisVisitor (five passes)
    │  Pass 1: build HIR tree
    │  Pass 2: extract literals → LiteralSet prefix/suffix
    │  Pass 3: one-pass safety check
    │  Pass 4: output acyclicity and bounded length
    │  Pass 5: engine classification → EngineHint
    │           prefilter selection → Prefilter
    ▼
Compiler ──→  Prog  (Instr[], startPc, acceptPc, Metadata)
                          │
                          │  Metadata contains:
                          │    hint       : EngineHint
                          │    prefilter  : Prefilter
                          │    groupCount : int
                          │    minLength  : int
                          │    maxLength  : int
                          │    startAnchored : boolean
                          │    endAnchored   : boolean
                          ▼
                     Pattern object  (immutable, thread-safe to share)
```

## Engine selection (MetaEngine)

`MetaEngine` is a stateless dispatcher — all methods are static and it holds no per-match
state. Its `execute` method runs in two phases:

1. **Prefilter phase.** If the compiled `Prefilter` is non-trivial (i.e., the pattern has
   an extractable literal prefix or inner literals), the prefilter scans the input for a
   candidate start position. If it finds none, `MetaEngine` returns a no-match result
   immediately without invoking any engine.

2. **Engine phase.** `MetaEngine` calls `getEngine(hint)` to resolve the hint to an
   `Engine` instance, then calls `engine.execute(prog, input, candidate, to)`.

```java
// MetaEngine.getEngine — routing table
return switch (hint) {
    case ONE_PASS_SAFE     -> ONE_PASS_DFA;   // OnePassDfaEngine
    case DFA_SAFE          -> LAZY_DFA;        // LazyDfaEngine
    case NEEDS_BACKTRACKER -> BACKTRACKER;     // BoundedBacktrackEngine
    default                -> PIKE_VM;         // PikeVmEngine
};
```

All five engine instances are singletons held as static fields in `MetaEngine`. The
`Matcher` layer ensures per-thread isolation.

### Engine selection flowchart

```mermaid
flowchart TD
    A([match request]) --> B{prefilter trivial?}
    B -- yes --> D
    B -- no  --> C[run prefilter\nfindFirst]
    C --> C2{candidate\nfound?}
    C2 -- no  --> Z([return no-match])
    C2 -- yes --> D[read prog.metadata.hint]
    D --> E{EngineHint}
    E -- ONE_PASS_SAFE --> OP[OnePassDfaEngine\nflat-array table walk\nO(n) strict]
    E -- DFA_SAFE --> LD[LazyDfaEngine\non-demand NFA→DFA\nO(n) amortised]
    E -- NEEDS_BACKTRACKER --> F[BoundedBacktrackEngine\niterative DFS, 1 000 000 op budget\nMatchTimeoutException on overflow]
    E -- PIKEVM_ONLY\nGRAMMAR_RULE --> G[PikeVmEngine\nThompson NFA simulation\nO(n × |NFA|)]
    OP --> H([MatchResult])
    LD --> H
    F --> H
    G --> H
```

### EngineHint values

| Hint | Assigned when | Engine used |
|---|---|---|
| `ONE_PASS_SAFE` | No backrefs, no lookarounds; one-pass safety check passed | `OnePassDfaEngine` (falls back to `LazyDfaEngine` if DFA exceeds 512 states) |
| `DFA_SAFE` | No backrefs, no lookarounds, no balancing groups | `LazyDfaEngine` |
| `PIKEVM_ONLY` | Captures, lookarounds, or transducer `Pair` nodes; not one-pass safe | `PikeVmEngine` |
| `NEEDS_BACKTRACKER` | Backreferences, balancing groups, possessive quantifiers, atomic groups, or conditionals | `BoundedBacktrackEngine` |
| `GRAMMAR_RULE` | Recursive grammar production (grammar layer not yet implemented) | `PikeVmEngine` |

## Engine summary

| Engine | Algorithm | EngineHint(s) routed here | Time complexity | Captures |
|---|---|---|---|---|
| [OnePassDfaEngine](one-pass-dfa.md) | Precomputed flat-array DFA with per-state capture ops | `ONE_PASS_SAFE` | O(n) | Full |
| [LazyDfaEngine](lazy-dfa.md) | On-demand NFA→DFA subset construction | `DFA_SAFE` | O(n) amortised | Boundary only; hybrid PikeVM pass for groups |
| [PikeVmEngine](pike-vm.md) | Thompson NFA simulation | `PIKEVM_ONLY`, `GRAMMAR_RULE` | O(n × \|NFA\|) | Full |
| [BoundedBacktrackEngine](bounded-backtrack.md) | Iterative DFS with op-count budget | `NEEDS_BACKTRACKER` | O(budget) | Full |

## Prefilter selection

`AnalysisVisitor` pass 9 builds a `Prefilter` from the literal prefix extracted in pass 3.
The prefilter runs before any engine is invoked. When `CASE_INSENSITIVE` is active,
`NoopPrefilter` is substituted regardless of extracted literals, because the prefilter
performs case-sensitive matching.

| Literal count | Prefilter chosen |
|---|---|
| 0 (or `CASE_INSENSITIVE` active) | `NoopPrefilter` — skipped at match time |
| 1 | `LiteralIndexOfPrefilter` — `String.indexOf` |
| 2–10 | `VectorLiteralPrefilter` — SIMD scan via `jdk.incubator.vector` |
| > 10 | `AhoCorasickPrefilter` — multi-literal linear scan |

The prefilter is stored in `Prog.metadata` and consulted by `MetaEngine` before any
engine runs.

## Thread safety

`Pattern` objects are immutable after construction and safe to share across threads.
`MetaEngine` itself is stateless. `LazyDfaEngine` is thread-safe (its `DfaStateCache`
uses `ConcurrentHashMap`). `PikeVmEngine` and `BoundedBacktrackEngine` are not
thread-safe; the `Matcher` layer ensures per-thread isolation.

## Individual engine documentation

- [one-pass-dfa.md](one-pass-dfa.md) — Precomputed flat-array DFA with embedded capture tracking
- [lazy-dfa.md](lazy-dfa.md) — Lazy DFA construction with 1,024-state cache
- [pike-vm.md](pike-vm.md) — Pike/Thompson NFA simulation
- [bounded-backtrack.md](bounded-backtrack.md) — Bounded backtracking for backrefs and balancing groups
- [vector-prefilter.md](vector-prefilter.md) — SIMD literal prefilter
