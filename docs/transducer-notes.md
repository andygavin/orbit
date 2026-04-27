---
title: Transducer Notes
nav_exclude: true
---
# Transducer Engineering Notes

**Status as of §6.7.4:** Phase 6 (Tier 1) is complete. All 45 enabled `TransducerTest` cases
pass; `testEmptyInput` and `testWeightedTransducer` remain `@Disabled` pending Phase 7/8.

Reference implementation: `../openfst` (OpenFST source, Apache 2.0).

---

## Phase 6 implementation summary

Phase 6 delivers a `Prog`-backed, `PikeVmEngine`-executed transducer integrated into the
existing regex compilation pipeline. The three components that were wired up:

### 1. Compiler (`Pattern.Compiler`)

The `case Pair pair` branch compiles the input side normally then calls
`emitOutputInstructions(pair.output())`, which walks the output expression and emits
`TransOutput` instructions:

- `Literal` → `TransOutput(literal.value(), pc()+1)`
- Numeric `Backref` → `TransOutput("$N", pc()+1)`
- Named `Backref` → `TransOutput("${name}", pc()+1)`
- `Concat` → recurse over each part
- `Group` → recurse into body (structural only on output side)
- `Epsilon` → emit nothing (identity fallback)
- `Union` / `Quantifier` → throw `PatternSyntaxException` (Phase 6 limitation)

### 2. Engine (`PikeVmEngine`)

`TransOutput` is handled in `computeClosure` as an epsilon-like instruction. An
`outputBuffer` (`StringBuilder`) is allocated once per `startPos`, reset at the start
of each closure, and flushed to `MatchResult.output()` on `Accept`. The buffer is `null`
for non-transducer programs (`isTransducer = expr instanceof Pair`), so there is zero
overhead for ordinary regex execution.

Backreference tokens (`$1`, `${name}`) are resolved by `appendResolvedDelta` against
the current capture register state.

### 3. `Transducer.compile` pipeline

Uses `Pattern.buildTransducerCompileResult` → full nine-pass HIR analysis →
`Pattern.Compiler` → `ProgOptimiser`. The original `Pair` AST is retained as
`originalPair` for `invert()`. Composed transducers have `originalPair == null` and
throw `NonInvertibleTransducerException` on `invert()`, `applyDown()`, and further
`compose()`.

---

## Phase boundaries

| Phase | Tier | Description | Status |
|---|---|---|---|
| 6 | 1 | `Prog`-backed, PikeVM-executed. Delivers `applyUp`, `applyDown`, `invert`, `tokenize`, `compose` (one-shot runtime chain). | **Complete** |
| 7 | 2 | `TransducerGraph`-backed. Structural `compose` (invertible result), `invert` on composed, `rmEpsilon`, `toProg`. Restricted to literal-output, anchor-free transducers. | Not started |
| 7 | 2b | `determinize` (Gallic semiring subset construction) + `minimize` (Hopcroft). Produces smaller Progs; not required for correctness. | Not started |
| 8 | — | Weighted mode. `Semiring<W>`, `applyUpAll`, `pushWeights`. | Not started |

---

## Phase 7 design — informed by OpenFST source

### Arc model (`openfst/lib/arc.h`)

OpenFST's core type is `ArcTpl<Weight, Label, StateId>`:

```cpp
struct ArcTpl {
  Label   ilabel;     // input label  (int; 0 = epsilon)
  Label   olabel;     // output label (int; 0 = epsilon)
  Weight  weight;     // semiring weight (ignored for Tier 2)
  StateId nextstate;  // destination state ID (int)
};
```

The Java equivalent for `TransducerGraph` is a record:

```java
record Arc(int ilabel, int olabel, int nextstate) {}
// weight omitted until Phase 8; 0 = epsilon label on either side
```

`StdArc` uses tropical (min-plus) weight; for Tier 2 use a trivial boolean weight
(present/absent) so the slot is ready for Phase 8's `Semiring<W>` without redesign.

### Graph representation (`openfst/lib/vector-fst.h`)

`VectorFst<Arc>` stores each state as a `VectorState` containing:
- `final_weight_` — `Weight::Zero()` means non-final; any other value means final
- `niepsilons_` / `noepsilons_` — cached counts of input-epsilon and output-epsilon arcs
- `arcs_` — `std::vector<Arc>` of outgoing arcs

