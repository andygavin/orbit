---
title: Security
nav_order: 8
---
# Security Guide

Orbit is designed to prevent ReDoS and provides bounded resource usage by construction.
This guide documents the security properties of the current implementation.

## ReDoS Protection

Traditional backtracking engines exhibit O(2^n) time on certain patterns with nested
quantifiers. Orbit prevents this through engine selection:

**Linear-time engines (`PikeVmEngine`):** O(n × |NFA|) worst case. The `visited[]` array
ensures each NFA instruction is processed at most once per input position, preventing
exponential blowup. Most patterns route here.

**Bounded backtracker (`BoundedBacktrackEngine`):** Used only for patterns with
backreferences (`NEEDS_BACKTRACKER`). An operation counter terminates matching with
`MatchTimeoutException` when the budget is exceeded. The default budget is 1,000,000
operations.

No engine uses unbounded recursion. All engines use iterative algorithms with explicit
stacks, so input length cannot cause a stack overflow.

### Example

The classic ReDoS pattern `(a+)+` on adversarial input:

```java
Pattern pattern = Pattern.compile("(a+)+");
Matcher matcher = pattern.matcher("aaaaaaaaaaaaaaaaaaaa!");

// DFA_SAFE hint — routes to PikeVmEngine — O(n × |NFA|), not O(2^n)
boolean found = matcher.find();
```

Patterns like `(a+)+` are `DFA_SAFE` because they contain no backreferences or
lookarounds. `PikeVmEngine`'s parallel simulation prevents exponential backtracking.

### Backtracker budget

For `NEEDS_BACKTRACKER` patterns, `MatchTimeoutException` is thrown when the budget is
exceeded:

```java
import com.orbit.engine.MatchTimeoutException;

Pattern p = Pattern.compile("(\\w+)\\1");  // NEEDS_BACKTRACKER (backreference)
Matcher m = p.matcher(adversarialInput);

try {
    m.find();
} catch (RuntimeException e) {
    if (e.getCause() instanceof MatchTimeoutException mte) {
        // Budget exceeded. Do not retry without reducing input size.
        // mte.getInputLength() and mte.getBudget() are available.
    } else {
        throw e;
    }
}
```

The budget is fixed at compile time in `BoundedBacktrackEngine.DEFAULT_BACKTRACK_BUDGET`
(`1_000_000`). There is no runtime API to change it.

## Thread Safety

- **`Pattern`** — immutable after construction; share freely across threads.
- **`Matcher`** — not thread-safe; create one per thread from a shared `Pattern`.
- **`Transducer`** — immutable; share freely. `applyUp`, `applyDown`, `tokenize`, `invert`, and `compose` are thread-safe (Phase 6 complete).
- **`LazyDfaEngine`** — thread-safe; its `DfaStateCache` uses `ConcurrentHashMap`.
- **`PikeVmEngine`** and **`BoundedBacktrackEngine`** — not thread-safe; the `Matcher`
  layer provides per-thread isolation.

```java
// Pattern is thread-safe — share it.
private static final Pattern EMAIL =
    Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

// Matcher is NOT thread-safe — create per call.
public boolean isValidEmail(String email) {
    return EMAIL.matcher(email).matches();
}
```

## Input Validation

`Pattern.compile` throws `PatternSyntaxException` on invalid regex syntax. The exception
includes the pattern string and the error index within it.

```java
try {
    Pattern.compile("[a-z");
} catch (PatternSyntaxException e) {
    System.out.println(e.getMessage());  // describes the syntax error
    System.out.println(e.getIndex());    // position of the error in the pattern
}
```

## Memory Bounds

The `DfaStateCache` in `LazyDfaEngine` is bounded at 1,024 states. When the bound is
reached the cache is flushed and the current match call falls back to `PikeVmEngine`.
Memory usage does not grow unboundedly even for patterns that generate many DFA states.

`AhoCorasickPrefilter` and `VectorLiteralPrefilter` are constructed at compile time and
hold no mutable state. Their size is proportional to the number of literals extracted from
the pattern prefix.

## Dependency Surface

Orbit has no external runtime dependencies. It requires JDK 21 or later. The
`VectorLiteralPrefilter` uses `jdk.incubator.vector` (`ShortVector`) for SIMD-accelerated
literal scanning; this is a standard JDK incubator module with no native code.

No native code, no JNI, no `sun.misc.Unsafe`.

## Best Practices

1. Compile patterns at startup, not per request.
2. Create one `Matcher` per thread from a shared `Pattern`.
3. Catch `MatchTimeoutException` in code that handles `NEEDS_BACKTRACKER` patterns
   on untrusted input.
4. Do not compile user-supplied strings as patterns without validation against an
   allowlist.
5. Patterns that are `DFA_SAFE` or `ONE_PASS_SAFE` (check via `pattern.engineHint()`)
   are safe against ReDoS without a budget.
