package com.orbit.engine.engines;

import com.orbit.engine.Engine;
import com.orbit.prog.Accept;
import com.orbit.prog.AnyChar;
import com.orbit.prog.AtomicCommit;
import com.orbit.prog.BackrefCheck;
import com.orbit.prog.BeginG;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.CharMatch;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EndZ;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Instr;
import com.orbit.prog.LookbehindNeg;
import com.orbit.prog.LookbehindPos;
import com.orbit.prog.Lookahead;
import com.orbit.prog.LookaheadNeg;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.PossessiveSplit;
import com.orbit.prog.ResetMatchStart;
import com.orbit.prog.Split;
import com.orbit.prog.UnionSplit;
import com.orbit.prog.TransOutput;
import com.orbit.prog.WordBoundary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PikeVM NFA interpreter for regular expression matching.
 *
 * <p>Implements the Pike/Thompson NFA simulation algorithm. At each input position the engine
 * maintains a prioritised set of active threads stored in a pre-allocated flat {@code long[]}
 * slab inside {@link ActiveStates}. Each active state is identified by its program counter (PC)
 * and stores capture slot data in a fixed-stride row within the slab.
 *
 * <p>Slot layout per state at index {@code pc} (stride = {@code Math.max(1, numGroups * 2)}):
 * <pre>
 *   slotTable[pc * stride + 2*i]       group i start (Long.MIN_VALUE = unset)
 *   slotTable[pc * stride + 2*i + 1]   group i end   (Long.MIN_VALUE = unset)
 * </pre>
 *
 * <p>Thread priority is implicit in {@link SparseSet} insertion order. The first thread to
 * reach a program counter claims it; later arrivals at the same PC are discarded by the
 * {@code visited.contains(pc)} guard in {@link #computeClosure}. For {@link Split}, the left
 * branch is pushed onto the epsilon stack last (LIFO order) so it executes first and claims
 * consuming states before the right branch is explored.
 *
 * <p>Epsilon transitions ({@link Split}, {@link EpsilonJump}, {@link SaveCapture},
 * {@link BeginText}, {@link EndText}, etc.) are followed without consuming input using an
 * explicit integer stack that avoids recursion and eliminates heap allocation inside the search
 * loop.
 *
 * <p>Instances are <em>not</em> thread-safe. Create one instance per thread or per match call.
 */
public class PikeVmEngine implements Engine {

  /**
   * Stack for iterative epsilon closure. Allocated once per {@code execute()} call and reused
   * for every closure computation within a non-backref path. Reset to top=0 at entry to each
   * {@link #computeClosure} call.
   *
   * <p>Frame layout (type is pushed last so it sits at {@code epsilonTop - 1}):
   * <pre>
   *   FRAME_EXPLORE (type=0):           [pc, 0]                            — 2 ints
   *   FRAME_RESTORE_CAPTURE (type=1):   [loInt, hiInt, slotIdx, 1]         — 4 ints
   * </pre>
   */
  private int[] epsilonStack;

  /** Next free slot in {@link #epsilonStack}. Reset to 0 at each closure entry. */
  private int epsilonTop;

  private static final int FRAME_EXPLORE = 0;
  private static final int FRAME_RESTORE = 1;

  /**
   * Executes the compiled program against the given input slice, looking for the leftmost
   * match starting at any position in {@code [from, to)}.
   *
   * @param prog         the compiled program; must not be null
   * @param input        the input string; must not be null
   * @param from         the starting search index (inclusive)
   * @param to           the ending search index (exclusive)
   * @param lastMatchEnd the end position of the previous successful match, for {@code \G}
   *                     support; {@code 0} if no previous match exists
   * @return the match result; never null
   */
  @Override
  public MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart) {
    if (prog == null || input == null) {
      return noMatch();
    }
    if (prog.getInstructionCount() == 0) {
      // Empty program — epsilon match at 'from'.
      return new MatchResult(true, from, from, List.of(), List.of(), null, 0L, 0L, 0);
    }

    boolean isTransducer = prog.metadata.isTransducer();
    int numGroups = countGroups(prog);
    int instrCount = prog.getInstructionCount();
    // stride: slots 2*i and 2*i+1 hold group i start/end.
    // One extra slot at index numGroups*2 stores the \K keep-start position
    // (Long.MIN_VALUE = no \K executed; non-negative = override match start).
    // Minimum stride of 2 to accommodate at least the keep-start slot.
    int stride = Math.max(1, numGroups * 2) + 1;

    // Allocate once per execute() call; reuse across all startPos iterations.
    ActiveStates curr = new ActiveStates(instrCount, stride);
    ActiveStates next = new ActiveStates(instrCount, stride);
    long[] scratch = new long[stride];
    SparseSet visited = new SparseSet(instrCount);
    // epsilon stack: each instruction contributes at most one EXPLORE (2 ints) and one
    // RESTORE (4 ints) frame. instrCount * 4 gives a comfortable safety margin.
    // TransOutput adds an extra EXPLORE frame; instrCount * 6 provides additional margin.
    epsilonStack = new int[instrCount * 6];
    epsilonTop = 0;
    // Output buffer: allocated once, reset per startPos. Null for non-transducer programs (C-2).
    StringBuilder outputBuffer = isTransducer ? new StringBuilder() : null;

    // Precise hitEnd tracking for failure: when runNfa returns null but had surviving threads
    // at pos == to (i.e. threads ran out of input without matching), more input could produce
    // a match. Additionally, if the outer for-loop exhausted all start positions (reaching
    // startPos == to), the engine scanned to the end and hitEnd = true.
    boolean[] nfaHitEnd = {false};
    // Track the last startPos actually attempted, to detect full outer-loop exhaustion.
    int lastStartPos = from - 1;

    for (int startPos = from; startPos <= to; startPos++) {
      lastStartPos = startPos;
      // Reset the output buffer for each candidate start position (C-3).
      if (outputBuffer != null) {
        outputBuffer.setLength(0);
      }
      String[] acceptedOutput = new String[1]; // single-element holder for output from runNfa
      int[] accepted = runNfa(prog, input, startPos, regionStart, to, numGroups, stride,
          curr, next, scratch, visited, outputBuffer, acceptedOutput, lastMatchEnd,
          anchoringBounds, transparentBounds, nfaHitEnd);
      if (accepted != null) {
        int matchStart = accepted[0];
        int matchEnd = accepted[1];
        List<String> groups = buildGroupList(accepted, input, numGroups);
        List<int[]> spans = buildGroupSpanList(accepted, numGroups);
        // acceptedOutput[0] is null for non-transducer programs.
        boolean hitEnd = (matchEnd == to);
        return new MatchResult(true, matchStart, matchEnd, groups, spans,
            acceptedOutput[0], 0L, 0L, 0, hitEnd);
      }
    }

    // hitEnd = true when: (a) threads survived to end-of-input in some runNfa call, OR
    // (b) the outer loop exhausted all start positions up to 'to', meaning the engine scanned
    //     all the way to the region boundary. Case (b) covers patterns whose seeds die
    //     immediately at every position (no character match) — the engine still reached 'to'.
    // Exception: start-anchored patterns fail definitively at position 'from' without
    // scanning forward — hitEnd = false even if the outer loop ran through all positions
    // (the non-from positions are vacuous because the anchor cannot pass there).
    boolean outerExhausted = (lastStartPos >= to) && !prog.metadata.startAnchored();
    boolean hitEnd = nfaHitEnd[0] || outerExhausted;
    return new MatchResult(false, -1, -1, List.of(), List.of(), null, 0L, 0L, 0, hitEnd);
  }

  // -----------------------------------------------------------------------
  // NFA simulation core
  // -----------------------------------------------------------------------

  /**
   * Runs the NFA from {@code startPos} and returns an accepted result array on success, or
   * {@code null} if no match starts at {@code startPos}.
   *
   * <p>On entry, {@code curr} and {@code next} must have their slot tables clean (all
   * {@link Long#MIN_VALUE}), which is the invariant maintained by {@link #cleanupActiveStates}.
   * On return, the same invariant holds.
   *
   * <p>The returned array has format: {@code [matchStart, matchEnd, cap0start, cap0end, …]}.
   *
   * @param prog      the program to execute
   * @param input     the full input string
   * @param startPos  the position at which to start this NFA simulation
   * @param to        the exclusive end of the search range
   * @param numGroups the number of capturing groups
   * @param stride    slots per state ({@code Math.max(1, numGroups * 2)})
   * @param curr      the current-step active states; slot table must be clean on entry
   * @param next      the next-step active states; slot table must be clean on entry
   * @param scratch   a scratch buffer of length {@code stride}
   * @param visited   the visited-PC set for epsilon closure
   * @return the match result array, or {@code null} if no match
   */
  /**
   * Per-thread output accumulation buffer for transducer programs. Passed through {@code runNfa}
   * and {@code computeClosure}. {@code null} for non-transducer programs.
   *
   * <p>The buffer is reset at the start of each {@code startPos} iteration in {@code execute}.
   * When {@link Accept} is reached, the accumulated content is captured into
   * {@code acceptedOutput[0]}.
   */
  // (instance field only for runNfa/computeClosure parameter threading; see execute() usage)

  private int[] runNfa(
      Prog prog, String input, int startPos, int regionStart, int to,
      int numGroups, int stride,
      ActiveStates curr, ActiveStates next,
      long[] scratch, SparseSet visited,
      StringBuilder outputBuffer, String[] acceptedOutput,
      int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds,
      boolean[] nfaHitEnd) {
    return runNfa(prog, input, startPos, regionStart, to, numGroups, stride,
        curr, next, scratch, visited, outputBuffer, acceptedOutput, lastMatchEnd,
        anchoringBounds, transparentBounds, null, nfaHitEnd);
  }

  private int[] runNfa(
      Prog prog, String input, int startPos, int regionStart, int to,
      int numGroups, int stride,
      ActiveStates curr, ActiveStates next,
      long[] scratch, SparseSet visited,
      StringBuilder outputBuffer, String[] acceptedOutput,
      int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds,
      long[] outerCaptures) {
    return runNfa(prog, input, startPos, regionStart, to, numGroups, stride,
        curr, next, scratch, visited, outputBuffer, acceptedOutput, lastMatchEnd,
        anchoringBounds, transparentBounds, outerCaptures, null);
  }

  private int[] runNfa(
      Prog prog, String input, int startPos, int regionStart, int to,
      int numGroups, int stride,
      ActiveStates curr, ActiveStates next,
      long[] scratch, SparseSet visited,
      StringBuilder outputBuffer, String[] acceptedOutput,
      int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds,
      long[] outerCaptures, boolean[] nfaHitEnd) {

    // --- Seed: compute epsilon closure from startPc at startPos into next, then swap ---
    next.set.clear();
    visited.clear();
    Arrays.fill(scratch, Long.MIN_VALUE);
    // outerCaptures (if non-null) is used as a fallback in the BackrefCheck position-loop
    // handler for groups that are out of range in the sub-program's slab. The scratch is
    // NOT seeded from outerCaptures here — that would pollute SaveCapture restore frames.
    // See BackrefCheck handling in the position loop below.

    computeClosure(prog, input, startPos, regionStart, to, prog.startPc,
        visited, next, scratch, outputBuffer, lastMatchEnd, anchoringBounds, transparentBounds);

    // Swap: curr <- consuming states reachable from startPc at startPos.
    ActiveStates tmp = curr;
    curr = next;
    next = tmp;

    int[] acceptedResult = null;

    for (int pos = startPos; pos <= to; pos++) {
      if (curr.set.size() == 0) {
        break;
      }

      next.set.clear();
      visited.clear();

      char ch = (pos < to) ? input.charAt(pos) : '\0';

      for (int ci = 0; ci < curr.set.size(); ci++) {
        int srcPc = curr.set.get(ci);
        Instr instr = prog.getInstruction(srcPc);

        if (instr instanceof Accept) {
          // Record the match at this position. Break immediately: states are in insertion
          // order (highest priority first). Any thread at a higher ci is lower-priority and
          // must not override this result, even if it would reach Accept at a later position.
          // Greedy quantifier extension still works because the quantifier's consuming
          // instruction is always at a lower ci than the Accept (committed to next first
          // during the previous step's closure), so it fires before we reach this break.
          acceptedResult = extractResult(curr, srcPc, startPos, pos, numGroups);
          // Capture the accumulated transducer output for this accept (C-8).
          if (outputBuffer != null) {
            acceptedOutput[0] = outputBuffer.toString();
          }
          break;
        }

        if (pos >= to) {
          continue;
        }

        if (instr instanceof CharMatch cm) {
          if (ch < cm.lo() || ch > cm.hi()) {
            continue;
          }
          populateScratch(scratch, curr, srcPc, stride);
          // Reset the output buffer before computing the next closure so each consuming
          // step starts with a fresh accumulation from scratch. The buffer already contains
          // any output emitted during the seed closure; here we re-seed from the current
          // thread's committed state. For correctness in the transducer case the output
          // buffer is rebuilt from the closure, not carried per-thread through the slab.
          // (TransOutput instructions are epsilon-like and re-run during each closure.)
          computeClosure(prog, input, pos + 1, regionStart, to, cm.next(),
              visited, next, scratch, outputBuffer, lastMatchEnd, anchoringBounds, transparentBounds);

        } else if (instr instanceof AnyChar ac) {
          populateScratch(scratch, curr, srcPc, stride);
          computeClosure(prog, input, pos + 1, regionStart, to, ac.next(),
              visited, next, scratch, outputBuffer, lastMatchEnd, anchoringBounds, transparentBounds);

        } else if (instr instanceof BackrefCheck br) {
          int startSlot = br.groupIndex() * 2;
          int endSlot = startSlot + 1;
          // Resolve the captured text. Primary source: the slab row for this thread.
          // Fallback: outerCaptures, which holds the outer program's group captures for
          // assertion bodies that reference groups defined in the outer pattern
          // (e.g. \1 in (?!.*\1)). Used when the slot is out of range or unset in the slab.
          long rawStart;
          long rawEnd;
          if (endSlot < stride) {
            rawStart = curr.getSlot(srcPc, startSlot);
            rawEnd = curr.getSlot(srcPc, endSlot);
            // Fall back to outerCaptures if the slab slot is unset and outer captures exist.
            if ((rawStart == Long.MIN_VALUE || rawEnd == Long.MIN_VALUE)
                && outerCaptures != null && endSlot < outerCaptures.length) {
              rawStart = outerCaptures[startSlot];
              rawEnd = outerCaptures[endSlot];
            }
          } else if (outerCaptures != null && endSlot < outerCaptures.length) {
            // Slot is beyond the sub-program's stride; use outer captures directly.
            rawStart = outerCaptures[startSlot];
            rawEnd = outerCaptures[endSlot];
          } else {
            rawStart = Long.MIN_VALUE;
            rawEnd = Long.MIN_VALUE;
          }
          int capStart = (rawStart == Long.MIN_VALUE) ? -1 : (int) rawStart;
          int capEnd = (rawEnd == Long.MIN_VALUE) ? -1 : (int) rawEnd;
          if (capStart >= 0 && capEnd >= 0) {
            String captured = input.substring(capStart, capEnd);
            int capturedLen = captured.length();
            if (capturedLen == 0) {
              // Zero-length backref: epsilon-advance without consuming input.
              populateScratch(scratch, curr, srcPc, stride);
              computeClosure(prog, input, pos, regionStart, to, br.next(),
                  visited, next, scratch, outputBuffer, lastMatchEnd,
                  anchoringBounds, transparentBounds);
            } else if (pos + capturedLen <= to
                && input.regionMatches(br.caseInsensitive(), pos, captured, 0, capturedLen)) {
              // Backref matched: copy seed captures and continue past the consumed text.
              // continueFromPosition allocates its own ActiveStates (backref is not the
              // hot path for this session's benchmark targets).
              long[] seedCaptures = new long[stride];
              System.arraycopy(curr.slotTable, srcPc * stride, seedCaptures, 0, stride);
              int[] subResult = continueFromPosition(
                  prog, input, pos + capturedLen, to, numGroups, stride,
                  br.next(), seedCaptures);
              if (subResult != null) {
                subResult[0] = startPos;
                acceptedResult = subResult;
              }
            }
          }
          continue;
        }
        // Any other instruction in curr is not handled (e.g., unrecognised consuming types
        // added to curr by default branch in computeClosure — treated as consumed already).
      }

      // Swap curr <-> next.
      ActiveStates swapTmp = curr;
      curr = next;
      next = swapTmp;

      // Early-exit: once no consuming threads survive, no future accept is possible.
      if (acceptedResult != null && curr.set.size() == 0) {
        return acceptedResult;
      }
    }

    // Final scan: check surviving curr for Accept states.
    for (int ci = 0; ci < curr.set.size(); ci++) {
      int pc = curr.set.get(ci);
      if (prog.getInstruction(pc) instanceof Accept) {
        acceptedResult = extractResult(curr, pc, startPos, to, numGroups);
        if (outputBuffer != null) {
          acceptedOutput[0] = outputBuffer.toString();
        }
        break; // states are in insertion/priority order; first Accept wins
      }
    }

    // Precise hitEnd: if no match was found and threads survived to end-of-input (curr is
    // non-empty before cleanup), more input could have changed the result.
    if (acceptedResult == null && nfaHitEnd != null && curr.set.size() > 0) {
      nfaHitEnd[0] = true;
    }

    cleanupActiveStates(curr, stride);
    return acceptedResult;
  }

  // -----------------------------------------------------------------------
  // Epsilon closure (iterative, explicit stack)
  // -----------------------------------------------------------------------

  /**
   * Computes the epsilon closure starting from {@code seedPc} at the given position. Consuming
   * instructions reached during traversal are committed to {@code next} via
   * {@link #commitToNext}. The {@code scratch} buffer carries the current capture state and is
   * restored for alternative branches using {@code FRAME_RESTORE} frames on the epsilon stack.
   *
   * <p>Priority is implicit in {@link SparseSet} insertion order. Left branches of
   * {@link Split} are pushed last (so they execute first under LIFO) ensuring left-before-right
   * priority without any explicit counter.
   *
   * <p>Preconditions:
   * <ul>
   *   <li>{@code scratch} has been populated with the source thread's capture state.
   *   <li>{@code visited} is the {@link SparseSet} for the current position step.
   *   <li>{@code next.set.clear()} was called at the start of the position step.
   *   <li>{@code epsilonTop} is reset to 0 at the start of this method.
   * </ul>
   *
   * @param prog    the compiled program
   * @param input   the full input string
   * @param pos     the current input position (used for anchor checks and SaveCapture)
   * @param to      the exclusive end of the search range
   * @param seedPc  the PC to start the closure from
   * @param visited the visited-PC set for this position step
   * @param next    the next-step active states to populate
   * @param scratch the mutable capture buffer ({@code stride} entries); restored by RESTORE
   *                frames for alternative branches
   */
  private void computeClosure(
      Prog prog, String input, int pos, int regionStart, int to,
      int seedPc,
      SparseSet visited, ActiveStates next,
      long[] scratch,
      StringBuilder outputBuffer,
      int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds) {

    epsilonTop = 0;
    // Reset the output buffer at the start of each closure for normal compiled transducers
    // (outputPrecedesInput == false). For graph-derived transducers (outputPrecedesInput == true),
    // TransOutput instructions may fire in the seed closure before any CharMatch; resetting here
    // would erase that output. The per-startPos reset in execute() handles the outer boundary.
    if (outputBuffer != null && !prog.metadata.outputPrecedesInput()) {
      outputBuffer.setLength(0);
    }
    pushExplore(seedPc);

    while (epsilonTop > 0) {
      int frameType = epsilonStack[--epsilonTop];

      if (frameType == FRAME_RESTORE) {
        int slotIdx = epsilonStack[--epsilonTop];
        int hiInt = epsilonStack[--epsilonTop];
        int loInt = epsilonStack[--epsilonTop];
        long oldValue = ((long) hiInt << 32) | (loInt & 0xFFFFFFFFL);
        scratch[slotIdx] = oldValue;
        continue;
      }

      // FRAME_EXPLORE
      int pc = epsilonStack[--epsilonTop];

      if (visited.contains(pc)) {
        continue;
      }
      visited.add(pc);

      Instr instr = prog.getInstruction(pc);

      switch (instr) {
        case EpsilonJump ej -> pushExplore(ej.next());

        case AtomicCommit ac ->
            // In the PikeVM, treat AtomicCommit as an epsilon jump to next().
            // Atomic semantics are only enforced by BoundedBacktrackEngine.
            pushExplore(ac.next());

        case Split split -> {
          // Push right first so left executes first (LIFO). The visited guard ensures
          // whichever branch reaches a consuming state first claims it; the other is
          // discarded. This encodes left-before-right priority without any counter.
          pushExplore(split.next2()); // right — pushed first, executes second
          pushExplore(split.next1()); // left  — pushed second, executes first
        }

        case UnionSplit us -> {
          // In the PikeVM, treat UnionSplit identically to Split; the COMMITTED_FAIL
          // normalization it provides is only relevant in BoundedBacktrackEngine.
          pushExplore(us.next2()); // right — pushed first, executes second
          pushExplore(us.next1()); // left  — pushed second, executes first
        }

        case PossessiveSplit ps -> {
          // In the PikeVM, treat identically to greedy Split; possessive semantics are
          // only enforced by BoundedBacktrackEngine.
          pushExplore(ps.next2()); // right — pushed first, executes second
          pushExplore(ps.next1()); // left  — pushed second, executes first
        }

        case SaveCapture sc -> {
          int slotIdx = sc.groupIndex() * 2 + (sc.isStart() ? 0 : 1);
          if (slotIdx < scratch.length) {
            long oldValue = scratch[slotIdx];
            // Push restore BEFORE writing, so the alternative branch sees the old value.
            pushRestoreCapture(slotIdx, oldValue);
            scratch[slotIdx] = (long) pos;
          }
          pushExplore(sc.next());
        }

        case BeginText bt -> {
          if (BoundedBacktrackEngine.beginTextPasses(pos, regionStart, anchoringBounds)) {
            pushExplore(bt.next());
          }
        }

        case BeginLine bl -> {
          if (BoundedBacktrackEngine.beginLinePasses(input, pos, regionStart, anchoringBounds,
              bl.unixLines(), bl.perlNewlines())) {
            pushExplore(bl.next());
          }
        }

        case EndText et -> {
          if (BoundedBacktrackEngine.endTextPasses(pos, to, regionStart, input.length(),
              anchoringBounds)) {
            pushExplore(et.next());
          }
        }

        case EndLine el -> {
          if (BoundedBacktrackEngine.endLinePasses(input, pos, to, regionStart, input.length(),
              anchoringBounds, el.multiline(), el.unixLines(), el.perlNewlines())) {
            pushExplore(el.next());
          }
        }

        case EndZ ez -> {
          if (BoundedBacktrackEngine.endZPasses(input, pos, to, regionStart, input.length(),
              anchoringBounds, ez.unixLines(), ez.perlNewlines())) {
            pushExplore(ez.next());
          }
        }

        case BeginG bg -> {
          if (pos == lastMatchEnd) {
            pushExplore(bg.next());
          }
        }

        case WordBoundary wb -> {
          boolean boundary = isWordBoundary(input, pos, to, wb.unicodeCase());
          if (wb.negated() ? !boundary : boundary) {
            pushExplore(wb.next());
          }
        }

        case Lookahead la -> {
          int laTo = transparentBounds ? input.length() : to;
          if (runSubProg(la.body(), input, pos, laTo, scratch)) {
            pushExplore(la.next());
          }
        }

        case LookaheadNeg ln -> {
          int lnTo = transparentBounds ? input.length() : to;
          if (!runSubProg(ln.body(), input, pos, lnTo, scratch)) {
            pushExplore(ln.next());
          }
        }

        case LookbehindPos lp -> {
          int lbFrom = transparentBounds ? 0 : regionStart;
          int startMin = Math.max(lbFrom, pos - lp.maxLen());
          int startMax = pos - lp.minLen();
          boolean found = false;
          for (int start = startMin; start <= startMax && !found; start++) {
            if (runSubProgExact(lp.body(), input, start, pos, scratch)) {
              found = true;
            }
          }
          if (found) {
            pushExplore(lp.next());
          }
        }

        case LookbehindNeg ln -> {
          int lbFrom = transparentBounds ? 0 : regionStart;
          int startMin = Math.max(lbFrom, pos - ln.maxLen());
          int startMax = pos - ln.minLen();
          boolean found = false;
          for (int start = startMin; start <= startMax && !found; start++) {
            if (runSubProgExact(ln.body(), input, start, pos, scratch)) {
              found = true;
            }
          }
          if (!found) {
            pushExplore(ln.next());
          }
        }

        case TransOutput tout -> {
          // TransOutput is an epsilon-like instruction: it does not consume input but
          // appends the resolved delta to the output buffer (Stage 5.2).
          if (outputBuffer != null) {
            appendResolvedDelta(tout.delta(), scratch, input, prog, outputBuffer);
          }
          pushExplore(tout.next());
        }

        case ResetMatchStart rms -> {
          // \K: record the current input position as the keep-start for this NFA thread.
          // The slot is at index (stride - 1) = numGroups * 2, after all capture slots.
          // Uses a RESTORE frame so that backtracking siblings see the old value.
          int keepSlot = scratch.length - 1;
          long oldKeep = scratch[keepSlot];
          pushRestoreCapture(keepSlot, oldKeep);
          scratch[keepSlot] = (long) pos;
          pushExplore(rms.next());
        }

        default ->
            // Accept, CharMatch, AnyChar, BackrefCheck, and any other consuming instruction:
            // commit to next and let the position-step loop handle it.
            commitToNext(pc, next, scratch);
      }
    }
  }

  /**
   * Resolves a {@link TransOutput} delta string and appends the result to {@code buffer}.
   *
   * <p>Resolution rules:
   * <ul>
   *   <li>{@code "$N"} (numeric backref) — append {@code input[capStart..capEnd)} for group N.</li>
   *   <li>{@code "${name}"} (named backref) — resolve name to index via metadata, then same.</li>
   *   <li>Anything else — append as a literal string.</li>
   *   <li>Unmatched group (slot = {@code Long.MIN_VALUE}) — append empty string (C-9).</li>
   * </ul>
   *
   * @param delta   the raw delta string from the {@link TransOutput} instruction
   * @param scratch the current capture state buffer (stride entries, even=start, odd=end)
   * @param input   the full input string
   * @param prog    the compiled program (used to look up named group indices)
   * @param buffer  the buffer to append the resolved output to
   */
  private static void appendResolvedDelta(
      String delta, long[] scratch, String input, Prog prog, StringBuilder buffer) {
    if (delta.isEmpty()) {
      return;
    }
    // Numeric backref: "$N" where N >= 1
    if (delta.length() >= 2 && delta.charAt(0) == '$' && delta.charAt(1) != '{') {
      try {
        int groupIndex = Integer.parseInt(delta.substring(1)); // 1-based
        appendCaptureGroup(groupIndex, scratch, input, buffer);
        return;
      } catch (NumberFormatException ignored) {
        // Fall through to literal handling.
      }
    }
    // Named backref: "${name}"
    if (delta.length() >= 4 && delta.startsWith("${") && delta.endsWith("}")) {
      String name = delta.substring(2, delta.length() - 1);
      java.util.Map<String, Integer> groupNames = prog.metadata.groupNames();
      Integer oneBasedIndex = groupNames.get(name);
      if (oneBasedIndex != null) {
        appendCaptureGroup(oneBasedIndex, scratch, input, buffer);
        return;
      }
      // Unknown named group: append empty string (consistent with unmatched group, C-9).
      return;
    }
    // Plain literal.
    buffer.append(delta);
  }

  /**
   * Appends the captured text for group {@code oneBasedIndex} to {@code buffer}.
   *
   * <p>If the group did not participate in the match (slot = {@link Long#MIN_VALUE}) or the
   * group index is out of range, appends nothing (consistent with JDK behaviour, C-9).
   *
   * @param oneBasedIndex the 1-based group index
   * @param scratch       the current capture state buffer
   * @param input         the full input string
   * @param buffer        the buffer to append to
   */
  private static void appendCaptureGroup(
      int oneBasedIndex, long[] scratch, String input, StringBuilder buffer) {
    int zeroBasedIndex = oneBasedIndex - 1;
    int startSlot = zeroBasedIndex * 2;
    int endSlot = startSlot + 1;
    if (endSlot >= scratch.length) {
      return; // group index out of range; append nothing (C-9)
    }
    long rawStart = scratch[startSlot];
    long rawEnd = scratch[endSlot];
    if (rawStart == Long.MIN_VALUE || rawEnd == Long.MIN_VALUE) {
      return; // unmatched optional group; append empty string (C-9)
    }
    int capStart = (int) rawStart;
    int capEnd = (int) rawEnd;
    if (capStart >= 0 && capEnd >= capStart) {
      buffer.append(input, capStart, capEnd);
    }
  }

  /**
   * Commits the consuming instruction at {@code dstPc} to {@code next}, copying the current
   * {@code scratch} capture state into the slab row for {@code dstPc}. If {@code dstPc} is
   * already present in {@code next}, the existing entry has higher priority (earlier insertion
   * order) and is kept; the new entry is discarded.
   *
   * @param dstPc   the destination PC (consuming instruction or Accept)
   * @param next    the next-step active states
   * @param scratch the current capture state buffer
   */
  private static void commitToNext(int dstPc, ActiveStates next, long[] scratch) {
    if (!next.set.contains(dstPc)) {
      next.set.add(dstPc);
      // Copy all capture slots from scratch into the slab row for dstPc.
      System.arraycopy(scratch, 0, next.slotTable, dstPc * next.stride, next.stride);
    }
    // If already present: existing entry has higher priority (earlier insertion). Discard.
  }

  // -----------------------------------------------------------------------
  // Push helpers for the epsilon stack
  // -----------------------------------------------------------------------

  /**
   * Pushes a {@code FRAME_EXPLORE} frame onto the epsilon stack.
   *
   * <p>Layout (type pushed last so it sits at top-1 for O(1) type-first pop):
   * {@code [pc, 0]}
   *
   * @param pc the PC to explore
   */
  private void pushExplore(int pc) {
    epsilonStack[epsilonTop++] = pc;
    epsilonStack[epsilonTop++] = FRAME_EXPLORE;
  }

  /**
   * Pushes a {@code FRAME_RESTORE_CAPTURE} frame onto the epsilon stack.
   *
   * <p>Layout (type pushed last):
   * {@code [(int)(oldValue & 0xFFFFFFFFL), (int)(oldValue >>> 32), slotIdx, 1]}
   *
   * @param slotIdx  the scratch buffer index to restore when this frame is popped
   * @param oldValue the value to restore
   */
  private void pushRestoreCapture(int slotIdx, long oldValue) {
    epsilonStack[epsilonTop++] = (int) (oldValue & 0xFFFFFFFFL);
    epsilonStack[epsilonTop++] = (int) (oldValue >>> 32);
    epsilonStack[epsilonTop++] = slotIdx;
    epsilonStack[epsilonTop++] = FRAME_RESTORE;
  }

  // -----------------------------------------------------------------------
  // populateScratch / extractResult / cleanupActiveStates
  // -----------------------------------------------------------------------

  /**
   * Copies the slot row for {@code srcPc} from {@code states} into {@code scratch}.
   *
   * @param scratch the destination scratch buffer; must have length {@code stride}
   * @param states  the source active states
   * @param srcPc   the source program counter
   * @param stride  the number of slots per state
   */
  private static void populateScratch(
      long[] scratch, ActiveStates states, int srcPc, int stride) {
    System.arraycopy(states.slotTable, srcPc * stride, scratch, 0, stride);
  }

  /**
   * Extracts the match result array from the accepted state at {@code srcPc} in {@code states}.
   *
   * <p>Result format: {@code [matchStart, matchEnd, cap0start, cap0end, …]}.
   *
   * @param states     the active states containing the accepted thread
   * @param srcPc      the program counter of the accepted thread
   * @param matchStart the start position of this NFA run
   * @param matchEnd   the position at which Accept was seen
   * @param numGroups  the number of capturing groups
   * @return a new result array; never null
   */
  private static int[] extractResult(
      ActiveStates states, int srcPc, int matchStart, int matchEnd, int numGroups) {
    int[] result = new int[2 + numGroups * 2];
    // The keep-start slot is at index (stride - 1). stride = Math.max(1, numGroups * 2) + 1
    // for the main program, but sub-programs (lookahead/lookbehind bodies) are allocated
    // with the old stride = Math.max(1, numGroups * 2) and do not have a keep-start slot.
    // Guard: keepSlotIdx must be < states.stride to avoid an out-of-bounds access.
    int keepSlotIdx = Math.max(1, numGroups * 2);
    long keepStartRaw = (keepSlotIdx < states.stride)
        ? states.getSlot(srcPc, keepSlotIdx)
        : Long.MIN_VALUE;
    result[0] = (keepStartRaw != Long.MIN_VALUE && keepStartRaw >= 0)
        ? (int) keepStartRaw : matchStart;
    result[1] = matchEnd;
    for (int i = 0; i < numGroups; i++) {
      long rawS = states.getSlot(srcPc, 2 * i);
      long rawE = states.getSlot(srcPc, 2 * i + 1);
      result[2 + 2 * i] = (rawS == Long.MIN_VALUE) ? -1 : (int) rawS;
      result[2 + 2 * i + 1] = (rawE == Long.MIN_VALUE) ? -1 : (int) rawE;
    }
    return result;
  }

  /**
   * Resets dirty slot rows in {@code states} to {@link Long#MIN_VALUE} and clears the set.
   *
   * <p>Only rows referenced by {@code states.set} are cleared, so cost is O(active states *
   * stride), not O(nfaSize * stride).
   *
   * @param states the active states object to clean up
   * @param stride the number of slots per state
   */
  private static void cleanupActiveStates(ActiveStates states, int stride) {
    for (int i = 0; i < states.set.size(); i++) {
      int pc = states.set.get(i);
      Arrays.fill(states.slotTable, pc * stride, pc * stride + stride, Long.MIN_VALUE);
    }
    states.set.clear();
  }

  // -----------------------------------------------------------------------
  // Backreference continuation
  // -----------------------------------------------------------------------

  /**
   * Continues the NFA simulation from {@code startPos} after a backreference has consumed
   * multiple characters. Allocates its own {@link ActiveStates} objects to avoid aliasing
   * with the caller's {@code curr}/{@code next} buffers (backref handling is not on the hot
   * path for this session's benchmark targets).
   *
   * @param prog         the program to execute
   * @param input        the full input string
   * @param startPos     the position after the consumed backref text
   * @param to           the exclusive end of the search range
   * @param numGroups    the number of capturing groups
   * @param stride       slots per state ({@code Math.max(1, numGroups * 2)})
   * @param seedPc       the PC to start from (the instruction after the BackrefCheck)
   * @param seedCaptures the full slot row from the parent thread; length must equal
   *                     {@code stride}
   * @return the match result array, or {@code null} if no match
   */
  private int[] continueFromPosition(
      Prog prog, String input, int startPos, int to,
      int numGroups, int stride,
      int seedPc,
      long[] seedCaptures) {

    int instrCount = prog.getInstructionCount();
    // Fresh allocations: backref path is not the hot path.
    ActiveStates subCurr = new ActiveStates(instrCount, stride);
    ActiveStates subNext = new ActiveStates(instrCount, stride);
    long[] subScratch = new long[stride];
    SparseSet subVisited = new SparseSet(instrCount);
    // Save and restore epsilonStack state (shared instance field) across the sub-call.
    int savedEpsilonTop = epsilonTop;

    // Seed: compute epsilon closure from seedPc at startPos into subNext, then swap.
    subNext.set.clear();
    subVisited.clear();
    System.arraycopy(seedCaptures, 0, subScratch, 0, stride);
    // continueFromPosition is used for backref continuation; no transducer output needed.
    // Use default bounds: anchoring=true, transparent=false.
    computeClosure(prog, input, startPos, startPos, to, seedPc,
        subVisited, subNext, subScratch, null, 0, true, false);

    ActiveStates tmp = subCurr;
    subCurr = subNext;
    subNext = tmp;

    int[] acceptedResult = null;

    for (int pos = startPos; pos <= to; pos++) {
      if (subCurr.set.size() == 0) {
        break;
      }

      subNext.set.clear();
      subVisited.clear();

      char ch = (pos < to) ? input.charAt(pos) : '\0';

      for (int ci = 0; ci < subCurr.set.size(); ci++) {
        int srcPc = subCurr.set.get(ci);
        Instr instr = prog.getInstruction(srcPc);

        if (instr instanceof Accept) {
          // First Accept in insertion order is highest priority; break to prevent
          // lower-priority threads from overriding it at a later position.
          acceptedResult = extractResult(subCurr, srcPc, startPos, pos, numGroups);
          break;
        }

        if (pos >= to) {
          continue;
        }

        if (instr instanceof CharMatch cm) {
          if (ch < cm.lo() || ch > cm.hi()) {
            continue;
          }
          populateScratch(subScratch, subCurr, srcPc, stride);
          computeClosure(prog, input, pos + 1, startPos, to, cm.next(),
              subVisited, subNext, subScratch, null, 0, true, false);

        } else if (instr instanceof AnyChar ac) {
          populateScratch(subScratch, subCurr, srcPc, stride);
          computeClosure(prog, input, pos + 1, startPos, to, ac.next(),
              subVisited, subNext, subScratch, null, 0, true, false);

        } else if (instr instanceof BackrefCheck br) {
          int startSlot = br.groupIndex() * 2;
          int endSlot = startSlot + 1;
          if (endSlot < stride) {
            long rawStart = subCurr.getSlot(srcPc, startSlot);
            long rawEnd = subCurr.getSlot(srcPc, endSlot);
            int capStart = (rawStart == Long.MIN_VALUE) ? -1 : (int) rawStart;
            int capEnd = (rawEnd == Long.MIN_VALUE) ? -1 : (int) rawEnd;
            if (capStart >= 0 && capEnd >= 0) {
              String captured = input.substring(capStart, capEnd);
              int capturedLen = captured.length();
              if (capturedLen == 0) {
                populateScratch(subScratch, subCurr, srcPc, stride);
                computeClosure(prog, input, pos, startPos, to, br.next(),
                    subVisited, subNext, subScratch, null, 0, true, false);
              } else if (pos + capturedLen <= to
                  && input.regionMatches(br.caseInsensitive(), pos, captured, 0, capturedLen)) {
                long[] subSeedCaptures = new long[stride];
                System.arraycopy(subCurr.slotTable, srcPc * stride, subSeedCaptures, 0, stride);
                int[] subResult = continueFromPosition(
                    prog, input, pos + capturedLen, to, numGroups, stride,
                    br.next(), subSeedCaptures);
                if (subResult != null) {
                  acceptedResult = subResult;
                }
              }
            }
          }
          continue;
        }
      }

      // Swap subCurr <-> subNext.
      ActiveStates swapTmp = subCurr;
      subCurr = subNext;
      subNext = swapTmp;
    }

    // Final scan for Accept in surviving subCurr.
    for (int ci = 0; ci < subCurr.set.size(); ci++) {
      int pc = subCurr.set.get(ci);
      if (prog.getInstruction(pc) instanceof Accept) {
        acceptedResult = extractResult(subCurr, pc, startPos, to, numGroups);
        break;
      }
    }

    cleanupActiveStates(subCurr, stride);
    // Restore epsilonTop so the caller's closure computation is unaffected.
    epsilonTop = savedEpsilonTop;
    return acceptedResult;
  }

  // -----------------------------------------------------------------------
  // Sub-program execution (lookahead / lookbehind)
  // -----------------------------------------------------------------------

  /**
   * Runs a sub-program against the input from {@code from} with the upper bound {@code to},
   * returning {@code true} if any match is found.
   *
   * <p>Used by lookahead assertions; the parent thread's capture state is not modified.
   * Allocates its own buffers (sub-programs are not on the benchmark hot path).
   *
   * @param subProg the assertion body program
   * @param input   the full input string
   * @param from    the position at which to start the sub-execution
   * @param to      the exclusive upper bound of the search range
   * @return {@code true} if the sub-program matches at {@code from}
   */
  private boolean runSubProg(Prog subProg, String input, int from, int to,
      long[] outerScratch) {
    if (subProg == null || subProg.getInstructionCount() == 0) {
      return true; // empty sub-program always matches
    }
    int subGroups = countGroups(subProg);
    int subStride = Math.max(1, subGroups * 2) + 1;
    int subCount = subProg.getInstructionCount();
    ActiveStates subCurr = new ActiveStates(subCount, subStride);
    ActiveStates subNext = new ActiveStates(subCount, subStride);
    long[] subScratch = new long[subStride];
    SparseSet subVisited = new SparseSet(subCount);
    // Swap in a fresh epsilon stack so the sub-program's computeClosure does not corrupt
    // the outer closure's stack contents. The outer closure resumes from the saved stack
    // after runNfa returns.
    int[] savedStack = epsilonStack;
    int savedTop = epsilonTop;
    epsilonStack = new int[subCount * 6];
    epsilonTop = 0;
    // Sub-programs for lookahead use default bounds. Pass regionStart=0 so that inner
    // lookbehinds within the sub-program are not restricted to the outer region boundary.
    // Pass outerScratch so BackrefCheck in the body can fall back to outer group captures
    // when the slot is out of range in the sub-program's slab (e.g. \1 in (?!.*\1)).
    int[] result = runNfa(subProg, input, from, 0, to, subGroups, subStride,
        subCurr, subNext, subScratch, subVisited, null, new String[1], 0, true, false,
        outerScratch);
    epsilonStack = savedStack;
    epsilonTop = savedTop;
    return result != null;
  }

  /**
   * Runs a sub-program against {@code input[from..to]} and returns {@code true} only if
   * it produces a match that spans the entire interval {@code [from, to]} exactly.
   *
   * <p>Used by lookbehind assertions. Allocates its own buffers.
   *
   * @param subProg the assertion body program (forward-compiled)
   * @param input   the full input string
   * @param from    the start of the lookbehind window (inclusive)
   * @param to      the end of the lookbehind window (exclusive / current position)
   * @return {@code true} if the sub-program matches exactly {@code [from, to]}
   */
  private boolean runSubProgExact(Prog subProg, String input, int from, int to,
      long[] outerScratch) {
    if (subProg == null || subProg.getInstructionCount() == 0) {
      return from == to; // only matches empty window
    }
    // Build an end-anchored variant of the sub-program. By inserting EndText before Accept,
    // Accept is only reachable when pos == to. This prevents the NFA from committing to a
    // short match (e.g. from a lazy quantifier) before exploring threads that reach to.
    Prog anchored = buildEndAnchoredProg(subProg);
    int subGroups = countGroups(anchored);
    int subStride = Math.max(1, subGroups * 2) + 1;
    int subCount = anchored.getInstructionCount();
    ActiveStates subCurr = new ActiveStates(subCount, subStride);
    ActiveStates subNext = new ActiveStates(subCount, subStride);
    long[] subScratch = new long[subStride];
    SparseSet subVisited = new SparseSet(subCount);
    // Swap in a fresh epsilon stack so the sub-program's computeClosure does not corrupt
    // the outer closure's stack contents.
    int[] savedStack = epsilonStack;
    int savedTop = epsilonTop;
    epsilonStack = new int[subCount * 6];
    epsilonTop = 0;
    // Sub-programs for lookbehind use default bounds. Pass regionStart=0 so that inner
    // lookbehinds within the sub-program are not restricted to the outer region boundary.
    // Pass outerScratch so BackrefCheck in the body can fall back to outer group captures.
    int[] result = runNfa(anchored, input, from, 0, to, subGroups, subStride,
        subCurr, subNext, subScratch, subVisited, null, new String[1], 0, true, false,
        outerScratch);
    epsilonStack = savedStack;
    epsilonTop = savedTop;
    return result != null && result[0] == from && result[1] == to;
  }

  /**
   * Builds a new {@link Prog} identical to {@code subProg} but with an {@link EndText}
   * instruction inserted immediately before the final {@link Accept}.
   *
   * <p>This forces the sub-program to only accept at the region end ({@code to}), which is
   * required for variable-length lookbehind bodies where the body might otherwise commit to a
   * short match (e.g. from a lazy quantifier) before exploring all possible match lengths.
   *
   * @param subProg the sub-program to modify; must not be null
   * @return a new end-anchored {@link Prog}, never null
   */
  private static Prog buildEndAnchoredProg(Prog subProg) {
    Instr[] orig = subProg.instructions;
    int n = orig.length;
    // Replace every Accept with EndText(n) and append a new Accept at position n.
    Instr[] anchored = new Instr[n + 1];
    for (int i = 0; i < n; i++) {
      if (orig[i] instanceof Accept) {
        anchored[i] = new EndText(n);
      } else {
        anchored[i] = orig[i];
      }
    }
    anchored[n] = new Accept();
    return new Prog(anchored, subProg.metadata, subProg.startPc, n);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /** Returns the number of capturing groups by scanning for SaveCapture instructions. */
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
   * Builds the list of captured group strings from the result array.
   *
   * @param result    the accepted thread result array in format {@code [start, end, cap...]}
   * @param input     the full input string
   * @param numGroups the number of capturing groups
   * @return the group string list; entries are {@code null} for non-participating groups
   */
  private static List<String> buildGroupList(int[] result, String input, int numGroups) {
    List<String> groups = new ArrayList<>(numGroups);
    for (int i = 0; i < numGroups; i++) {
      int start = result[2 + i * 2];
      int end = result[2 + i * 2 + 1];
      if (start >= 0 && end >= start) {
        groups.add(input.substring(start, end));
      } else {
        groups.add(null);
      }
    }
    return groups;
  }

  /**
   * Builds the per-group span list from the result array.
   *
   * <p>Each element is a two-element {@code int[]} of the form {@code {start, end}},
   * or {@code {-1, -1}} when the group did not participate in the match.
   *
   * @param result    the accepted thread result array
   * @param numGroups the number of capturing groups
   * @return the span list; never null
   */
  private static List<int[]> buildGroupSpanList(int[] result, int numGroups) {
    List<int[]> spans = new ArrayList<>(numGroups);
    for (int i = 0; i < numGroups; i++) {
      int start = result[2 + i * 2];
      int end = result[2 + i * 2 + 1];
      spans.add(new int[]{start, end});
    }
    return spans;
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
   * Returns {@code true} if {@code c} is a Unicode word character
   * ({@code Character.isLetterOrDigit(c) || c == '_'}).
   *
   * @param c the character to test
   * @return {@code true} if {@code c} is a Unicode word character
   */
  private static boolean isWordCharUnicode(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Returns {@code true} if the character at {@code pos} in {@code input} is effectively a word
   * character, applying JDK's Unicode non-spacing mark (NSM) extension rule.
   *
   * <p>A Unicode non-spacing mark ({@code NON_SPACING_MARK}) is considered a word character when
   * it directly follows — possibly through a chain of other non-spacing marks — a word character.
   * An NSM at the start of a word (preceded only by non-word characters) is treated as non-word.
   * This replicates the behaviour of {@code java.util.regex} for bug 4979006.
   *
   * <p>Characters outside the string range are treated as the {@code '\0'} sentinel (non-word).
   *
   * @param input       the full input string; must not be null
   * @param pos         the position to evaluate; must be {@code >= 0}
   * @param to          the exclusive upper bound of the active range
   * @param unicodeCase {@code true} to use the Unicode-aware word-character predicate
   * @return {@code true} if the position is effectively a word character
   */
  private static boolean isEffectiveWordChar(String input, int pos, int to, boolean unicodeCase) {
    if (pos >= to) {
      return false;
    }
    char c = input.charAt(pos);
    boolean word = unicodeCase ? isWordCharUnicode(c) : isWordChar(c);
    if (word) {
      return true;
    }
    if (Character.getType(c) != Character.NON_SPACING_MARK) {
      return false;
    }
    // NSM: walk backwards through any consecutive NSMs to find the base character.
    for (int i = pos - 1; i >= 0; i--) {
      char base = input.charAt(i);
      boolean baseWord = unicodeCase ? isWordCharUnicode(base) : isWordChar(base);
      if (baseWord) {
        return true;
      }
      if (Character.getType(base) != Character.NON_SPACING_MARK) {
        return false;
      }
    }
    return false;
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
   * @param unicodeCase {@code true} to use the Unicode-aware word-character predicate
   * @return {@code true} if a word boundary exists at {@code pos}
   */
  private static boolean isWordBoundary(String input, int pos, int to, boolean unicodeCase) {
    boolean prevWord = pos > 0 && isEffectiveWordChar(input, pos - 1, to, unicodeCase);
    boolean currWord = isEffectiveWordChar(input, pos, to, unicodeCase);
    return prevWord != currWord;
  }

  private static MatchResult noMatch() {
    return new MatchResult(false, -1, -1, List.of(), null, 0L, 0L, 0);
  }
}