The Java `TransducerGraph`:

```java
class TransducerGraph {
  int startState;
  List<List<Arc>> arcs;          // arcs.get(s) = outgoing arcs from state s
  boolean[] isFinal;             // isFinal[s] = true if state s is accepting
  // Phase 8: Weight[] finalWeights
}
```

State IDs are dense integers. `addState()` returns the next ID; `addArc(s, arc)` appends
to the arc list for state `s`.

### Invert (`openfst/lib/invert.h`)

OpenFST's `InvertMapper` is:

```cpp
ToArc operator()(const FromArc& arc) const {
    return ToArc(arc.olabel, arc.ilabel, arc.weight, arc.nextstate);
}
```

**O(V + E)**: walk every arc, swap `ilabel ↔ olabel`, done. No recompilation, no AST
required. This is why graph-backed composition enables `invert()` on composed
transducers — the composed graph has real arcs to swap, whereas Tier 1's composed
transducer is a runtime closure with no structural representation.

### Epsilon removal (`openfst/lib/rmepsilon.h`)

`RmEpsilon` uses `EpsilonArcFilter`, which accepts only arcs where **both**
`ilabel == 0 AND olabel == 0`. An arc with `(ilabel=0, olabel='x')` is an *insertion*
arc (output-side epsilon), not a true epsilon — it is NOT removed.

This is critical for Orbit: `TransOutput` instructions currently represent
"consume nothing, emit string" — i.e., insertion arcs `(ilabel=0, olabel=char)`.
These survive `RmEpsilon`. Only the structural epsilon-jumps (`EpsilonJump`,
`SaveCapture` etc.) that emit nothing on either side map to true `(0, 0)` epsilons
and are candidates for removal.

`EpsNormalize` (via `epsnormalize.h`) is a prerequisite for determinization: it uses
the Gallic semiring to move insertion arcs to a canonical position on each path
(either all before or all after real input arcs), making the result of determinization
well-defined.

### Compose (`openfst/lib/compose.h`, `compose-filter.h`)

Product construction over two FSTs. The matching condition is: FST1's `olabel` matches
FST2's `ilabel`. The `ComposeFilter` interface handles epsilon arcs at the seam:

```
FilterState FilterArc(Arc* arc1, Arc* arc2) const;
```

The `SequenceComposeFilter` prevents double-counting epsilon paths by enforcing that
epsilon transitions on one side do not interleave freely with epsilon transitions on the
other. For Orbit, the simplest correct filter is `SequenceComposeFilter`; `AltSequenceComposeFilter`
and `MatchComposeFilter` exist for more complex cases.

The composed state space is pairs `(s1, s2)` of states from FST1 and FST2. After
composition the result is a new `VectorFst` with the product states materialized —
unlike Tier 1 which defers to runtime `applyUp` chaining.

### Determinize (`openfst/lib/determinize.h`, `string-weight.h`)

OpenFST determinizes transducers using the **Gallic semiring** approach (Mohri 2000):
encode each arc's output label as part of the weight (arc becomes an acceptor arc with
Gallic weight), run weighted determinization, then decode. `determinize.h` includes
`string-weight.h` because the output accumulation during subset construction uses
`StringWeight<int>` (longest-common-prefix semiring).

This is the most complex of the four algorithms. For Orbit Tier 2, defer determinization
until `rmEpsilon` and `compose` are working. The implementation strategy:

1. Convert `TransducerGraph` to a `GallicArc`-style graph (encode olabel in weight)
2. Run weighted determinization (standard subset construction with weight accumulation)
3. Decode back to `(ilabel, olabel)` arcs using `FactorWeight`

### Minimize (`openfst/lib/minimize.h`)

Hopcroft-style state merging after determinization. Partition states into equivalence
classes by their output behaviour; merge states in the same class. Requires the FST to
already be deterministic and epsilon-free. Run after `rmEpsilon` + `determinize`.

---

## The backreference constraint — critical for Tier 2 scope

**Standard FST theory requires fixed output alphabets.** Every arc label is a symbol
from a finite alphabet; output is determined by the path, not by the input content.

