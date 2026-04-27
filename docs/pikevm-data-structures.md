---
title: PikeVM Data Structures
nav_exclude: true
---
# PikeVM Data Structures: How Orbit Stopped Littering the Heap

This document walks through the three data structures introduced in the Session 1
optimisation of `PikeVmEngine`. The target audience is developers who know Java well but
have not had to think about allocation pressure, GC pauses, or CPU cache behaviour before.
No prior knowledge of automata theory is assumed, though a rough sense that a regex engine
"tries multiple paths through a pattern at once" is helpful.

---

## What the engine was doing before, and why it hurt

The PikeVM is a simulation of a non-deterministic finite automaton (NFA). At each character
position in the input it maintains a set of *active threads* — independent cursors walking
through the compiled pattern, each carrying a copy of whatever capture groups have been
recorded so far.

The original implementation represented this set as an `ArrayList<int[]>`. Each element
was one thread, stored as an `int[]` where the first two slots held the program counter and
a priority number, and the remaining slots held capture-group positions. Every time the
engine advanced past an epsilon transition (a zero-width step like `|` or `*`), it called
`thread.clone()` to fork a copy for the new branch. Every input character started a fresh
`new ArrayList<>()` for the next set of threads.

The visited-state guard — used to detect when the engine is about to step into a program
counter it has already visited this position — was a `boolean[]` of length `nfaSize`,
reset at every character with `Arrays.fill(visited, false)`.

Put those together and consider a 1 MB input string with an average of 10 active NFA
threads per character:

- 1,000,000 characters × 1 `ArrayList` per step = **1,000,000 `ArrayList` objects**
- 1,000,000 characters × 10 threads × roughly 2 `clone()` calls per epsilon pass
  = **~20,000,000 `int[]` objects**
- 1,000,000 `Arrays.fill` calls over an array of, say, 30 instructions
  = **30,000,000 stores** that do nothing but write `false` over values that are already
  effectively stale

Heap objects cost time in two ways. First, allocation itself is not free — the JVM has to
bump a pointer, zero the memory, and eventually trace the object during GC. Second, and
more damaging for throughput, GC pauses. When you allocate at the rate described above, the
young generation fills quickly and minor GCs fire frequently. A minor GC that takes 5 ms
every 50 ms of mutator time cuts throughput by 10% before you have written a single line of
your actual algorithm. On a 1 MB input the situation is worse because the allocation rate
stays high for the entire duration of the search.

Session 1 eliminates all heap allocation from inside the search loop. The three structures
below are how.

---

## SparseSet: clearing without zeroing

The `boolean[]` guard had one job: remember which NFA program counters the engine had
already visited at the current character position, so it would not visit them again.
At the start of each position step the engine reset it with `Arrays.fill(visited, false)`.

`Arrays.fill` over an array of `n` elements is O(n) work. It writes to every element,
regardless of how many were actually set. If the NFA has 50 instructions but only 6 were
live at the previous step, the engine still wrote 50 zeros. On a long input that adds up.

`SparseSet` replaces this with a structure that clears in O(1) by never zeroing anything.

The trick is two parallel arrays, `dense` and `sparse`, plus a counter `size`:

- `dense[0..size)` holds the current members in the order they were added.
- `sparse[id]` holds the index in `dense` where `id` lives — but only for current members.
  For non-members it may hold a stale value left over from a previous use.

Membership is tested with a two-way cross-check:

```java
boolean contains(int id) {
    int idx = sparse[id];
    return idx < size && dense[idx] == id;
}
```

The stale-value problem — where `sparse[id]` points somewhere valid from a previous round —
cannot cause a false positive because of that second condition. `sparse[id]` may be less
than `size` (a slot that is currently occupied), but `dense[sparse[id]]` will hold whatever
occupies that slot now, not `id`. Both conditions must hold simultaneously for `contains` to
return `true`, and they can only both hold if `id` was genuinely added after the last
`clear()`.

Clearing is just:

```java
void clear() {
    size = 0;
}
```

That is it. Neither array is touched. The stale values stay where they are, harmlessly
waiting for the cross-check to expose them as imposters.

Think of it like a guest list where the host does not erase names at the end of the party.
Instead they just draw a line under the last valid entry and point to that line. Anyone
above the line who cross-references correctly against the room number they were given is
still in. Anyone below the line is irrelevant, and anyone above the line whose room number
points back to a different guest is also out. The whole teardown takes one pen stroke.

