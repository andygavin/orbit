---
name: Variable-length lookbehind end-anchor pattern
description: Both PikeVM and BBE need buildEndAnchoredProg to correctly evaluate variable-length lookbehind bodies with lazy quantifiers
type: feedback
---

When implementing variable-length lookbehind (C1), `runSubProgExact(body, input, start, pos)` naively
returns the first match found (shortest for lazy quantifiers), which may end before `pos`. Both
`BoundedBacktrackEngine` and `PikeVmEngine` need a `buildEndAnchoredProg` helper that replaces every
`Accept` instruction with `EndText(n)` and appends a new `Accept` at position `n`. This forces the
engine to only accept at `pos == to`, causing lazy patterns to expand until the window is filled.

**Why:** Without this, `(?<=%b{1,4}?)foo` fails because the lazy body matches `%b` (2 chars) and
returns before reaching the required end position.

**How to apply:** Whenever implementing lookbehind sub-program execution that must match an exact
`[from, to]` window, use `buildEndAnchoredProg(subProg)` before running `execute()` or `runNfa()`.
The same pattern applies to both `BoundedBacktrackEngine.runSubProgExact` and
`PikeVmEngine.runSubProgExact`.