Orbit's `TransOutput("$1")` means "output whatever group 1 captured" — the actual
output string depends on the input content, not just the path taken. This is a
**register transducer** or **extended transducer**, which is strictly beyond standard
FST theory. No OpenFST operation (`rmEpsilon`, `determinize`, `minimize`, `compose`)
can handle content-dependent outputs.

**Consequence:** Tier 2 graph operations are only applicable to transducers whose
output side contains **no backreferences** — only literals. A transducer such as
`(\d{4})-(\d{2})-(\d{2}):(\1/\2/\3)` (output = backrefs) must remain `Prog`-backed
and routed through PikeVM indefinitely.

**What this means for the API:**

- `Transducer.compile` continues to work for all transducers (with and without backrefs)
- Graph-backed representation is built automatically when there are no backreferences in the output side
- `compose()`, `invert()` on the graph-backed result are then fully supported
- Transducers with backreference outputs remain Tier 1; calling `compose()` on them produces a Tier 1 one-shot composed transducer as today
- A new query method (e.g. `isGraphBacked()` or `isStructurallyComposable()`) can expose which tier a transducer uses

**`Prog` → `TransducerGraph` conversion algorithm** (literal-only output side):

1. Run NFA epsilon-closure from the `Prog` start PC to enumerate reachable states
2. For each closure set, create a `TransducerGraph` state
3. For each consuming instruction (`CharMatch(lo, hi)`) reachable via epsilon from state S:
   - For each char `c` in `[lo, hi]`: emit arc `(ilabel=c, olabel=0, nextstate=...)`
   - Then follow output-side epsilons (`TransOutput(literal)`) from the post-consume PC:
     emit `(ilabel=0, olabel=char)` insertion arcs for each character of the literal
4. `Accept` PC → mark state as final

---

## Remaining decisions needed before implementation

### 1. Arc label representation — use AlphabetMap class IDs

OpenFST uses single `int` labels per arc. Orbit's `CharMatch(lo, hi)` covers a character
range. Expanding every range to individual arcs is impractical — `.` alone would produce
65,536 arcs from one instruction.

**Resolution:** reuse `AlphabetMap` (already in `com.orbit.engine.dfa`, used by
`LazyDfaEngine`). Build one `AlphabetMap` from the `Prog` at graph-construction time;
use equivalence-class IDs as `ilabel` values. Each `CharMatch(lo, hi)` becomes one arc
per class ID whose representative character falls in `[lo, hi]`. The class count is
typically a small number (< 100 for real patterns).

Arc records therefore use class IDs, not raw chars:

```java
record Arc(int iclassId, int olabel, int nextstate) {}
// iclassId: AlphabetMap class ID (0 = epsilon); olabel: raw char (0 = epsilon)
```

The `AlphabetMap` is stored on the `TransducerGraph` alongside the arc list so that
`applyUp` can translate input chars to class IDs at runtime.

### 2. Execution model — Prog for execution, graph for algebra

**Use the Prog for `applyUp`/`tokenize` execution; the graph only for structural
operations (`compose`, `invert`, `rmEpsilon`, `determinize`, `minimize`).**

A graph-backed transducer still holds a `prog` field for runtime execution via
`PikeVmEngine`. The graph is built lazily on the first structural operation and stored
in a new `transducerGraph` field. This means:

- No new engine or executor needed for Tier 2
- `applyUp` / `tokenize` performance is unchanged
- Graph construction cost is paid only when algebraic operations are actually called
- After `compose()` produces a graph-backed result: that result has no `prog` (it was
  never compiled from source), so it needs either a graph-based executor or the ability
  to re-derive a `Prog` from the composed graph

For the composed-transducer execution path, the simplest option is to determinize the
composed graph and construct a new `Prog` from it (graph → Prog compilation, the reverse
of the current pipeline). Alternatively, add a simple NFA simulator that walks the graph
directly. Defer this decision to implementation.

### 3. Literal-only output detection

A transducer is graph-eligible if its output side contains no `Backref` nodes. The check
is a one-pass walk of the output `Expr`:

```java
static boolean isLiteralOnly(Expr outputExpr) {
    return switch (outputExpr) {
        case Backref ignored  -> false;
        case Literal ignored  -> true;
        case Epsilon ignored  -> true;
        case Concat c         -> c.parts().stream().allMatch(Tier2::isLiteralOnly);
        case Group g          -> isLiteralOnly(g.body());
        default               -> false; // Union/Quantifier rejected at compile time anyway
    };
}
```

