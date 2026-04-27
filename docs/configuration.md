---
title: Configuration
nav_order: 10
---
# Configuration Reference

**Audience:** Operator, Library Integrator
**Purpose:** Describe Orbit's runtime behaviour and the parameters that govern it.

## Overview

Orbit's current implementation has no external configuration API. Behaviour is governed
by constants defined in the source. There is no `OrbitalConfig` class and no system
properties that Orbit reads at startup.

---

## Fixed Parameters

These values are defined as constants in the source and are not configurable at runtime.
They are documented here so operators understand the bounds.

### DFA state cache bound

**Constant:** `DfaStateCache.MAX_STATES = 1024`
**Scope:** Per `Pattern`, shared across all `Matcher` instances for that `Pattern`.

When `LazyDfaEngine` processes a pattern and the number of constructed DFA states reaches
1,024, the cache is flushed and the current match call falls back to `PikeVmEngine`. The
next call rebuilds the cache from scratch. Cache saturation is silent: `MatchResult` is
identical; only throughput is affected.

This limit applies per `Pattern` instance across all `Matcher` instances that share it.

### Backtrack budget

**Constant:** `BoundedBacktrackEngine.DEFAULT_BACKTRACK_BUDGET = 1_000_000`
**Scope:** Per `execute` call on a `NEEDS_BACKTRACKER` pattern.

When the operation counter reaches this value, `MatchTimeoutException` is thrown.
Callers must catch it. The budget is not configurable at runtime.

### Prefilter literal thresholds

These thresholds determine which prefilter implementation is selected at compile time:

| Literal count | Prefilter |
|---|---|
| 0 | `NoopPrefilter` |
| 1–10 | `VectorLiteralPrefilter` |
| 11–500 | `AhoCorasickPrefilter` (NFA mode) |
| > 500 | `AhoCorasickPrefilter` (DFA mode) |

These thresholds are not configurable.

---

## Runtime Behaviour

### Pattern compilation

`Pattern.compile(String)` and `Pattern.compile(String, PatternFlag...)` are synchronous
and deterministic. The result is immutable. Compiled patterns are cached in a static
`ConcurrentHashMap` keyed by `(regex, flagSet)` with an initial capacity of 512 entries.
Subsequent calls with the same arguments return the cached `CompileResult` without
re-running the pipeline. Callers should still reuse `Pattern` instances in hot paths to
avoid the cache lookup cost.

### Matcher creation

`pattern.matcher(CharSequence)` allocates a new `Matcher` bound to the given input. Each
`Matcher` is independent. `Matcher` instances are not thread-safe.

### Exception types

| Exception | When thrown |
|---|---|
| `PatternSyntaxException` | `Pattern.compile` on invalid regex syntax |
| `MatchTimeoutException` | `BoundedBacktrackEngine` exceeds the backtrack budget |
| `IllegalStateException` | `Matcher` method called before a successful match; positional accessors on a no-match `MatchResult` snapshot |
| `IllegalArgumentException` | Malformed replacement string: trailing `\` or `$`, `$` followed by a non-digit non-`{` character, unclosed `${...}`, or unknown named group in replacement |
| `IndexOutOfBoundsException` | Numeric group reference in a replacement string exceeds `groupCount()` and no back-off applies |

---

## How to run tests

```bash
# All tests
mvn test -pl orbit-core

# Specific suite
mvn test -pl orbit-core -Dtest=BasicRegexCompatTest
```

See `STATUS.md` for current test counts.