`SparseSet` is the simplest of the three structures. Its contribution is making the
per-position-step reset cost proportional to the number of states *added* this step, not
the size of the NFA.

---

## ActiveStates: one big array instead of many small ones

This is the structure that eliminated the bulk of the allocation.

### The problem with `List<int[]>`

Each `int[]` thread object was a small, independent heap allocation. When the engine
followed an epsilon transition — a `Split` instruction in the compiled pattern — it called
`thread.clone()` to produce a copy for the alternative branch, because both branches needed
their own capture-group state going forward. A pattern like `(a|b)(c|d)` has two `Split`
instructions; following both from a single thread produced four clones in rapid succession.
Every one of those clones is a new object on the heap that the GC will eventually have to
trace and collect.

### The flat slab

`ActiveStates` replaces the list of small arrays with one large `long[]` called `slotTable`.
All active NFA threads share it. Each possible program counter (state ID) is allocated a
fixed-width *row* in the table. The row width is called `stride`:

```
stride = Math.max(1, numGroups * 2)
```

Two slots (start and end) per capture group, with a floor of 1 to keep the slab
non-empty for patterns with no groups. State `id` occupies `slotTable[id * stride]`
through `slotTable[id * stride + stride - 1]`.

If the NFA has 50 instructions and the pattern has 3 capture groups, `stride` is 6, and
the slab is `50 * 6 = 300` longs — about 2.4 KB. That entire region lives contiguously in
memory. When the position-step loop iterates over active states, it reads from nearby
addresses in order, which plays well with CPU cache lines. The scattered individual `int[]`
allocations did not — each was a separate heap object at an unpredictable address, and
following a list of pointers to them caused cache misses on every dereference.

`Long.MIN_VALUE` marks a slot as unset (the equivalent of `-1` in the old `int[]` layout).

### The clist/nlist swap

Two `ActiveStates` objects, conventionally called `curr` and `next`, are allocated once at
the start of `execute()` and reused for the entire match operation:

```java
ActiveStates curr = new ActiveStates(instrCount, stride);
ActiveStates next = new ActiveStates(instrCount, stride);
```

`curr` holds the threads active at the current character position. As the engine processes
each character it populates `next` with the threads that survive into the following position.
When all current threads have been processed, the references are swapped:

```java
ActiveStates tmp = curr;
curr = next;
next = tmp;
```

That is a reference swap — three local variable assignments, zero allocations. Then
`next.set.clear()` prepares the (now vacated) object for the next step. The slab itself is
not zeroed. Rows are overwritten lazily when a state is activated: before writing a thread
into a row, the engine populates all stride slots. Reading a row that was set by a *previous*
invocation is prevented by the `SparseSet` guard — if a state ID is not in `next.set`, its
row is never read, regardless of what stale data it contains.

The upshot: **zero heap allocations per position step** after the initial setup.

### What stride means and how to read a slot

Given a state ID and a slot index, the value lives at:

```java
slotTable[stateId * stride + slotIdx]
```

Slots `2*i` and `2*i + 1` hold the start and end positions for capture group `i`.
There is no header slot — the PC is the row index itself, not stored inside the row.

`ActiveStates` wraps this arithmetic in `getSlot` and `setSlot`, and `copySlots` wraps
`System.arraycopy` for propagating capture state from one row to another when a consuming
instruction fires. Those three methods are the entire API.

---

## The epsilon stack: iterating instead of recursing

Epsilon transitions are zero-width steps: `Split` (alternation or repetition), `SaveCapture`
(mark a group boundary), `EpsilonJump` (unconditional forward jump), anchor checks, and
lookaheads. Following them does not consume a character; the engine just moves to a new
program counter with the same input position.

The original `addThread` method handled this recursively. Every epsilon transition caused a
recursive call, and each call cloned the current thread array to give the child its own
capture state. Deep patterns — long chains of alternations or deeply nested repetitions —
could produce call stacks hundreds of frames deep, with a heap allocation at each level.

The explicit epsilon stack replaces all of that with a pre-allocated `int[]` and a
push/pop loop.

### How it works

At the start of each closure computation the stack is reset to empty (`epsilonTop = 0`) and
the seed state is pushed as a frame. The main loop pops a frame, dispatches on its type,
and pushes successor frames as needed. No recursion, no `thread.clone()`, no allocation.