Identity transducers (no `Pair` at all) are always graph-eligible.

### 4. Anchor instructions in graph construction

`BeginText`, `EndText`, `EndZ`, `BeginLine`, `EndLine`, `BeginG`, `WordBoundary` are
zero-width assertions with no arc-label equivalent in standard FST theory.

**For Tier 2:** restrict graph construction to `Prog` instructions that translate
cleanly — `CharMatch`, `AnyChar`, `Split`/`UnionSplit`, `EpsilonJump`, `SaveCapture`
(ignored — no captures for literal-output), `TransOutput`, `Accept`, `Fail`. If the
Prog contains any anchor instruction, skip graph construction and leave the transducer
`Prog`-backed. This means `compose()` and `invert()` on anchored patterns continue to
use the Tier 1 strategy (or throw for composed invert).

A compile-time flag `isAnchorFree` can be derived from the HIR `startAnchored` /
`endAnchored` fields and a scan for `WordBoundary` / `BeginG` / `BeginLine` /
`EndLine` instructions.

### 5. `Transducer` class field changes

```java
// New field — null until first structural operation, or null if not graph-eligible
private volatile TransducerGraph transducerGraph;

// New field — true if output is literal-only AND no anchor instructions
private final boolean graphEligible;
```

Routing in `compose()`:

- Both transducers `graphEligible` → build product graph → return `Transducer` with graph
- Either not graph-eligible → current Tier 1 runtime-chain behaviour

Routing in `invert()`:

- `graphEligible` → build graph, swap arc labels → return new graph-backed `Transducer`
- Not graph-eligible → recompile from `originalPair` (current behaviour)

The `composedFirst` / `composedSecond` fields remain for Tier 1 fallback paths.

### 6. ComposeFilter selection

Use `SequenceComposeFilter` — the standard filter in OpenFST that prevents
double-counting epsilon paths by enforcing that once FST1 takes a non-epsilon input arc,
FST2 may not take further input epsilons, and vice versa. This is correct for Orbit's
arc model where insertion arcs `(0, char)` appear as output epsilons on the input side.

`AltSequenceComposeFilter` and `MatchComposeFilter` are needed only for lookahead
optimizations; skip for Tier 2.

### 7. Build timing — lazy with memoization

Graph construction is deferred until the first call to `compose()` or `invert()` on a
graph-eligible transducer:

```java
private TransducerGraph getOrBuildGraph() {
    if (transducerGraph == null) {
        synchronized (this) {
            if (transducerGraph == null)
                transducerGraph = TransducerGraph.fromProg(prog);
        }
    }
    return transducerGraph;
}
```

`fromProg(prog)` is the `Prog → TransducerGraph` conversion (with `AlphabetMap`).

### 8. `testEmptyInput` — separate Tier 1 fix, unrelated to Tier 2

The disabled test expects `applyUp("")` to return `""` for a non-matching pattern. The
current implementation throws `TransducerException` per spec C-6. The test was written
for stub behavior. Fix: either update the test expectation to `assertThrows`, or change
the spec. Not a Tier 2 concern.

---

## Key constraints for Phase 7 implementors

- `TransOutput` is currently epsilon-like (no input consumed). In the graph, it maps to
  insertion arcs `(ilabel=0, olabel=char)`, not true `(0,0)` epsilons. `RmEpsilon` will
  NOT remove them.
- The `outputBuffer` reset-per-closure strategy is correct for Phase 6 Prog execution
  and does not transfer to graph construction.
- `ProgOptimiser.foldEpsilonChains` already handles `TransOutput` — verify it does not
  produce instruction sequences that confuse the graph builder (e.g. adjacent `TransOutput`
  chains should still convert correctly to chains of insertion arcs).
- `EngineHint.PIKEVM_ONLY` remains correct. No new hint needed: the composed Prog
  produced by `toProg()` is routed through PikeVM exactly like a directly-compiled one.
- `invert()` on a graph-backed transducer is O(V+E): swap `ilabel ↔ olabel` on every
  arc. No AST retention needed. The Tier 1 `originalPair` field becomes unnecessary for
  graph-backed transducers.

---

## Option B — full design

