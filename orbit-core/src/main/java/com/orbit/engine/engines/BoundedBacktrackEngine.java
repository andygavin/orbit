package com.orbit.engine.engines;

import com.orbit.engine.Engine;
import com.orbit.engine.MatchTimeoutException;
import com.orbit.prog.Accept;
import com.orbit.prog.AnyChar;
import com.orbit.prog.AtomicCommit;
import com.orbit.prog.BalanceCheckInstr;
import com.orbit.prog.BalancePopInstr;
import com.orbit.prog.BalancePushInstr;
import com.orbit.prog.BackrefCheck;
import com.orbit.prog.BeginG;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.CharMatch;
import com.orbit.prog.ConditionalBranchInstr;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EndZ;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Fail;
import com.orbit.prog.Instr;
import com.orbit.prog.LookbehindNeg;
import com.orbit.prog.LookbehindPos;
import com.orbit.prog.Lookahead;
import com.orbit.prog.LookaheadNeg;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import com.orbit.prog.PossessiveSplit;
import com.orbit.prog.RepeatMin;
import com.orbit.prog.RepeatReturn;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.ResetMatchStart;
import com.orbit.prog.Split;
import com.orbit.prog.UnionSplit;
import com.orbit.prog.WordBoundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded-backtracking NFA interpreter for patterns that require backtracking semantics.
 *
 * <p>Implements a recursive depth-first backtracking engine. The JVM call stack serves as the
 * backtrack stack: each {@link Split} instruction recursively tries its preferred branch; on
 * failure the fallback branch is taken as a tail call via {@code continue}. Capture slot
 * save/restore is performed with local {@code int prev} variables on the JVM stack — no
 * heap-allocated frame or explicit deque is used on the hot path.
 *
 * <p>A monotonically-increasing operation counter and a recursion depth counter are both
 * maintained. If either exceeds its configured limit, {@link MatchTimeoutException} is thrown
 * (wrapped in a {@link RuntimeException}), providing ReDoS protection.
 *
 * <p>Greedy quantifiers are compiled as {@link Split}{@code (body, exit)}: the engine recurses
 * into the body and, on failure, tail-calls to exit. Lazy quantifiers are compiled as
 * {@link Split}{@code (exit, body)}: the engine recurses into exit first. Possessive
 * quantifiers are compiled as a chain of {@link PossessiveSplit}{@code (body, exit)} nodes:
 * each node recurses into its body; if the body fails immediately (r == -1, zero input
 * consumed) the exit is tried; if the body returns {@link #COMMITTED_FAIL} the signal is
 * propagated upward unchanged (no conversion to -1). This propagation ensures that once a
 * possessive loop has consumed input and the continuation fails, no outer context retries
 * from an earlier position.
 *
 * <p>Atomic groups ({@code (?>body)}) are compiled as
 * {@link PossessiveSplit}{@code (body, exit)} followed by {@link AtomicCommit}.
 * {@link AtomicCommit} executes the continuation as a recursive sub-call and returns a
 * special "committed-fail" sentinel ({@value #COMMITTED_FAIL}) on failure, which propagates
 * up through {@link Split} nodes without triggering their fallback branches, enforcing the
 * no-backtrack-into-body invariant.
 *
 * <p>Union alternation ({@code a|b}) is compiled using {@link UnionSplit} rather than plain
 * {@link Split}. {@link UnionSplit} converts {@link #COMMITTED_FAIL} from the preferred branch
 * to {@code -1} before trying the fallback, so that a possessive quantifier in one alternative
 * does not suppress the other alternative.
 *
 * <p>Instances are <em>not</em> thread-safe. Create one instance per thread or per match call.
 */
public class BoundedBacktrackEngine implements Engine {

  static final int DEFAULT_BACKTRACK_BUDGET = 1_000_000;

  /**
   * Maximum recursion depth for {@link #rec}. When exceeded, a {@link RuntimeException}
   * wrapping {@link MatchTimeoutException} is thrown — identical to budget exhaustion so
   * callers handle both cases uniformly.
   */
  static final int MAX_RECURSION_DEPTH = 512;

  /**
   * Special return value from {@link #rec} meaning "an atomic commit or possessive exit was
   * reached and the continuation failed — do not retry any fallback branches within the
   * atomic body or possessive scope."
   *
   * <p>This sentinel propagates upward through {@link Split} (suppressing its fallback)
   * and through {@link SaveCapture}, {@link BalancePushInstr}, and {@link BalancePopInstr}
   * (after performing their cleanup). {@link UnionSplit} converts it to {@code -1} so that
   * the other arm of an alternation can still be tried. {@link PossessiveSplit} converts it
   * to {@link #LOOP_COMMITTED_FAIL} to indicate that a deeper possessive loop has committed.
   */
  static final int COMMITTED_FAIL = -2;

  /**
   * Special return value from {@link #rec} meaning "a possessive loop at a deeper nesting
   * level has committed — do not retry any outer possessive loops or alternation fallbacks."
   *
   * <p>Distinguishes from {@link #COMMITTED_FAIL} so that {@link UnionSplit} does NOT
   * downgrade it to {@code -1}. This prevents the following wrong behaviour: a possessive
   * quantifier {@code (aA|bB)++} iterates several times, then its continuation fails;
   * the {@link #COMMITTED_FAIL} from the deepest iteration propagates through a
   * {@link UnionSplit} inside the loop body (for the {@code |} alternation) which would
   * incorrectly downgrade it to {@code -1}, allowing the outer {@link PossessiveSplit} to
   * try the exit branch at an intermediate position and produce a spurious match.
   *
   * <p>{@link PossessiveSplit} returns this sentinel when its body returns either
   * {@link #COMMITTED_FAIL} or {@link #LOOP_COMMITTED_FAIL}. {@link UnionSplit} propagates
   * it unchanged (no downgrade). {@link Split} propagates it alongside
   * {@link #COMMITTED_FAIL}.
   */
  static final int LOOP_COMMITTED_FAIL = -3;

  private final int budget;

  /**
   * Creates an engine with the default backtrack budget of {@value DEFAULT_BACKTRACK_BUDGET}
   * operations.
   */
  public BoundedBacktrackEngine() {
    this(DEFAULT_BACKTRACK_BUDGET);
  }

  /**
   * Creates an engine with the given backtrack budget.
   *
   * @param budget the maximum number of operations before {@link MatchTimeoutException} is
   *               thrown; must be > 0
   * @throws IllegalArgumentException if {@code budget} is &lt;= 0
   */
  public BoundedBacktrackEngine(int budget) {
    if (budget <= 0) {
      throw new IllegalArgumentException("budget must be > 0, got " + budget);
    }
    this.budget = budget;
  }

  // -----------------------------------------------------------------------
  // Engine.execute implementation
  // -----------------------------------------------------------------------

  /**
   * Executes the compiled program against the given input slice using recursive depth-first
   * backtracking.
   *
   * <p>Searches for the leftmost match starting at each position in {@code [from, to]} in
   * order. Within a start position, greedy quantifiers try the longer branch first, lazy
   * quantifiers try the shorter branch first, and possessive quantifiers commit atomically.
   *
   * @param prog         the compiled program; must not be null
   * @param input        the input string; must not be null
   * @param from         the starting search index (inclusive)
   * @param to           the ending search index (exclusive)
   * @param lastMatchEnd the end position of the previous successful match, for {@code \G}
   *                     support; {@code 0} if no previous match exists
   * @return the match result; never null
   * @throws RuntimeException wrapping a {@link MatchTimeoutException} if the backtrack budget
   *                          or recursion depth limit is exceeded
   */
  @Override
  public MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart) {
    Objects.requireNonNull(prog, "prog must not be null");
    Objects.requireNonNull(input, "input must not be null");

    if (prog.getInstructionCount() == 0) {
      return new MatchResult(true, from, from, List.of(), null, 0L, 0L, 0);
    }

    int numGroups = prog.metadata.groupCount();
    if (numGroups == 0) {
      // Sub-programs compiled for lookahead/lookbehind do not carry metadata groupCount.
      numGroups = countGroups(prog);
    }

    // Detect whether any BalancePushInstr exists. If not, a shared empty sentinel
    // avoids allocating a HashMap entirely for the common case.
    boolean hasBalanceGroups = false;
    for (int i = 0; i < prog.getInstructionCount(); i++) {
      if (prog.getInstruction(i) instanceof BalancePushInstr) {
        hasBalanceGroups = true;
        break;
      }
    }

    // Hoist per-start-position allocations outside the loop.
    // slots is reset with Arrays.fill at the top of each iteration.
    int[] slots = new int[numGroups * 2];

    // balanceStacks: if no balance groups exist, use an unmodifiable empty sentinel forever.
    // Otherwise allocate once and clear inner deques each iteration.
    final Map<String, ArrayDeque<Integer>> balanceStacks;
    if (hasBalanceGroups) {
      balanceStacks = new HashMap<>();
    } else {
      balanceStacks = Map.of();
    }

    // Shared counters threaded through all recursive calls within this execute() invocation.
    long[] ops = {0L};
    long[] bt = {0L};

    // Shared consumed flag: set to true when any character-consuming instruction succeeds.
    // Reset at the start of each startPos iteration. Used by PossessiveSplit to distinguish
    // 0-iteration failure (COMMITTED_FAIL) from N>0-iteration failure (LOOP_COMMITTED_FAIL).
    boolean[] consumed = {false};

    // Tracks the position set by the last executed ResetMatchStart (\K) instruction.
    // -1 means no \K was executed; a non-negative value overrides startPos as the reported
    // match start. Reset to -1 at the start of each startPos iteration.
    int[] keepStart = {-1};

    // Precise hitEnd tracking: set to true when any rec() call had pos reach 'to' during
    // a live exploration path (a consuming instruction checked pos < to and found pos == to).
    // This distinguishes "engine scanned to input boundary" from "engine failed before reaching
    // the boundary" (e.g. a start-anchored pattern that fails at position 0).
    boolean[] reachedEnd = {false};

    for (int startPos = from; startPos <= to; startPos++) {
      Arrays.fill(slots, -1);
      consumed[0] = false;
      keepStart[0] = -1;
      reachedEnd[0] = false;
      if (hasBalanceGroups) {
        for (ArrayDeque<Integer> inner : balanceStacks.values()) {
          inner.clear();
        }
      }

      int endPos = rec(startPos, prog.startPc, slots, ops, bt, 0,
          prog, input, regionStart, to, lastMatchEnd, balanceStacks,
          anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);

      if (endPos >= 0) {
        int reportedStart = (keepStart[0] >= 0) ? keepStart[0] : startPos;
        List<String> groups = buildGroupList(slots, input, numGroups);
        List<int[]> spans = buildGroupSpans(slots, numGroups);
        long bytesScanned = endPos - from;
        // hitEnd on success: true if the match end reaches the region boundary.
        boolean hitEnd = (endPos == to);
        return new MatchResult(
            true, reportedStart, endPos, groups, spans, null,
            ops[0], bytesScanned, (int) Math.min(bt[0], Integer.MAX_VALUE), hitEnd);
      }
    }

    // hitEnd on failure: true if any rec() call had a consuming instruction that reached 'to'
    // (i.e. the engine scanned to the region boundary). False when the engine failed without
    // reaching the boundary — for example a start-anchored pattern failing at position 0.
    boolean hitEndOnFailure = reachedEnd[0];
    return new MatchResult(false, -1, -1, List.of(), List.of(), null, ops[0], to - from, 0, hitEndOnFailure);
  }

  // -----------------------------------------------------------------------
  // Recursive backtracking core
  // -----------------------------------------------------------------------

  /**
   * Recursive backtracking engine core. Executes the program from {@code (pos, initPc)} and
   * returns the new input position on success, {@code -1} on normal failure, or
   * {@link #COMMITTED_FAIL} when an atomic commit was reached but the continuation failed.
   *
   * <p>Instructions that do not need restore on failure are handled as tail calls via
   * {@code continue} in the inner while loop, consuming no additional JVM stack depth.
   * Instructions that require restore on failure ({@link Split}, {@link SaveCapture},
   * {@link PossessiveSplit}, {@link BalancePushInstr}, {@link BalancePopInstr}) each make one
   * recursive call with {@code depth + 1}.
   *
   * <p>{@link AtomicCommit} makes a recursive call and returns {@link #COMMITTED_FAIL} when
   * the continuation fails, preventing {@link Split} fallbacks within the atomic body from
   * being retried. {@link PossessiveSplit} propagates {@link #COMMITTED_FAIL} upward unchanged
   * (no conversion to -1), ensuring that once a possessive loop has consumed input and the
   * continuation fails, no outer context retries from an earlier position.
   *
   * @param pos           current input position; passed by value
   * @param initPc        starting program counter
   * @param slots         mutable capture slot array; shared across all frames
   * @param ops           operation counter array of length 1; shared across all frames
   * @param bt            backtrack counter array of length 1; shared across all frames
   * @param depth         current recursion depth; incremented for each non-tail recursive call
   * @param prog          the compiled program; read-only
   * @param input         the input string; read-only
   * @param from          inclusive start of the valid input range
   * @param to            exclusive end of the valid input range
   * @param balanceStacks named balance stacks; mutated and restored in-place on failure paths
   * @return the input position after a successful match, {@code -1} on normal failure,
   *         {@link #COMMITTED_FAIL} when an atomic commit occurred and the continuation failed,
   *         or {@link #LOOP_COMMITTED_FAIL} when a deeper possessive loop committed and the
   *         continuation failed
   * @throws RuntimeException wrapping {@link MatchTimeoutException} if {@code ops[0] > budget}
   *                          or {@code depth >= MAX_RECURSION_DEPTH}
   */
  private int rec(
      int pos,
      int initPc,
      int[] slots,
      long[] ops,
      long[] bt,
      int depth,
      Prog prog,
      String input,
      int from,
      int to,
      int lastMatchEnd,
      Map<String, ArrayDeque<Integer>> balanceStacks,
      boolean anchoringBounds,
      boolean transparentBounds,
      boolean[] consumed,
      int[] keepStart,
      boolean[] reachedEnd) {
    int pc = initPc;
    while (true) {
      if (pc < 0 || pc >= prog.getInstructionCount()) {
        return -1; // invalid PC treated as failure
      }
      Instr instr = prog.getInstruction(pc);
      switch (instr) {

        case Accept ignored -> {
          return pos; // success: return the current input position
        }

        case RepeatReturn ignored -> {
          // End of one body iteration for a RepeatMin loop.
          // Return pos to the RepeatMin handler, which loops 'count' times.
          return pos;
        }

        case RepeatMin rm -> {
          // Mandatory counted loop: execute body exactly rm.count() times.
          // The body occupies [rm.bodyStart(), rm.bodyEnd()) and is terminated by RepeatReturn.
          int bodyStart = rm.bodyStart();
          int bodyEnd = rm.bodyEnd();
          int count = rm.count();
          for (int i = 0; i < count; i++) {
            pos = rec(pos, bodyStart, slots, ops, bt, depth + 1,
                prog, input, from, to, lastMatchEnd, balanceStacks,
                anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
            if (pos < 0) {
              return -1; // body failed; mandatory repetition cannot be satisfied
            }
          }
          pc = bodyEnd; // continue with the instruction after the loop
          continue;
        }

        case Fail ignored -> {
          return -1; // explicit failure
        }

        case EpsilonJump ej -> {
          pc = ej.next();
          continue; // tail call — no new JVM stack frame
        }

        case ResetMatchStart rms -> {
          // \K: record the current position as the reported match start, then continue.
          // Only the last \K executed before Accept takes effect (each overwrites the prior).
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          keepStart[0] = pos;
          pc = rms.next();
          continue; // epsilon — no new JVM stack frame, no input consumed
        }

        case Split split -> {
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          if (depth >= MAX_RECURSION_DEPTH) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, split.next1(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r >= 0) {
            return r; // preferred branch succeeded
          }
          if (r == COMMITTED_FAIL || r == LOOP_COMMITTED_FAIL) {
            // An atomic commit or a deeper possessive loop committed — its continuation
            // failed. Do not try the fallback; propagate the committed failure upward.
            return r;
          }
          bt[0]++;
          // Normal failure: take the fallback branch as a tail call.
          pc = split.next2();
          continue;
        }

        case UnionSplit us -> {
          // Union alternation split (emitted specifically for | in the parser).
          // Identical to Split except that COMMITTED_FAIL from the preferred branch is
          // downgraded to -1 before the fallback is tried. This allows a possessive
          // quantifier in one alternative to fail without suppressing the other alternative.
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          if (depth >= MAX_RECURSION_DEPTH) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, us.next1(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r >= 0) {
            return r; // preferred branch succeeded
          }
          if (r == LOOP_COMMITTED_FAIL) {
            // A deeper possessive loop committed — propagate unchanged. This sentinel must
            // NOT be downgraded to -1: the loop committed at a position deeper than this
            // alternation, so trying the other arm would produce an incorrect earlier match.
            return LOOP_COMMITTED_FAIL;
          }
          // COMMITTED_FAIL or normal failure: try the fallback arm. A committed failure in
          // one union arm (from an AtomicCommit or PossessiveSplit exit at THIS level) must
          // not prevent the other arm from being tried.
          bt[0]++;
          pc = us.next2();
          continue;
        }

        case PossessiveSplit ps -> {
          // Possessive semantics.
          //
          // Possessive quantifiers are compiled using an AtomicCommit(loopCommit=true) at the
          // loop exit (see Pattern.compileQuantifier). When the greedy loop runs out of body
          // matches and the continuation fails, AtomicCommit returns LOOP_COMMITTED_FAIL
          // (not COMMITTED_FAIL). This signal propagates through UnionSplits inside the body
          // unchanged (UnionSplit downgrades COMMITTED_FAIL but not LOOP_COMMITTED_FAIL),
          // preventing any shallow greedy Split from prematurely taking its exit branch.
          //
          // The PossessiveSplit gate is the absorber of LOOP_COMMITTED_FAIL. It converts
          // this signal — and all other failure modes — into a return value that the outer
          // context can use:
          //
          //  - r >= 0:               body succeeded; propagate the end position.
          //  - r == LOOP_COMMITTED_FAIL or COMMITTED_FAIL:
          //      - bodyConsumedNew=true  → possessive consumed ≥1 chars before failing.
          //                               Return -1 so that outer UnionSplits can try
          //                               alternative arms at the same start position.
          //      - bodyConsumedNew=false → 0-iteration: possessive took the exit immediately
          //                               and the continuation failed. Return COMMITTED_FAIL
          //                               so that split fallbacks on the call stack (e.g. an
          //                               enclosing optional quantifier) are suppressed.
          //  - r == -1 and bodyConsumedNew=false:
          //      0-iteration; inner greedy Split took exit without going through AtomicCommit.
          //      Try the continuation at ps.next2() directly.
          //      Exit failure → COMMITTED_FAIL (outer union may retry other arms).
          //  - r == -1 and bodyConsumedNew=true:
          //      Should not occur with the AtomicCommit loop structure (consuming chars always
          //      reaches AtomicCommit), but handle defensively: return -1.
          //
          // Consumption tracking: consumed[0] is reset at entry to track only THIS gate's
          // body. Merged back (savedConsumed || bodyConsumedNew) after the call so the outer
          // context's consumption accumulates correctly.
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          if (depth >= MAX_RECURSION_DEPTH) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          boolean savedConsumed = consumed[0];
          consumed[0] = false; // reset so body's consumption is tracked fresh
          int r = rec(pos, ps.next1(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          boolean bodyConsumedNew = consumed[0];
          consumed[0] = savedConsumed || bodyConsumedNew; // merge back
          if (r >= 0) {
            return r; // body (plus committed continuation) succeeded
          }
          if (r == LOOP_COMMITTED_FAIL || r == COMMITTED_FAIL) {
            // Absorb LOOP_COMMITTED_FAIL (from this level's loop exit AtomicCommit) and
            // COMMITTED_FAIL (from a nested atomic group). Convert based on whether chars
            // were consumed:
            //  consumed → return -1 so outer union can try alternatives at this startPos
            //  not consumed → 0-iter possessive exit failure → COMMITTED_FAIL
            if (bodyConsumedNew) {
              return -1;
            }
            return COMMITTED_FAIL;
          }
          // r == -1: plain failure.
          if (bodyConsumedNew) {
            // Chars were consumed but no AtomicCommit path taken — defensive; return -1.
            return -1;
          }
          bt[0]++;
          // 0-iteration case: body consumed nothing. Try the continuation directly.
          if (depth >= MAX_RECURSION_DEPTH) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int exitR = rec(pos, ps.next2(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (exitR >= 0) {
            return exitR;
          }
          // Exit failed. Return COMMITTED_FAIL — outer union may retry other alternatives.
          return COMMITTED_FAIL;
        }

        case AtomicCommit ac -> {
          // Run the continuation as a recursive call so that failure propagates as a
          // committed-failure sentinel, preventing Split nodes on the call stack from
          // retrying their fallbacks.
          //
          // Two modes (controlled by ac.loopCommit()):
          //  false (atomic group): return COMMITTED_FAIL. UnionSplit will downgrade this to
          //    -1, allowing outer union alternatives to be tried at the same position.
          //  true (possessive loop exit): return LOOP_COMMITTED_FAIL. UnionSplit does NOT
          //    downgrade LOOP_COMMITTED_FAIL, so it propagates through any UnionSplits
          //    inside the loop body up to the PossessiveSplit gate. The gate converts it
          //    to -1 when the body consumed ≥1 chars.
          if (depth >= MAX_RECURSION_DEPTH) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, ac.next(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r >= 0) {
            return r;
          }
          return ac.loopCommit() ? LOOP_COMMITTED_FAIL : COMMITTED_FAIL;
        }

        case SaveCapture sc -> {
          int slot = sc.groupIndex() * 2 + (sc.isStart() ? 0 : 1);
          if (slot >= slots.length) {
            // Slot out of range — skip write, continue as epsilon.
            pc = sc.next();
            continue;
          }
          int prev = slots[slot]; // save previous value on the JVM stack
          slots[slot] = pos;      // write new value
          if (depth >= MAX_RECURSION_DEPTH) {
            slots[slot] = prev; // restore before throwing
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, sc.next(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r < 0) {
            slots[slot] = prev; // restore on any failure (including COMMITTED_FAIL)
            if (r == -1) {
              bt[0]++;
            }
            return r; // propagate -1, COMMITTED_FAIL, or LOOP_COMMITTED_FAIL
          }
          return r; // success: propagate position upward
        }

        case CharMatch cm -> {
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          if (pos < to
              && input.charAt(pos) >= cm.lo()
              && input.charAt(pos) <= cm.hi()) {
            consumed[0] = true;
            pos++;
            pc = cm.next();
            continue; // consuming tail call
          }
          // When pos == to, the engine reached the region boundary without matching.
          // This means more input could have changed the result — record it.
          if (pos >= to) {
            reachedEnd[0] = true;
          }
          return -1; // character mismatch — failure
        }

        case AnyChar anyChar -> {
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          if (pos < to) {
            consumed[0] = true;
            pos++;
            pc = anyChar.next();
            continue; // consuming tail call
          }
          // pos == to: engine reached region boundary — record hit-end.
          reachedEnd[0] = true;
          return -1;
        }

        case BackrefCheck br -> {
          ops[0]++;
          if (ops[0] > budget) {
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int capStartSlot = br.groupIndex() * 2;
          int capEndSlot = capStartSlot + 1;
          if (capStartSlot < slots.length && capEndSlot < slots.length) {
            int capStart = slots[capStartSlot];
            int capEnd = slots[capEndSlot];
            if (capStart >= 0 && capEnd >= 0) {
              int capturedLen = capEnd - capStart;
              if (capturedLen == 0) {
                // Zero-length backreference always matches; tail call.
                pc = br.next();
                continue;
              }
              if (pos + capturedLen <= to
                  && input.regionMatches(br.caseInsensitive(), pos, input, capStart, capturedLen)) {
                consumed[0] = true;
                pos += capturedLen;
                pc = br.next();
                continue; // consuming tail call
              }
              // If we can't fit the captured text because pos is at or past to, record hit-end.
              if (pos >= to) {
                reachedEnd[0] = true;
              }
            }
          }
          return -1; // backreference mismatch or group not captured
        }

        case BeginText beginText -> {
          if (beginTextPasses(pos, from, anchoringBounds)) {
            pc = beginText.next();
            continue;
          }
          return -1;
        }

        case EndText et -> {
          if (endTextPasses(pos, to, from, input.length(), anchoringBounds)) {
            pc = et.next();
            continue;
          }
          return -1;
        }

        case BeginLine bl -> {
          if (beginLinePasses(input, pos, from, anchoringBounds, bl.unixLines(),
              bl.perlNewlines())) {
            pc = bl.next();
            continue;
          }
          return -1;
        }

        case EndLine el -> {
          if (endLinePasses(input, pos, to, from, input.length(), anchoringBounds,
              el.multiline(), el.unixLines(), el.perlNewlines())) {
            pc = el.next();
            continue;
          }
          return -1;
        }

        case EndZ ez -> {
          if (endZPasses(input, pos, to, from, input.length(), anchoringBounds,
              ez.unixLines(), ez.perlNewlines())) {
            pc = ez.next();
            continue;
          }
          return -1;
        }

        case BeginG bg -> {
          if (pos == lastMatchEnd) {
            pc = bg.next();
            continue;
          }
          return -1;
        }

        case WordBoundary wb -> {
          boolean boundary = isWordBoundary(input, pos, to, wb.unicodeCase());
          if (wb.negated() ? !boundary : boundary) {
            pc = wb.next();
            continue;
          }
          return -1;
        }

        case Lookahead la -> {
          // Under transparent bounds, lookahead can see past regionEnd (to full input length).
          int laTo = transparentBounds ? input.length() : to;
          if (runSubProg(la.body(), input, pos, laTo)) {
            pc = la.next();
            continue;
          }
          return -1;
        }

        case LookaheadNeg ln -> {
          int lnTo = transparentBounds ? input.length() : to;
          if (!runSubProg(ln.body(), input, pos, lnTo)) {
            pc = ln.next();
            continue;
          }
          return -1;
        }

        case LookbehindPos lp -> {
          // Under transparent bounds, lookbehind can see before regionStart (to 0).
          int lbFrom = transparentBounds ? 0 : from;
          int startMin = Math.max(lbFrom, pos - lp.maxLen());
          int startMax = pos - lp.minLen();
          boolean found = false;
          for (int start = startMin; start <= startMax && !found; start++) {
            if (runSubProgExact(lp.body(), input, start, pos)) {
              found = true;
            }
          }
          if (found) {
            pc = lp.next();
            continue;
          }
          return -1;
        }

        case LookbehindNeg lbn -> {
          int lbFrom = transparentBounds ? 0 : from;
          int startMin = Math.max(lbFrom, pos - lbn.maxLen());
          int startMax = pos - lbn.minLen();
          boolean found = false;
          for (int start = startMin; start <= startMax && !found; start++) {
            if (runSubProgExact(lbn.body(), input, start, pos)) {
              found = true;
            }
          }
          if (!found) {
            pc = lbn.next();
            continue;
          }
          return -1;
        }

        case BalancePushInstr bp -> {
          // Push current position onto the named balance stack.
          balanceStacks.computeIfAbsent(bp.name(), k -> new ArrayDeque<>()).push(pos);
          if (depth >= MAX_RECURSION_DEPTH) {
            // Undo the push before throwing.
            ArrayDeque<Integer> bStack = balanceStacks.get(bp.name());
            if (bStack != null && !bStack.isEmpty()) {
              bStack.pop();
            }
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, bp.next(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r < 0) {
            // Undo the push on any failure (including COMMITTED_FAIL).
            ArrayDeque<Integer> bStack = balanceStacks.get(bp.name());
            if (bStack != null && !bStack.isEmpty()) {
              bStack.pop();
            }
            if (r == -1) {
              bt[0]++;
            }
            return r; // propagate -1 or COMMITTED_FAIL
          }
          return r;
        }

        case BalancePopInstr bpo -> {
          ArrayDeque<Integer> bStack = balanceStacks.get(bpo.name());
          if (bStack == null || bStack.isEmpty()) {
            return -1; // pop from empty stack — failure
          }
          int popped = bStack.pop(); // save popped value on JVM stack
          if (depth >= MAX_RECURSION_DEPTH) {
            bStack.push(popped); // undo pop before throwing
            throw new RuntimeException(
                new MatchTimeoutException("<compiled pattern>", input.length(), budget));
          }
          int r = rec(pos, bpo.next(), slots, ops, bt, depth + 1,
              prog, input, from, to, lastMatchEnd, balanceStacks,
              anchoringBounds, transparentBounds, consumed, keepStart, reachedEnd);
          if (r < 0) {
            bStack.push(popped); // restore on any failure (including COMMITTED_FAIL)
            if (r == -1) {
              bt[0]++;
            }
            return r; // propagate -1 or COMMITTED_FAIL
          }
          return r;
        }

        case BalanceCheckInstr bc -> {
          ArrayDeque<Integer> bStack = balanceStacks.get(bc.name());
          if (bStack != null && !bStack.isEmpty()) {
            pc = bc.next();
            continue; // tail call — no undo needed (pure read)
          }
          return -1;
        }

        case ConditionalBranchInstr cb -> {
          boolean conditionMet = evaluateCondition(
              cb, pos, to, slots, input, prog, balanceStacks);
          pc = conditionMet ? cb.yesPC() : cb.noPC();
          continue; // tail call
        }

        default -> {
          return -1; // unknown instruction treated as failure
        }
      }
    }
  }

  // -----------------------------------------------------------------------
  // Sub-program execution for lookahead / lookbehind
  // -----------------------------------------------------------------------

  /**
   * Runs a sub-program from {@code from} within {@code [from, to]}, returning {@code true}
   * if any match is found.
   *
   * <p>Uses a fresh engine so the sub-execution's operation count does not roll into the
   * parent counter.
   *
   * @param subProg the assertion body program; {@code null} or empty is treated as always-match
   * @param input   the full input string
   * @param from    the position at which to start the sub-execution
   * @param to      the exclusive upper bound of the search range
   * @return {@code true} if the sub-program matches at or after {@code from}
   */
  private boolean runSubProg(Prog subProg, String input, int from, int to) {
    if (subProg == null || subProg.getInstructionCount() == 0) {
      return true;
    }
    // Sub-programs for lookahead/lookbehind always use default bounds (anchoringBounds=true,
    // transparentBounds=false) because they operate on their own sub-range.
    MatchResult result = new BoundedBacktrackEngine(budget)
        .execute(subProg, input, from, to, 0, true, false, from);
    return result.matches();
  }

  /**
   * Runs a sub-program against {@code input[from..to]} and returns {@code true} only if the
   * result spans {@code [from, to]} exactly.
   *
   * <p>Handles both fixed-length and bounded variable-length lookbehind bodies. For
   * variable-length bodies (e.g. lazy quantifiers), a fresh {@link Prog} is built with an
   * end-text anchor appended before the original {@link Accept}, forcing the engine to match
   * exactly to {@code to} even when the body would otherwise match less input.
   *
   * @param subProg the assertion body program; {@code null} or empty matches only empty window
   * @param input   the full input string
   * @param from    the start of the lookbehind window (inclusive)
   * @param to      the end of the lookbehind window (exclusive / current position)
   * @return {@code true} if the sub-program matches exactly {@code [from, to]}
   */
  private boolean runSubProgExact(Prog subProg, String input, int from, int to) {
    if (subProg == null || subProg.getInstructionCount() == 0) {
      return from == to;
    }
    // Build an end-anchored variant of the sub-program by replacing the Accept instruction
    // with EndText → Accept. This forces even lazy patterns to expand until pos == to.
    Prog anchored = buildEndAnchoredProg(subProg);
    MatchResult result = new BoundedBacktrackEngine(budget)
        .execute(anchored, input, from, to, 0, true, false, from);
    return result.matches() && result.start() == from && result.end() == to;
  }

  /**
   * Builds a new {@link Prog} identical to {@code subProg} but with an {@link EndText}
   * instruction inserted immediately before the final {@link Accept}.
   *
   * <p>This forces the sub-program to only accept at the region end ({@code to}), which is
   * required for variable-length lookbehind bodies where the body might otherwise match
   * a prefix shorter than the desired window.
   *
   * @param subProg the sub-program to modify; must not be null
   * @return a new end-anchored {@link Prog}, never null
   */
  private static Prog buildEndAnchoredProg(Prog subProg) {
    Instr[] orig = subProg.instructions;
    int n = orig.length;
    // The compiled sub-program has Accept at acceptPc (always the last instruction).
    // Build: [orig[0..n-1 but Accept→EndText(n)], Accept] so length becomes n+1.
    Instr[] anchored = new Instr[n + 1];
    for (int i = 0; i < n; i++) {
      if (orig[i] instanceof Accept) {
        // Replace Accept with EndText pointing to new Accept at position n.
        anchored[i] = new EndText(n);
      } else {
        anchored[i] = orig[i];
      }
    }
    anchored[n] = new Accept();
    return new Prog(anchored, subProg.metadata, subProg.startPc, n);
  }

  // -----------------------------------------------------------------------
  // Conditional evaluation
  // -----------------------------------------------------------------------

  /**
   * Evaluates the condition in a {@link ConditionalBranchInstr} and returns {@code true} if the
   * condition is satisfied.
   *
   * @param cb            the conditional branch instruction; must not be null
   * @param currentPos    the current input position
   * @param to            the exclusive upper bound of the search range
   * @param captures      the current capture array
   * @param input         the full input string
   * @param prog          the main program (used to resolve group names against metadata)
   * @param balanceStacks the active balance stacks
   * @return {@code true} if the condition is met
   */
  private boolean evaluateCondition(
      ConditionalBranchInstr cb,
      int currentPos,
      int to,
      int[] captures,
      String input,
      Prog prog,
      Map<String, ArrayDeque<Integer>> balanceStacks) {
    return switch (cb.kind()) {
      case GROUP_INDEX -> {
        int idx = cb.refIndex() - 1;
        int startSlot = idx * 2;
        int endSlot = startSlot + 1;
        yield startSlot >= 0
            && startSlot < captures.length
            && captures[startSlot] >= 0
            && captures[endSlot] >= 0;
      }
      case GROUP_NAME -> {
        // In .NET semantics, (?(Name)...) tests the balance stack for Name first.
        String name = cb.refName();
        ArrayDeque<Integer> namedStack = balanceStacks.get(name);
        if (namedStack != null) {
          yield !namedStack.isEmpty();
        }
        Integer groupIdx = prog.metadata.groupNames().get(name);
        if (groupIdx != null) {
          int startSlot = (groupIdx - 1) * 2;
          int endSlot = startSlot + 1;
          yield startSlot >= 0
              && startSlot < captures.length
              && captures[startSlot] >= 0
              && captures[endSlot] >= 0;
        }
        yield false;
      }
      case BALANCE_STACK -> {
        ArrayDeque<Integer> bStack = balanceStacks.get(cb.refName());
        yield bStack != null && !bStack.isEmpty();
      }
      case LOOKAHEAD_POS -> runSubProg(cb.lookaheadBody(), input, currentPos, to);
      case LOOKAHEAD_NEG -> !runSubProg(cb.lookaheadBody(), input, currentPos, to);
    };
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * Returns {@code true} if a {@code BeginText} assertion ({@code \A} or non-MULTILINE
   * {@code ^}) passes at {@code pos}.
   *
   * <p>When {@code anchoringBounds} is {@code true}, passes at {@code regionStart} (the
   * region boundary acts as the start of text). When {@code false}, passes only at absolute
   * position {@code 0}.
   *
   * @param pos             the current position
   * @param regionStart     the inclusive start of the current region (equals {@code from})
   * @param anchoringBounds whether anchoring bounds are active
   * @return {@code true} if the assertion passes
   */
  static boolean beginTextPasses(int pos, int regionStart, boolean anchoringBounds) {
    return pos == (anchoringBounds ? regionStart : 0);
  }

  /**
   * Returns {@code true} if an {@code EndText} assertion ({@code \z} or non-MULTILINE
   * {@code $}) passes at {@code pos}.
   *
   * <p>When {@code anchoringBounds} is {@code true}, passes at {@code regionEnd} (the
   * region boundary acts as the end of text). When {@code false}, passes only at absolute
   * {@code input.length()}.
   *
   * @param pos             the current position
   * @param regionEnd       the exclusive end of the current region (equals {@code to})
   * @param regionStart     the inclusive start of the current region (equals {@code from})
   * @param inputLength     the full input length ({@code input.length()})
   * @param anchoringBounds whether anchoring bounds are active
   * @return {@code true} if the assertion passes
   */
  static boolean endTextPasses(int pos, int regionEnd, int regionStart, int inputLength,
      boolean anchoringBounds) {
    return pos == (anchoringBounds ? regionEnd : inputLength);
  }

  /**
   * Returns {@code true} if a {@code BeginLine} assertion passes at {@code pos}.
   *
   * <p>When {@code anchoringBounds} is {@code true}, the region start ({@code regionStart})
   * is treated as the logical start of input for line-boundary purposes. When {@code false},
   * absolute position {@code 0} is used.
   *
   * <p>When {@code perlNewlines} is {@code true}, a line begins after {@code '\n'} or after
   * a standalone {@code '\r'} (i.e., not the {@code '\r'} of a {@code \r\n} CRLF pair; that
   * position lies inside the unit and is not a line start).
   *
   * @param input           the full input string
   * @param pos             the current position
   * @param regionStart     the inclusive start of the current region (equals {@code from})
   * @param anchoringBounds whether anchoring bounds are active
   * @param unixLines       whether only {@code '\n'} counts as a line terminator
   * @param perlNewlines    whether Perl newline semantics are active
   * @return {@code true} if the assertion passes
   */
  static boolean beginLinePasses(String input, int pos, int regionStart, boolean anchoringBounds,
      boolean unixLines, boolean perlNewlines) {
    int logicalStart = anchoringBounds ? regionStart : 0;
    if (pos == logicalStart) {
      return true;
    }
    if (pos == 0) {
      // absolute start always matches for BeginLine, regardless of anchoring
      return true;
    }
    char prev = input.charAt(pos - 1);
    if (unixLines) {
      return prev == '\n';
    }
    if (perlNewlines) {
      if (prev == '\n') {
        return true;
      }
      if (prev == '\r') {
        // '\r\n' is a unit: pos after '\r' is NOT a line start if input[pos] == '\n'.
        return pos >= input.length() || input.charAt(pos) != '\n';
      }
      return false;
    }
    // Reject position between '\r' and '\n' of a CRLF pair.
    if (prev == '\r' && pos < input.length() && input.charAt(pos) == '\n') {
      return false;
    }
    return prev == '\n' || prev == '\r' || prev == '\u0085'
        || prev == '\u2028' || prev == '\u2029';
  }

  /**
   * Legacy overload of {@link #beginLinePasses} for call sites that do not pass region
   * parameters (uses default anchoring bounds — anchoring enabled, region start = 0, no
   * PERL_NEWLINES).
   *
   * @param input     the full input string
   * @param pos       the current position
   * @param to        the exclusive end of the search range (unused; retained for API compat)
   * @param unixLines whether only {@code '\n'} counts as a line terminator
   * @return {@code true} if the assertion passes
   */
  static boolean beginLinePasses(String input, int pos, int to, boolean unixLines) {
    return beginLinePasses(input, pos, 0, true, unixLines, false);
  }

  /**
   * Returns {@code true} if an {@code EndLine} assertion passes at {@code pos}.
   *
   * <p>When {@code multiline} is {@code false} (non-MULTILINE {@code $}), matches only at the
   * end of the effective range or before the single trailing line terminator — identical to
   * {@link #endZPasses(String, int, int, int, int, boolean, boolean, boolean)}.
   * When {@code multiline} is {@code true} (MULTILINE {@code $}), matches before any line
   * terminator in the input.
   *
   * <p>When {@code anchoringBounds} is {@code true}, the effective end is {@code regionEnd};
   * when {@code false}, the effective end is {@code inputLength}.
   *
   * <p>When {@code perlNewlines} is {@code true}, {@code $} matches before {@code '\n'} and
   * before a standalone {@code '\r'} (not the {@code '\r'} of a CRLF pair — that position lies
   * between {@code '\r'} and {@code '\n'} and does not pass).
   *
   * @param input           the full input string
   * @param pos             the current position
   * @param regionEnd       the exclusive end of the current region (equals {@code to})
   * @param regionStart     the inclusive start of the current region (equals {@code from})
   * @param inputLength     the full input length
   * @param anchoringBounds whether anchoring bounds are active
   * @param multiline       whether the MULTILINE flag is active
   * @param unixLines       whether only {@code '\n'} counts as a line terminator
   * @param perlNewlines    whether Perl newline semantics are active
   * @return {@code true} if the assertion passes
   */
  static boolean endLinePasses(String input, int pos, int regionEnd, int regionStart,
      int inputLength, boolean anchoringBounds, boolean multiline, boolean unixLines,
      boolean perlNewlines) {
    int effectiveEnd = anchoringBounds ? regionEnd : inputLength;
    if (!multiline) {
      return endZPasses(input, pos, regionEnd, regionStart, inputLength,
          anchoringBounds, unixLines, perlNewlines);
    }
    if (pos == effectiveEnd) {
      return true;
    }
    if (pos > effectiveEnd) {
      return false;
    }
    char ch = input.charAt(pos);
    if (unixLines) {
      return ch == '\n';
    }
    if (perlNewlines) {
      if (ch == '\n') {
        return true;
      }
      if (ch == '\r') {
        // Reject the position between '\r' and '\n' of a CRLF pair.
        return pos + 1 >= inputLength || input.charAt(pos + 1) != '\n';
      }
      return false;
    }
    // Reject the '\n' of a '\r\n' CRLF pair (the match point is before the '\r').
    if (ch == '\n' && pos > 0 && input.charAt(pos - 1) == '\r') {
      return false;
    }
    return ch == '\n' || ch == '\r' || ch == '\u0085'
        || ch == '\u2028' || ch == '\u2029';
  }

  /**
   * Legacy overload of {@link #endLinePasses} for call sites that do not pass region
   * parameters (uses default anchoring bounds — anchoring enabled, {@code to == inputLength},
   * no PERL_NEWLINES).
   *
   * @param input     the full input string
   * @param pos       the current position
   * @param to        the exclusive end of the search range
   * @param multiline whether the MULTILINE flag is active
   * @param unixLines whether only {@code '\n'} counts as a line terminator
   * @return {@code true} if the assertion passes
   */
  static boolean endLinePasses(String input, int pos, int to,
      boolean multiline, boolean unixLines) {
    return endLinePasses(input, pos, to, 0, to, true, multiline, unixLines, false);
  }

  /**
   * Returns {@code true} if an {@code EndZ} assertion ({@code \Z}) passes at {@code pos}.
   *
   * <p>When {@code anchoringBounds} is {@code true}, the effective end is {@code regionEnd}
   * (the current region boundary). When {@code false}, the effective end is {@code inputLength}.
   * Passes at the effective end, or immediately before the single trailing line terminator
   * at that effective end.
   *
   * <p>When {@code perlNewlines} is {@code true}, the trailing terminators recognised are
   * {@code '\n'}, {@code '\r'}, and the CRLF pair {@code '\r\n'}.
   *
   * @param input           the full input string
   * @param pos             the current position
   * @param regionEnd       the exclusive end of the current region (equals {@code to})
   * @param regionStart     the inclusive start of the current region (equals {@code from})
   * @param inputLength     the full input length
   * @param anchoringBounds whether anchoring bounds are active
   * @param unixLines       whether only {@code '\n'} counts as the final line terminator
   * @param perlNewlines    whether Perl newline semantics are active
   * @return {@code true} if the assertion passes
   */
  static boolean endZPasses(String input, int pos, int regionEnd, int regionStart,
      int inputLength, boolean anchoringBounds, boolean unixLines, boolean perlNewlines) {
    int effectiveEnd = anchoringBounds ? regionEnd : inputLength;
    if (pos == effectiveEnd) {
      return true;
    }
    if (perlNewlines) {
      if (pos == effectiveEnd - 1) {
        char c = input.charAt(pos);
        return c == '\n' || c == '\r';
      }
      if (pos == effectiveEnd - 2) {
        return input.charAt(pos) == '\r' && input.charAt(pos + 1) == '\n';
      }
      return false;
    }
    if (pos == effectiveEnd - 1) {
      char last = input.charAt(pos);
      if (unixLines) {
        return last == '\n';
      }
      // Reject the '\n' of a trailing CRLF pair — that position is handled
      // by the pos == effectiveEnd - 2 branch as part of the two-character '\r\n' unit.
      if (last == '\n' && pos > 0 && input.charAt(pos - 1) == '\r') {
        return false;
      }
      return last == '\n' || last == '\r' || last == '\u0085'
          || last == '\u2028' || last == '\u2029';
    }
    // Check for trailing '\r\n' CRLF pair only.
    if (!unixLines && pos == effectiveEnd - 2) {
      return input.charAt(pos) == '\r' && input.charAt(pos + 1) == '\n';
    }
    return false;
  }

  /**
   * Legacy overload of {@link #endZPasses} for call sites that do not pass region parameters
   * (uses default anchoring bounds — anchoring enabled, {@code to == inputLength}, no
   * PERL_NEWLINES).
   *
   * @param input     the full input string
   * @param pos       the current position
   * @param to        the exclusive end of the search range
   * @param unixLines whether only {@code '\n'} counts as the final line terminator
   * @return {@code true} if the assertion passes
   */
  static boolean endZPasses(String input, int pos, int to, boolean unixLines) {
    return endZPasses(input, pos, to, 0, to, true, unixLines, false);
  }

  /**
   * Returns {@code true} if {@code c} is an ASCII word character ({@code [a-zA-Z0-9_]}).
   *
   * @param c the character to test
   * @return {@code true} if {@code c} is a word character
   */
  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9') || c == '_';
  }

  /**
   * Returns {@code true} if the character at {@code pos} in {@code input} is effectively a word
   * character, applying JDK's Unicode non-spacing mark (NSM) extension rule.
   *
   * <p>A Unicode non-spacing mark ({@code NON_SPACING_MARK}) is considered a word character when
   * it directly follows — possibly through a chain of other non-spacing marks — an ASCII word
   * character. An NSM at the start of a word (preceded only by non-word characters) is treated as
   * non-word. This replicates the behaviour of {@code java.util.regex} for bug 4979006.
   *
   * <p>Characters outside the string range are treated as the {@code '\0'} sentinel (non-word).
   *
   * @param input the full input string; must not be null
   * @param pos   the position to evaluate; must be {@code >= 0}
   * @param to    the exclusive upper bound of the active range
   * @return {@code true} if the position is effectively a word character
   */
  private static boolean isEffectiveWordChar(String input, int pos, int to, boolean unicodeCase) {
    if (pos >= to) {
      return false;
    }
    char c = input.charAt(pos);
    if (unicodeCase ? isWordCharUnicode(c) : isWordChar(c)) {
      return true;
    }
    if (Character.getType(c) != Character.NON_SPACING_MARK) {
      return false;
    }
    // NSM: walk backwards through any consecutive NSMs to find the base character.
    for (int i = pos - 1; i >= 0; i--) {
      char base = input.charAt(i);
      if (unicodeCase ? isWordCharUnicode(base) : isWordChar(base)) {
        return true;
      }
      if (Character.getType(base) != Character.NON_SPACING_MARK) {
        return false;
      }
    }
    return false;
  }

  private static boolean isWordCharUnicode(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Returns {@code true} if position {@code pos} in {@code input} is at a word boundary, using
   * the search-range upper bound {@code to} as the logical end-of-text sentinel.
   *
   * <p>A word boundary exists when the character class of the character before {@code pos} differs
   * from the character class of the character at {@code pos}. Unicode non-spacing marks extend the
   * word boundary of the preceding base character (JDK bug 4979006). Characters outside the string
   * (before index 0 or at/after index {@code to}) are treated as non-word characters.
   *
   * @param input       the full input string; must not be null
   * @param pos         the current position to evaluate; must be {@code >= 0}
   * @param to          the exclusive upper bound of the search range
   * @param unicodeCase {@code true} to use Unicode letter/digit definition for word chars
   * @return {@code true} if a word boundary exists at {@code pos}
   */
  private static boolean isWordBoundary(String input, int pos, int to, boolean unicodeCase) {
    boolean prevWord = pos > 0 && isEffectiveWordChar(input, pos - 1, to, unicodeCase);
    boolean currWord = isEffectiveWordChar(input, pos, to, unicodeCase);
    return prevWord != currWord;
  }

  /**
   * Counts the number of capturing groups by scanning for {@link SaveCapture} instructions.
   *
   * <p>Used as a fallback when {@code prog.metadata.groupCount()} is zero (e.g., sub-programs
   * compiled for assertion bodies).
   *
   * @param prog the program to scan; must not be null
   * @return the number of capturing groups, >= 0
   */
  private static int countGroups(Prog prog) {
    int max = -1;
    for (int i = 0; i < prog.getInstructionCount(); i++) {
      if (prog.getInstruction(i) instanceof SaveCapture sc && sc.isStart()) {
        if (sc.groupIndex() > max) {
          max = sc.groupIndex();
        }
      }
    }
    return max + 1;
  }

  /**
   * Builds the list of captured group span pairs from the capture array.
   *
   * @param captures  the capture slot array; slot {@code 2*i} is group {@code i} start,
   *                  slot {@code 2*i+1} is group {@code i} end; unset slots are {@code -1}
   * @param numGroups the number of capturing groups
   * @return a list of span pairs, with {@code [-1,-1]} entries for unmatched groups
   */
  private static List<int[]> buildGroupSpans(int[] captures, int numGroups) {
    List<int[]> spans = new ArrayList<>(numGroups);
    for (int i = 0; i < numGroups; i++) {
      int start = captures[i * 2];
      int end = captures[i * 2 + 1];
      spans.add((start >= 0 && end >= start) ? new int[]{start, end} : new int[]{-1, -1});
    }
    return spans;
  }

  private static List<String> buildGroupList(int[] captures, String input, int numGroups) {
    List<String> groups = new ArrayList<>(numGroups);
    for (int i = 0; i < numGroups; i++) {
      int start = captures[i * 2];
      int end = captures[i * 2 + 1];
      groups.add((start >= 0 && end >= start) ? input.substring(start, end) : null);
    }
    return groups;
  }
}
