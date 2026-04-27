package com.orbit.engine.engines;

import com.orbit.engine.Engine;
import com.orbit.engine.dfa.AlphabetMap;
import com.orbit.engine.dfa.DfaState;
import com.orbit.engine.dfa.DfaStateCache;
import com.orbit.prog.Accept;
import com.orbit.prog.AnyByte;
import com.orbit.prog.AnyChar;
import com.orbit.prog.BackrefCheck;
import com.orbit.prog.BeginG;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.ByteMatch;
import com.orbit.prog.ByteRangeMatch;
import com.orbit.prog.CharMatch;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EndZ;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Fail;
import com.orbit.prog.Instr;
import com.orbit.prog.Lookahead;
import com.orbit.prog.LookaheadNeg;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.Split;
import com.orbit.prog.UnionSplit;
import com.orbit.prog.TransOutput;
import com.orbit.prog.WordBoundary;
import com.orbit.util.EngineHint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy on-the-fly DFA engine for patterns classified as {@link EngineHint#DFA_SAFE} or {@link
 * EngineHint#ONE_PASS_SAFE}.
 *
 * <h2>Construction</h2>
 *
 * <p>DFA states are not pre-built from the compiled {@link Prog}. Instead, each DFA state is
 * computed on demand as the input is scanned and cached in a per-{@code Prog} {@link DfaStateCache}
 * for reuse. A DFA state is the epsilon closure of a set of NFA program counter (PC) values —
 * represented as a sorted {@code int[]} of PC indices.
 *
 * <h2>When this engine is used</h2>
 *
 * <p>{@link com.orbit.engine.MetaEngine} routes here for {@code EngineHint.DFA_SAFE} and {@code
 * EngineHint.ONE_PASS_SAFE} patterns. Patterns with {@code EngineHint.PIKEVM_ONLY} or {@code
 * EngineHint.NEEDS_BACKTRACKER} are never routed here.
 *
 * <h2>Match semantics</h2>
 *
 * <p>Produces leftmost-longest matches, identical to the results of {@link PikeVmEngine} for the
 * same pattern.
 *
 * <h2>Capture group handling</h2>
 *
 * <p>For {@code ONE_PASS_SAFE} patterns, capture groups are extracted directly from the DFA
 * simulation. For {@code DFA_SAFE} patterns that have capturing groups but are not one-pass safe,
 * the engine operates in hybrid mode: the DFA determines the match boundaries and then {@code
 * PikeVmEngine} is run on the matched substring to extract group values.
 *
 * <h2>Cache behavior</h2>
 *
 * <p>DFA states are cached per {@code Prog} instance in a {@link DfaStateCache}. The cache is
 * bounded to 1,024 states ({@code DfaStateCache.MAX_STATES}). When the bound is exceeded the cache
 * is flushed completely and this engine delegates to {@code PikeVmEngine} for the remainder of the
 * current {@code execute()} call. Future calls rebuild the cache from scratch.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is thread-safe. The singleton instance held by {@code MetaEngine} may be used
 * concurrently by multiple {@code Matcher} instances. All per-call mutable state is stack-local;
 * the shared cache uses {@code ConcurrentHashMap}.
 */
public final class LazyDfaEngine implements Engine {

  /**
   * Per-(Prog, regionEnd) DFA state caches.
   *
   * <p>DFA state transitions embed the results of position-dependent anchor checks such as
   * {@link com.orbit.prog.EndText} ({@code \z}) and {@link com.orbit.prog.EndZ} ({@code \Z}).
   * These checks compare the current position against {@code to} (the region end). A transition
   * cached while running with {@code to=N} may incorrectly classify a state as non-accepting
   * when later reused with {@code to=M} where the anchor would pass. Keying by {@code (Prog, to)}
   * ensures that transitions are never reused across different region-end values.
   *
   * <p>{@code Prog} identity is used (default {@code Object.equals}/{@code hashCode}) because
   * {@link com.orbit.api.Pattern} interns compiled programs: the same pattern string and flags
   * always produce the same {@code Prog} instance.
   */
  private final ConcurrentHashMap<Long, DfaStateCache> caches = new ConcurrentHashMap<>();

  /** Per-Prog alphabet maps. */
  private final ConcurrentHashMap<Prog, AlphabetMap> alphabetMaps = new ConcurrentHashMap<>();

  /** Fallback engine for hybrid capture extraction and cache-saturation fallback. */
  private final PikeVmEngine fallback = new PikeVmEngine();

  /**
   * Executes a lazy DFA match over the specified range of {@code input}.
   *
   * @param prog the compiled program; must not be null; must have {@code EngineHint.DFA_SAFE} or
   *     {@code EngineHint.ONE_PASS_SAFE}; must not contain {@code BackrefCheck}, {@code Lookahead},
   *     {@code LookaheadNeg}, or {@code TransOutput} instructions
   * @param input the input string; must not be null
   * @param from the starting search index (inclusive); must be {@code >= 0}
   * @param to the ending search index (exclusive); must be {@code <= input.length()}
   * @return the match result; never null
   * @throws IllegalStateException if {@code prog} contains DFA-unsafe instructions
   * @throws IllegalArgumentException if {@code from < 0}, {@code to > input.length()}, or {@code
   *     from > to}
   */
  @Override
  public MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart) {
    if (from < 0) {
      throw new IllegalArgumentException("from must be >= 0, got: " + from);
    }
    if (to > input.length()) {
      throw new IllegalArgumentException(
          "to must be <= input.length(), got to=" + to + " length=" + input.length());
    }
    if (from > to) {
      return noMatch();
    }

    // Guard: if the prog contains DFA-unsafe instructions (due to AnalysisVisitor
    // not propagating hints from children to parent nodes), fall back to PikeVmEngine.
    if (hasDfaUnsafeInstructions(prog)) {
      return fallback.execute(prog, input, from, to, lastMatchEnd, anchoringBounds,
          transparentBounds, regionStart);
    }

    if (prog.getInstructionCount() == 0) {
      return new MatchResult(true, from, from, List.of(), null, 0L, 0L, 0);
    }

    // NOTE: prog.metadata has a dummy groupCount=0 (set by Pattern.Compiler).
    // The real group count must be derived from SaveCapture instructions, mirroring PikeVmEngine.
    boolean isOnePassSafe = prog.metadata.hint() == EngineHint.ONE_PASS_SAFE;
    int groupCount = countGroups(prog);

    // Key = (Prog identity hash << 32) | to. Combines Prog identity with the region-end value
    // so that position-dependent anchor transitions (EndText, EndZ) are not reused across
    // different region ends. Prog identity hash is safe here because Pattern interns Prog
    // instances: the same compiled program is always the same object within a JVM session.
    long cacheKey = ((long) System.identityHashCode(prog) << 32) | (to & 0xFFFFFFFFL);
    DfaStateCache cache = caches.computeIfAbsent(cacheKey, k -> new DfaStateCache());
    AlphabetMap alpha = alphabetMaps.computeIfAbsent(prog, AlphabetMap::build);

    int matchStart = -1;
    int matchEnd = -1;
    int[] matchCaptures = null;

    for (int startPos = from; startPos <= to; startPos++) {

      if (cache.isSaturated()) {
        // Cache was saturated; fall back to PikeVmEngine for the remainder.
        if (matchStart >= 0) {
          return buildMatchResult(prog, input, matchStart, matchEnd, matchCaptures, groupCount);
        }
        return fallback.execute(prog, input, startPos, to, lastMatchEnd,
            anchoringBounds, transparentBounds, regionStart);
      }

      // Compute initial DFA state via epsilon closure from startPc at startPos.
      int[] liveCaptures = isOnePassSafe && groupCount > 0 ? newCaptures(groupCount) : null;
      DfaState current =
          epsilonClosure(prog, new int[] {prog.startPc}, startPos, to, input, liveCaptures,
              lastMatchEnd);
      current = cache.intern(current);

      if (current == null) {
        // Cache saturated during intern.
        if (matchStart >= 0) {
          return buildMatchResult(prog, input, matchStart, matchEnd, matchCaptures, groupCount);
        }
        return fallback.execute(prog, input, startPos, to, lastMatchEnd,
            anchoringBounds, transparentBounds, regionStart);
      }

      int lastAcceptPos = -1;
      int[] lastAcceptCaptures = null;

      if (current.isAccepting) {
        lastAcceptPos = startPos;
        lastAcceptCaptures = liveCaptures != null ? liveCaptures.clone() : null;
      }

      // Scan forward from startPos consuming characters.
      for (int pos = startPos; pos < to; pos++) {
        char ch = input.charAt(pos);
        int classId = alpha.classOf(ch);

        DfaState next = cache.getCachedTransition(current, classId);
        if (next == null) {
          int[] nextCaptures = liveCaptures != null ? liveCaptures.clone() : null;
          next =
              computeTransition(
                  prog, current, ch, pos + 1, to, input, alpha, cache, nextCaptures, lastMatchEnd);
          if (next == null) {
            // Cache saturated during computeTransition.
            if (lastAcceptPos >= 0) {
              matchStart = startPos;
              matchEnd = lastAcceptPos;
              matchCaptures = lastAcceptCaptures;
              break;
            }
            if (matchStart >= 0) {
              return buildMatchResult(prog, input, matchStart, matchEnd, matchCaptures, groupCount);
            }
            return fallback.execute(prog, input, startPos, to, lastMatchEnd,
                anchoringBounds, transparentBounds, regionStart);
          }
          cache.putTransition(current, classId, next, alpha.classCount);

          // Update liveCaptures from the transition's epsilon closure result.
          if (liveCaptures != null && nextCaptures != null) {
            System.arraycopy(nextCaptures, 0, liveCaptures, 0, liveCaptures.length);
          }
        } else if (liveCaptures != null) {
          // Transition was cached; re-run epsilon closure to get updated captures.
          int[] recomputedCaptures = liveCaptures.clone();
          recomputeCaptures(prog, current, ch, pos + 1, to, input, recomputedCaptures, lastMatchEnd);
          System.arraycopy(recomputedCaptures, 0, liveCaptures, 0, liveCaptures.length);
        }

        if (next == DfaState.DEAD) {
          break;
        }

        current = next;

        if (current.isAccepting) {
          lastAcceptPos = pos + 1;
          lastAcceptCaptures = liveCaptures != null ? liveCaptures.clone() : null;
        }
      }

      if (lastAcceptPos >= 0) {
        matchStart = startPos;
        matchEnd = lastAcceptPos;
        matchCaptures = lastAcceptCaptures;
        break; // leftmost match found
      }
    }

    if (matchStart < 0) {
      // hitEnd for LazyDFA failure:
      // For start-anchored patterns, the engine fails at position 'from' without scanning
      // forward — hitEnd = false. For non-anchored patterns, the DFA scanned the full range
      // before concluding no match exists — hitEnd = true when the region is non-empty.
      // Precise per-startPos DFA-state tracking is omitted here because tracking the last
      // DFA state across the outer startPos loop is complex; the distinction matters most
      // for start-anchored vs non-anchored patterns, which this covers correctly.
      boolean hitEnd = !prog.metadata.startAnchored() && (to > from);
      return new MatchResult(false, -1, -1, List.of(), List.of(), null, 0L, to - from, 0, hitEnd);
    }

    boolean hitEnd = (matchEnd == to);
    return buildMatchResult(prog, input, matchStart, matchEnd, matchCaptures, groupCount, hitEnd);
  }

  /**
   * Computes the epsilon closure of the given seed PCs at the given position. Optionally updates
   * {@code liveCaptures} for ONE_PASS_SAFE patterns.
   */
  private DfaState epsilonClosure(
      Prog prog,
      int[] seedPcs,
      int pos,
      int to,
      String input,
      int[] liveCaptures,
      int lastMatchEnd) {

    int instrCount = prog.getInstructionCount();
    boolean[] visited = new boolean[instrCount];
    List<Integer> resultPcs = new ArrayList<>();
    boolean isAccepting = false;

    Deque<Integer> worklist = new ArrayDeque<>();
    for (int pc : seedPcs) {
      if (pc >= 0 && pc < instrCount && !visited[pc]) {
        worklist.push(pc);
      }
    }

    while (!worklist.isEmpty()) {
      int pc = worklist.pop();
      if (visited[pc]) continue;
      visited[pc] = true;

      Instr instr = prog.getInstruction(pc);

      switch (instr) {
        case Accept ignored -> {
          resultPcs.add(pc);
          isAccepting = true;
          // Accept is a sink; do not follow.
        }
        case Fail ignored -> {
          // Dead end; discard this branch.
        }
        case EpsilonJump ej -> {
          if (!visited[ej.next()]) worklist.push(ej.next());
        }
        case Split s -> {
          if (!visited[s.next1()]) worklist.push(s.next1());
          if (!visited[s.next2()]) worklist.push(s.next2());
        }
        case UnionSplit us -> {
          if (!visited[us.next1()]) worklist.push(us.next1());
          if (!visited[us.next2()]) worklist.push(us.next2());
        }
        case SaveCapture sc -> {
          if (liveCaptures != null) {
            int slot = 2 * sc.groupIndex() + (sc.isStart() ? 0 : 1);
            if (slot < liveCaptures.length) {
              liveCaptures[slot] = pos;
            }
          }
          resultPcs.add(pc);
          if (!visited[sc.next()]) worklist.push(sc.next());
        }
        case BeginText bt -> {
          if (pos == 0) {
            if (!visited[bt.next()]) worklist.push(bt.next());
          }
        }
        case EndText et -> {
          if (pos == to) {
            if (!visited[et.next()]) worklist.push(et.next());
          }
        }
        case BeginLine bl -> {
          if (BoundedBacktrackEngine.beginLinePasses(input, pos, to, bl.unixLines())) {
            if (!visited[bl.next()]) worklist.push(bl.next());
          }
        }
        case EndLine el -> {
          if (BoundedBacktrackEngine.endLinePasses(input, pos, to,
              el.multiline(), el.unixLines())) {
            if (!visited[el.next()]) worklist.push(el.next());
          }
        }
        case EndZ ez -> {
          if (BoundedBacktrackEngine.endZPasses(input, pos, to, ez.unixLines())) {
            if (!visited[ez.next()]) worklist.push(ez.next());
          }
        }
        case BeginG bg -> {
          if (pos == lastMatchEnd) {
            if (!visited[bg.next()]) worklist.push(bg.next());
          }
        }
        case WordBoundary wb -> {
          boolean boundary = isWordBoundary(input, pos, to, wb.unicodeCase());
          if (wb.negated() ? !boundary : boundary) {
            if (!visited[wb.next()]) worklist.push(wb.next());
          }
        }
        default -> {
          // Consuming instruction (CharMatch, AnyChar, ByteMatch, ByteRangeMatch, AnyByte):
          // it is a leaf in the epsilon closure.
          resultPcs.add(pc);
        }
      }
    }

    int[] pcs = resultPcs.stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
    return new DfaState(pcs, isAccepting, null);
  }

  /**
   * Computes the next DFA state by consuming character {@code ch} from the given DFA state, then
   * computing the epsilon closure at {@code nextPos}.
   *
   * @return the next DFA state (may be {@link DfaState#DEAD}), or {@code null} if the cache
   *     saturated during interning
   */
  private DfaState computeTransition(
      Prog prog,
      DfaState from,
      char ch,
      int nextPos,
      int to,
      String input,
      AlphabetMap alpha,
      DfaStateCache cache,
      int[] liveCaptures,
      int lastMatchEnd) {

    // Move: collect all NFA states reachable by consuming ch.
    int maxMoved = from.nfaPcs.length;
    int[] movedPcs = new int[maxMoved];
    int movedCount = 0;

    for (int pc : from.nfaPcs) {
      Instr instr = prog.getInstruction(pc);
      switch (instr) {
        case CharMatch cm -> {
          if (ch >= cm.lo() && ch <= cm.hi()) {
            movedPcs[movedCount++] = cm.next();
          }
        }
        case AnyChar ac -> movedPcs[movedCount++] = ac.next();
        case ByteMatch bm -> {
          if ((char) Byte.toUnsignedInt(bm.value()) == ch) {
            movedPcs[movedCount++] = bm.next();
          }
        }
        case ByteRangeMatch brm -> {
          char lo = (char) Byte.toUnsignedInt(brm.lo());
          char hi = (char) Byte.toUnsignedInt(brm.hi());
          if (ch >= lo && ch <= hi) {
            movedPcs[movedCount++] = brm.next();
          }
        }
        case AnyByte ab -> movedPcs[movedCount++] = ab.next();
        default -> {
          // Accept, Fail, epsilon instructions: cannot consume a character.
        }
      }
    }

    if (movedCount == 0) {
      return DfaState.DEAD;
    }

    int[] seedPcs = Arrays.copyOf(movedPcs, movedCount);
    DfaState candidate = epsilonClosure(prog, seedPcs, nextPos, to, input, liveCaptures,
        lastMatchEnd);

    if (candidate.nfaPcs.length == 0 && !candidate.isAccepting) {
      return DfaState.DEAD;
    }

    DfaState canonical = cache.intern(candidate);
    return canonical; // null signals saturation
  }

  /**
   * Re-runs the move+epsilon-closure for ONE_PASS_SAFE patterns when the transition was found in
   * the cache (so we still need to update captures from the epsilon closure).
   */
  private void recomputeCaptures(
      Prog prog, DfaState from, char ch, int nextPos, int to, String input, int[] liveCaptures,
      int lastMatchEnd) {
    int[] movedPcs = new int[from.nfaPcs.length];
    int movedCount = 0;
    for (int pc : from.nfaPcs) {
      Instr instr = prog.getInstruction(pc);
      switch (instr) {
        case CharMatch cm -> {
          if (ch >= cm.lo() && ch <= cm.hi()) movedPcs[movedCount++] = cm.next();
        }
        case AnyChar ac -> movedPcs[movedCount++] = ac.next();
        case ByteMatch bm -> {
          if ((char) Byte.toUnsignedInt(bm.value()) == ch) movedPcs[movedCount++] = bm.next();
        }
        case ByteRangeMatch brm -> {
          char lo = (char) Byte.toUnsignedInt(brm.lo());
          char hi = (char) Byte.toUnsignedInt(brm.hi());
          if (ch >= lo && ch <= hi) movedPcs[movedCount++] = brm.next();
        }
        case AnyByte ab -> movedPcs[movedCount++] = ab.next();
        default -> {}
      }
    }
    if (movedCount > 0) {
      epsilonClosure(prog, Arrays.copyOf(movedPcs, movedCount), nextPos, to, input, liveCaptures,
          lastMatchEnd);
    }
  }

  /**
   * Builds the final {@link MatchResult}. For patterns with capture groups that are not
   * ONE_PASS_SAFE, uses hybrid mode: runs PikeVmEngine on the matched substring to extract groups.
   */
  private MatchResult buildMatchResult(
      Prog prog,
      String input,
      int matchStart,
      int matchEnd,
      int[] captureOffsets,
      int groupCount) {
    return buildMatchResult(prog, input, matchStart, matchEnd, captureOffsets, groupCount, false);
  }

  private MatchResult buildMatchResult(
      Prog prog,
      String input,
      int matchStart,
      int matchEnd,
      int[] captureOffsets,
      int groupCount,
      boolean hitEnd) {

    if (groupCount == 0) {
      return new MatchResult(true, matchStart, matchEnd, List.of(), List.of(), null, 0L, 0L, 0, hitEnd);
    }

    // ONE_PASS_SAFE: captures tracked inline via captureOffsets.
    if (captureOffsets != null) {
      List<String> groups = new ArrayList<>(groupCount);
      List<int[]> spans = new ArrayList<>(groupCount);
      for (int g = 0; g < groupCount; g++) {
        int start = captureOffsets[2 * g];
        int end = captureOffsets[2 * g + 1];
        if (start >= 0 && end >= 0 && start <= end) {
          groups.add(input.substring(start, end));
          spans.add(new int[]{start, end});
        } else {
          groups.add(null);
          spans.add(new int[]{-1, -1});
        }
      }
      return new MatchResult(true, matchStart, matchEnd, groups, spans, null, 0L, 0L, 0, hitEnd);
    }

    // DFA_SAFE with captures: hybrid mode — run PikeVM on the matched substring.
    String sub = input.substring(matchStart, matchEnd);
    MatchResult subResult = fallback.execute(prog, sub, 0, sub.length(), 0, true, false, 0);
    if (!subResult.matches()) {
      // PikeVM disagrees with DFA — should not happen; return boundaries without groups.
      return new MatchResult(true, matchStart, matchEnd, List.of(), List.of(), null, 0L, 0L, 0, hitEnd);
    }
    // Re-adjust spans from the sub-string coordinate system to the full input coordinate system.
    List<int[]> adjustedSpans;
    if (!subResult.groupSpans().isEmpty()) {
      adjustedSpans = new ArrayList<>(subResult.groupSpans().size());
      for (int[] span : subResult.groupSpans()) {
        if (span[0] < 0) {
          adjustedSpans.add(new int[]{-1, -1});
        } else {
          adjustedSpans.add(new int[]{span[0] + matchStart, span[1] + matchStart});
        }
      }
    } else {
      adjustedSpans = List.of();
    }
    return new MatchResult(true, matchStart, matchEnd, subResult.groups(), adjustedSpans,
        null, 0L, 0L, 0, hitEnd);
  }

  private static MatchResult noMatch() {
    return new MatchResult(false, -1, -1, List.of(), null, 0L, 0L, 0);
  }

  private static int[] newCaptures(int groupCount) {
    int[] captures = new int[2 * groupCount];
    Arrays.fill(captures, -1);
    return captures;
  }

  /** Counts capturing groups by scanning for SaveCapture(isStart=true) instructions. */
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

  private static boolean isWordBoundary(String input, int pos, int to, boolean unicodeCase) {
    char prev = pos > 0 ? input.charAt(pos - 1) : '\0';
    char curr = pos < to ? input.charAt(pos) : '\0';
    boolean prevWord = unicodeCase ? isWordCharUnicode(prev) : isWordChar(prev);
    boolean currWord = unicodeCase ? isWordCharUnicode(curr) : isWordChar(curr);
    return prevWord != currWord;
  }

  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean isWordCharUnicode(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Returns true if the program contains any DFA-unsafe instructions. Used to detect cases where
   * the AnalysisVisitor's hint may not have propagated correctly from child nodes.
   */
  private static boolean hasDfaUnsafeInstructions(Prog prog) {
    for (Instr instr : prog.instructions) {
      if (instr instanceof BackrefCheck
          || instr instanceof Lookahead
          || instr instanceof LookaheadNeg
          || instr instanceof TransOutput
          || instr instanceof WordBoundary
          || instr instanceof BeginG
          || instr instanceof BeginLine
          || instr instanceof EndLine
          || instr instanceof EndZ
          || instr instanceof com.orbit.prog.ResetMatchStart) {
        return true;
      }
    }
    return false;
  }
}