The `TransducerGraph` is an ephemeral intermediate representation used only during
algebraic operations. It is never stored on the `Transducer` object. Every operation
follows the pattern:

```
Prog  →  fromProg()  →  TransducerGraph  →  [algebra]  →  toProg()  →  Prog
```

`applyUp`, `tokenize`, and all streaming methods always execute against a `Prog` via
PikeVM. No new executor is needed.

---

### Arc model

```java
// com.orbit.transducer
record Arc(int ilabel, int olabel, int nextstate) {
    static final int EPS = 0;  // epsilon on either side

    boolean isTrueEpsilon() { return ilabel == EPS && olabel == EPS; }
    boolean isInsertion()   { return ilabel == EPS && olabel != EPS; }
    boolean isConsuming()   { return ilabel != EPS; }
}
```

Both `ilabel` and `olabel` are raw Unicode code points (BMP: 1–65535) or `EPS` (0).
This mirrors OpenFST's `ArcTpl<Weight>` exactly and makes `invert()` a trivial label
swap. Weight is omitted until Phase 8.

---

### `TransducerGraph` class

```java
// com.orbit.transducer
final class TransducerGraph {
    final int startState;
    final int numStates;
    final List<List<Arc>> outArcs;   // outArcs.get(s) = outgoing arcs from state s
    final boolean[] isFinal;

    // Factory
    static TransducerGraph fromProg(Prog prog) { ... }

    // Algebraic operations — each returns a new TransducerGraph
    TransducerGraph rmEpsilon() { ... }
    TransducerGraph compose(TransducerGraph other) { ... }
    TransducerGraph invert() { ... }

    // Back-compilation
    Prog toProg() { ... }
}
```

All operations are pure (return new graphs; inputs are not modified).

---

### Graph-eligibility

A `Transducer` is graph-eligible if both of the following hold at compile time:

**Output check** — walk the output `Expr` tree; return false if any `Backref` is found:
```java
static boolean isLiteralOutput(Expr e) {
    return switch (e) {
        case Backref ignored          -> false;
        case Literal ignored          -> true;
        case Epsilon ignored          -> true;
        case Concat(var parts)        -> parts.stream().allMatch(TransducerGraph::isLiteralOutput);
        case Group(var body, _, _)    -> isLiteralOutput(body);
        default                       -> false;
    };
}
```
Identity transducers (no `Pair`) are always literal-output.

**Prog check** — scan every instruction; the Prog must contain only these types:
`CharMatch`, `Split`, `UnionSplit`, `EpsilonJump`, `SaveCapture`, `TransOutput`,
`Accept`, `Fail`.

Any other instruction disqualifies the Prog: `AnyChar`, anchor instructions
(`BeginText`, `EndText`, `EndZ`, `BeginLine`, `EndLine`, `BeginG`), `WordBoundary`,
`BackrefCheck`, `Lookahead`/`LookaheadNeg`/`LookbehindPos`/`LookbehindNeg`,
`PossessiveSplit`, `AtomicCommit`, `ConditionalBranchInstr`, balance instructions,
`RepeatMin`, `RepeatReturn`, `ResetMatchStart`.

`AnyChar` is excluded because expanding it to individual arcs (one per code point)
would produce an unmanageably large graph. Anchor and lookaround instructions have no
FST arc equivalent.

Compute `graphEligible` once during `Transducer.compile()` and store it.

---

### `fromProg` algorithm

