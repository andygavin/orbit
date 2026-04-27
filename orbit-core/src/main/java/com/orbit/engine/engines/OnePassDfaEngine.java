package com.orbit.engine.engines;

import com.orbit.engine.Engine;
import com.orbit.engine.dfa.PrecomputedDfa;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One-pass DFA engine for patterns classified as {@link com.orbit.util.EngineHint#ONE_PASS_SAFE}.
 *
 * <p>When a {@link PrecomputedDfa} is available on the {@link Prog}, this engine executes a
 * simple flat-array table walk: for each character position, look up the next state in the
 * precomputed transition table and apply any capture operations recorded for that state.
 * No epsilon-closure computation is performed at match time.
 *
 * <p>If the {@code Prog} has no precomputed DFA (because the NFA exceeded the DFA state limit
 * during precompute), this engine transparently delegates to {@link LazyDfaEngine}.
 *
 * <p>This class is thread-safe. The singleton instance held by {@link com.orbit.engine.MetaEngine}
 * may be used concurrently by multiple matcher threads. All per-call mutable state is
 * stack-local.
 */
public final class OnePassDfaEngine implements Engine {

  /** Fallback engine used when no precomputed DFA is available. */
  private static final LazyDfaEngine FALLBACK = new LazyDfaEngine();

  /**
   * Executes the one-pass DFA match over the specified range of {@code input}.
   *
   * <p>Falls back to {@link LazyDfaEngine} if the program has no precomputed DFA.
   *
   * @param prog the compiled program; must not be null
   * @param input the input string; must not be null
   * @param from the starting search index (inclusive); must be {@code >= 0}
   * @param to the ending search index (exclusive); must be {@code <= input.length()}
   * @return the match result; never null
   */
  @Override
  public MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart) {
    PrecomputedDfa dfa = prog.precomputedDfa;
    if (dfa == null) {
      return FALLBACK.execute(prog, input, from, to, lastMatchEnd, anchoringBounds,
          transparentBounds, regionStart);
    }
    return run(dfa, input, from, to);
  }

  // ---------------------------------------------------------------------------
  // Core match loop
  // ---------------------------------------------------------------------------

  private static MatchResult run(PrecomputedDfa dfa, String input, int from, int to) {
    int alphabetSize = dfa.alphabetMap().classCount;
    int[] table = dfa.transitionTable();
    boolean[] accepting = dfa.accepting();
    int[][] captureOps = dfa.captureOps();

    // Derive group count from the capture-ops arrays.
    int groupCount = countGroups(captureOps);

    for (int start = from; start <= to; start++) {
      int[] localCaptures = newCaptures(groupCount);
      int state = dfa.initialState();
      applyCapOps(captureOps[state], start, localCaptures);

      int matchEnd = -1;
      int[] matchCaptures = null;

      if (accepting[state]) {
        matchEnd = start;
        matchCaptures = localCaptures.clone();
      }

      for (int pos = start; pos < to; pos++) {
        int cls = dfa.alphabetMap().classOf(input.charAt(pos));
        int next = table[state * alphabetSize + cls];
        if (next == PrecomputedDfa.DEAD) {
          break;
        }
        state = next;
        applyCapOps(captureOps[state], pos + 1, localCaptures);
        if (accepting[state]) {
          matchEnd = pos + 1;
          matchCaptures = localCaptures.clone();
        }
      }

      if (matchEnd >= 0) {
        return buildMatchResult(input, start, matchEnd, matchCaptures, groupCount);
      }
    }

    return noMatch();
  }

  // ---------------------------------------------------------------------------
  // Capture helpers
  // ---------------------------------------------------------------------------

  /**
   * Applies a set of capture operations to the capture array, recording {@code pos} at the
   * appropriate slot for each operation.
   *
   * @param ops the encoded operations; may be null (no-op)
   * @param pos the current input position to record
   * @param captures the capture array to update in place
   */
  private static void applyCapOps(int[] ops, int pos, int[] captures) {
    if (ops == null) {
      return;
    }
    for (int op : ops) {
      int idx = op >>> 1;
      boolean isEnd = (op & 1) == 1;
      int slot = isEnd ? idx * 2 + 1 : idx * 2;
      if (slot < captures.length) {
        captures[slot] = pos;
      }
    }
  }

  /** Allocates and initialises a capture array with all slots set to -1. */
  private static int[] newCaptures(int groupCount) {
    if (groupCount == 0) {
      return new int[0];
    }
    int[] captures = new int[groupCount * 2];
    Arrays.fill(captures, -1);
    return captures;
  }

  /**
   * Infers the number of capturing groups from the per-state capture-ops arrays. Scans all
   * encoded ops and returns the highest group index + 1.
   */
  private static int countGroups(int[][] captureOps) {
    int max = -1;
    for (int[] ops : captureOps) {
      if (ops == null) {
        continue;
      }
      for (int op : ops) {
        int idx = op >>> 1;
        if (idx > max) {
          max = idx;
        }
      }
    }
    return max + 1;
  }

  // ---------------------------------------------------------------------------
  // Result construction
  // ---------------------------------------------------------------------------

  private static MatchResult buildMatchResult(
      String input, int matchStart, int matchEnd, int[] captures, int groupCount) {
    if (groupCount == 0) {
      return new MatchResult(true, matchStart, matchEnd, List.of(), null, 0L, 0L, 0);
    }
    List<String> groups = new ArrayList<>(groupCount);
    List<int[]> spans = new ArrayList<>(groupCount);
    for (int g = 0; g < groupCount; g++) {
      int gs = captures[g * 2];
      int ge = captures[g * 2 + 1];
      if (gs >= 0 && ge >= 0 && gs <= ge) {
        groups.add(input.substring(gs, ge));
        spans.add(new int[]{gs, ge});
      } else {
        groups.add(null);
        spans.add(new int[]{-1, -1});
      }
    }
    return new MatchResult(true, matchStart, matchEnd, groups, spans, null, 0L, 0L, 0);
  }

  private static MatchResult noMatch() {
    return new MatchResult(false, -1, -1, List.of(), null, 0L, 0L, 0);
  }
}