There are two frame types:

**FRAME_EXPLORE** — "visit this program counter". Two integers: the PC and the frame type
tag. When popped, the engine looks up the instruction at that PC and pushes appropriate
follow-up frames.

**FRAME_RESTORE_CAPTURE** — "after the left branch of a `SaveCapture` is done, restore
this slot to its previous value before the right branch runs". This is the mechanism that
replaces `thread.clone()` for capture propagation. Instead of forking the capture state
into two independent copies, the engine mutates a single scratch buffer and pushes a restore
frame that undoes the mutation when the alternative needs to run. One buffer, no clones.

### How `Split` preserves match priority without a counter

Priority — "left alternative beats right" — is encoded implicitly by **insertion order**
in the `SparseSet`. The first thread to reach a given program counter claims that row in
the slab; any later thread trying to reach the same PC finds it already in `visited` and
is discarded. No priority numbers, no counter.

This works because of a single rule in how `Split` pushes frames: **right branch is pushed
first, left branch is pushed second**. Because the stack is LIFO, left is popped and
explored before right. Left-branch threads reach consuming states first and get inserted
into `next` first. When the right branch eventually reaches the same states, they are
already claimed.

```java
case Split split -> {
    pushExplore(split.next2());  // right — pushed first, executes second
    pushExplore(split.next1());  // left  — pushed second, executes first (LIFO)
}
```

The same principle applies inside `curr` at match time: states are iterated in insertion
order, so the first `Accept` encountered is always the highest-priority one. The position
loop breaks as soon as it sees an Accept — any further threads in `curr` are lower priority
and cannot override it.

---

## Putting it together: what changed at runtime

Before Session 1, the search loop allocated on almost every iteration: a new `ArrayList`
per character, a new `int[]` per epsilon transition, a full `Arrays.fill` per position step.
On a 1 MB input with a moderately complex pattern this generated tens of millions of
short-lived objects, which the GC had to trace, move, and collect.

After Session 1, the search loop allocates nothing. `curr`, `next`, the scratch buffer, and
the epsilon stack are all created once in `execute()` and reused for every start position and
every character within that search. The only allocations inside the outer loop are the
single `int[]` produced when a match is actually accepted — once per match found, not once
per character.

The slab layout also helps cache behaviour. Because all thread state for a given step lives
in one contiguous `long[]`, the position-step loop accesses memory sequentially. Modern
CPUs prefetch sequential memory access well; a list of pointers to scattered `int[]` objects
does not benefit from this at all.

The gains are not uniform. For patterns that route through the DFA or one-pass engines,
none of this applies — those paths were already allocation-light. The improvement is most
visible on `PIKEVM_ONLY` patterns: those with repeated capturing groups or features the DFA
cannot handle, running over large inputs. The `ip_address` and `log_request` benchmark
scenarios exercise exactly this path.

---

## For readers who want to go further

**Benchmark the difference.** The `ThroughputBenchmark` class in `orbit-benchmarks`
includes the `ip_address` and `log_request` scenarios, which are the canonical stress tests
for the PikeVM path. Both patterns are `PIKEVM_ONLY` and run against ~1 MB corpora. Run
them with the `scenario` parameter:

```
java --enable-preview -jar orbit-benchmarks.jar ThroughputBenchmark -p scenario=ip_address
java --enable-preview -jar orbit-benchmarks.jar ThroughputBenchmark -p scenario=log_request
```

See `/home/acg/development/workspace/orbit/docs/performance-guide.md` for full
build and execution instructions, including how to isolate a single engine for comparison.

**The precise encoding.** The slot layout (capture positions packed into `long` values),
the exact frame layout for both epsilon stack frame types, and the step-by-step invariants
the `curr`/`next` pair must satisfy are specified in
`/home/acg/development/workspace/orbit/optimise/SPECIFICATION.md`, sections 5–7.
Note that the SPECIFICATION.md predates the removal of `FRAME_DEFERRED_RIGHT` and the
priority counter — the implementation is the authoritative reference for those details.

**Session 2 and beyond.** Session 1 targets allocation. The remaining gap against the JDK
on `ip_address` and `log_request` — once allocation pressure is gone — comes from
instruction-dispatch overhead and memory bandwidth. That is the scope of later sessions.