Produces a `TransducerGraph` where each Prog PC is one graph state (no epsilon-closure
collapsing at this stage — that is `rmEpsilon`'s job):

```
numStates = prog.instrs.length
startState = prog.startPc
isFinal[s] = (prog.instrs[s] instanceof Accept)

For each PC s:
  switch (prog.instrs[s]) {
    Split(next1, next2) or UnionSplit(next1, next2):
      add Arc(EPS, EPS, next1)
      add Arc(EPS, EPS, next2)

    CharMatch(lo, hi, next):
      for each char c in [lo, hi]:
        add Arc(c, EPS, next)

    TransOutput(delta, next):
      // delta is a literal string (graphEligible guarantees no "$N" tokens)
      if delta.isEmpty():
        add Arc(EPS, EPS, next)
      else:
        chain insertion arcs through fresh intermediate states:
          s → Arc(EPS, delta.charAt(0), fresh_s1)
          fresh_s1 → Arc(EPS, delta.charAt(1), fresh_s2)
          ...
          fresh_s_{k-1} → Arc(EPS, delta.charAt(k-1), next)

    EpsilonJump(next):
      add Arc(EPS, EPS, next)

    SaveCapture(_, _, next):
      add Arc(EPS, EPS, next)  // captures irrelevant for literal-output transducers

    Accept:
      mark isFinal[s] = true  // no outgoing arcs

    Fail:
      // no arcs
  }
```

For `TransOutput` with multi-char deltas, intermediate states are allocated beyond
`prog.instrs.length`. Final `numStates` = `prog.instrs.length` + total intermediate
states added.

---

### `rmEpsilon` algorithm

Standard epsilon-closure removal. A true epsilon arc is one where `arc.isTrueEpsilon()`:

```
For each state s:
  closure(s) = all states reachable from s following only true-epsilon arcs (BFS)

Build new graph:
  For each state s:
    isFinal'[s] = any state in closure(s) is final
    outArcs'[s] = all non-epsilon arcs from any state in closure(s)
                  (insertion arcs and consuming arcs both survive)

Remove all true-epsilon arcs from the result.
Prune unreachable states (BFS from startState).
```

---

### `invert` algorithm

O(V + E): swap `ilabel` and `olabel` on every arc:

```java
TransducerGraph invert() {
    List<List<Arc>> inverted = new ArrayList<>(numStates);
    for (int s = 0; s < numStates; s++) {
        List<Arc> newArcs = outArcs.get(s).stream()
            .map(a -> new Arc(a.olabel(), a.ilabel(), a.nextstate()))
            .toList();
        inverted.add(newArcs);
    }
    return new TransducerGraph(startState, numStates, inverted, isFinal.clone());
}
```

No recompilation. No AST required. Works on any graph including composed ones.

---

### `compose` algorithm

Product construction. Require both graphs to be epsilon-free (call `rmEpsilon` first).
States of the composed graph are pairs `(s1, s2)`; represented as `s1 * other.numStates + s2`.

```
startState = (this.startState, other.startState)
isFinal[(s1,s2)] = this.isFinal[s1] && other.isFinal[s2]

BFS from startState:
  For each reached state (s1, s2):
    For each arc a1 in this.outArcs[s1]:
      For each arc a2 in other.outArcs[s2]:
        Match condition:
          (a1.olabel == a2.ilabel)  — FST1 output consumed by FST2 input
        If matched:
          composed arc: (a1.ilabel, a2.olabel, (a1.nextstate, a2.nextstate))
          add to outArcs[(s1,s2)]

        Special cases after rmEpsilon (no true-epsilon arcs remain):
          a1 is insertion (ilabel=EPS, olabel=x), a2 is consuming (ilabel=x):
            → composed arc (EPS, a2.olabel, (a1.next, a2.next))
          a1 is consuming (olabel=EPS) and a2 has insertion (ilabel=EPS, olabel=y):
            → take a2's insertion independently:
              composed arc (EPS, y, (s1, a2.next))  — s1 stays in place
          Both consuming and olabel1 == ilabel2:
            → composed arc (a1.ilabel, a2.olabel, (a1.next, a2.next))

Prune unreachable states.
```

The third case (FST2 taking an insertion while FST1 stays) corresponds to the
`SequenceComposeFilter` state 2 in OpenFST (FST2 is ahead). For the simple patterns
Tier 2 targets, FST2 insertions are rare; include them for correctness.

---

### `toProg` algorithm

Two-pass compilation back to a `Prog` instruction array.

**Pass 1 — compute instruction count per state:**

For state `s` with `k` non-final arcs and `isFinal[s]`:
```
base = 0
if isFinal[s] and k == 0: base = 1  (EpsilonJump to shared Accept)
if isFinal[s] and k > 0:  base = 2  (Split + EpsilonJump to shared Accept)
if !isFinal[s] and k == 0: base = 1 (Fail)
Split instructions = max(0, k - 1)
Arc instructions per arc:
  consuming + output (both labels non-EPS): CharMatch + TransOutput + EpsilonJump = 3
  consuming, no output (olabel=EPS):        CharMatch + EpsilonJump               = 2
  insertion (ilabel=EPS, olabel non-EPS):   TransOutput + EpsilonJump             = 2
Total for state s = base + (k-1) + sum(arc_instructions)
```

A single shared `Accept` instruction is appended at the end of the Prog:
`acceptPc = sum(all state sizes)`.

**Pass 2 — emit instructions:**

`state_pc[s]` = sum of instruction counts for states 0..s-1.

For each state `s` at `state_pc[s]`:
```
cursor = state_pc[s]
arcs = outArcs[s]
k = arcs.size()

// Final-state preamble
if isFinal[s]:
  if k == 0: emit EpsilonJump(acceptPc); continue
  else:       emit Split(cursor+2, first_arc_pc)
              emit EpsilonJump(acceptPc)
              cursor += 2
else if k == 0:
  emit Fail; continue

// Compute arc start PCs
arcPc[0] = cursor + (k-1)
for i in 1..k-1: arcPc[i] = arcPc[i-1] + arc_instruction_count(arcs[i-1])

// Emit Split chain
for i in 0..k-2:
  emit Split(arcPc[i], arcPc[i+1] if i+1<k-1 else arcPc[k-1])
  // Actually emit a left-spine of splits:
  //   Split(arcPc[0], rest_pc)
  //   Split(arcPc[1], rest_pc)  ...

// Emit each arc block
for each arc a at arcPc[i]:
  if a.isConsuming():
    emit CharMatch(a.ilabel, a.ilabel, cursor+1)
    if a.olabel != EPS: emit TransOutput(String.valueOf((char)a.olabel), cursor+1)
  else:  // insertion
    emit TransOutput(String.valueOf((char)a.olabel), cursor+1)
  emit EpsilonJump(state_pc[a.nextstate])
```

Set `prog.startPc = state_pc[startState]`, `prog.acceptPc = acceptPc`.
Run `ProgOptimiser.foldEpsilonChains()` on the result.

---

### `Transducer` class changes

**New field:**
```java
private final boolean graphEligible;
```
Computed in `Transducer(Prog, Pair, TransducerFlag[])` constructor.

**`compose()` routing:**
```java
public Transducer compose(Transducer other) {
    if (this.graphEligible && other.graphEligible) {
        TransducerGraph g1 = TransducerGraph.fromProg(this.prog).rmEpsilon();
        TransducerGraph g2 = TransducerGraph.fromProg(other.prog).rmEpsilon();
        TransducerGraph composed = g1.compose(g2).rmEpsilon();
        Prog newProg = composed.toProg();
        return new Transducer(newProg, /*originalPair=*/null,
                              /*graphEligible=*/true, flags);
    }
    // Tier 1 fallback — runtime chain
    return new Transducer(this, other, flags);
}
```

**`invert()` routing:**
```java
public Transducer invert() {
    if (graphEligible) {
        TransducerGraph g = TransducerGraph.fromProg(prog).rmEpsilon().invert();
        return new Transducer(g.toProg(), /*originalPair=*/null,
                              /*graphEligible=*/true, flags);
    }
    if (originalPair != null) { /* Tier 1 recompile */ }
    throw new NonInvertibleTransducerException(...);
}
```

**New constructor for graph-derived transducers:**
```java
private Transducer(Prog prog, Pair originalPair, boolean graphEligible,
                   TransducerFlag[] flags) { ... }
```

**`originalPair` retention:** no longer needed for `graphEligible` transducers.
Retain it for non-graph-eligible transducers that still use Tier 1 `invert()`.

---

### Test changes

| Test | Change |
|---|---|
| `testNonInvertibleTransducerException` | Now only applies to Tier 1 (non-graphEligible) composed transducers. Add a new test `testComposedInvertibleWhenGraphEligible` asserting that `compose().invert()` succeeds for literal-output transducers. |
| `testEmptyInput` | Separate fix unrelated to Tier 2: update assertion to `assertThrows(TransducerException.class, ...)` per spec C-6. Remove `@Disabled`. |
| New: `testGraphCompose` | Compose two literal-output transducers; assert `applyUp` on composed result. |
| New: `testGraphInvert` | Invert a graph-eligible composed transducer; assert round-trip. |
| New: `testComposeInvertRoundTrip` | `t.compose(u).invert().applyUp(output)` == `t.applyUp(input)` when `u.applyUp(input)` == `output`. |
