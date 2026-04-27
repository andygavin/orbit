package com.orbit.engine.dfa;

import com.orbit.prog.AnyByte;
import com.orbit.prog.AnyChar;
import com.orbit.prog.Accept;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.ByteMatch;
import com.orbit.prog.ByteRangeMatch;
import com.orbit.prog.CharMatch;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Fail;
import com.orbit.prog.Instr;
import com.orbit.prog.Prog;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.Split;
import com.orbit.prog.UnionSplit;
import com.orbit.prog.WordBoundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fully precomputed flat-array DFA for patterns classified as {@code ONE_PASS_SAFE}.
 *
 * <p>Built once at pattern-compile time via {@link #build(Prog, AlphabetMap)} by expanding
 * the NFA to a DFA using BFS over epsilon closures. The resulting transition table is stored
 * as a flat {@code int[]} indexed by {@code stateId * alphabetSize + classId}, enabling O(1)
 * transition lookup per input character.
 *
 * <p>Capture operations encountered during epsilon closure are encoded as
 * {@code (captureIndex << 1) | (isEnd ? 1 : 0)} and stored per state, so that capture offsets
 * can be updated at match time without re-running the NFA.
 *
 * <p>If the NFA expands to more than {@link #MAX_STATES} DFA states, {@code build} returns
 * {@code null} and the caller must fall back to {@link com.orbit.engine.engines.LazyDfaEngine}.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record PrecomputedDfa(
    int stateCount,
    int initialState,
    int[] transitionTable,
    boolean[] accepting,
    int[][] captureOps,
    AlphabetMap alphabetMap
) {

  /** Maximum DFA states before {@link #build} gives up and returns null. */
  public static final int MAX_STATES = 512;

  /** Sentinel for a dead (no-transition) entry in the transition table. */
  public static final int DEAD = -1;

  /**
   * Builds a {@code PrecomputedDfa} from the given compiled program.
   *
   * <p>Returns {@code null} if the NFA expands to more than {@link #MAX_STATES} DFA states, in
   * which case the caller should fall back to {@link com.orbit.engine.engines.LazyDfaEngine}.
   *
   * @param prog the compiled program; must not be null
   * @param alphabetMap the pre-built alphabet equivalence map; must not be null
   * @return the precomputed DFA, or null if the state limit was exceeded
   */
  public static PrecomputedDfa build(Prog prog, AlphabetMap alphabetMap) {
    Objects.requireNonNull(prog, "prog must not be null");
    Objects.requireNonNull(alphabetMap, "alphabetMap must not be null");

    int alphabetSize = alphabetMap.classCount;

    // Map from sorted int[] of NFA PCs → assigned DFA state ID.
    // We use a wrapper so equals/hashCode work correctly on the int[].
    Map<PcSet, Integer> stateIds = new HashMap<>();
    List<int[]> statePcs = new ArrayList<>();        // statePcs.get(id) = nfa PCs for that state
    List<boolean[]> stateAccepting = new ArrayList<>();
    List<int[]> stateCaptureOps = new ArrayList<>(); // encoded capture ops for each state

    // Compute initial state: epsilon closure of startPc.
    EpsilonResult initResult = epsilonClosure(prog, new int[]{prog.startPc});
    int[] initPcs = initResult.pcs();

    // If the initial closure is empty and not accepting, pattern never matches — still
    // build a minimal DFA so the engine can return no-match cleanly.
    PcSet initKey = new PcSet(initPcs);
    stateIds.put(initKey, 0);
    statePcs.add(initPcs);
    stateAccepting.add(new boolean[]{initResult.accepting()});
    stateCaptureOps.add(encodeCapOps(initResult.captureOps()));

    // BFS worklist of state IDs whose transitions have not yet been computed.
    Deque<Integer> worklist = new ArrayDeque<>();
    worklist.add(0);

    // Transition table: we grow it dynamically as states are discovered.
    // Stored flat: [sid * alphabetSize + classId] = nextSid, or DEAD.
    List<int[]> transRows = new ArrayList<>();
    transRows.add(new int[alphabetSize]);
    Arrays.fill(transRows.get(0), DEAD);

    while (!worklist.isEmpty()) {
      int sid = worklist.poll();
      int[] nfaPcs = statePcs.get(sid);

      for (int classId = 0; classId < alphabetSize; classId++) {
        // Move step: find all NFA PCs that accept some character in this class.
        // Because all chars in the same alphabet class behave identically, we advance
        // using the representative char '\0' with a direct class-based check.
        int[] successors = move(prog, nfaPcs, classId, alphabetMap);
        if (successors.length == 0) {
          // Dead transition — already DEAD sentinel in table.
          continue;
        }

        EpsilonResult result = epsilonClosure(prog, successors);
        int[] nextPcs = result.pcs();

        if (nextPcs.length == 0 && !result.accepting()) {
          // Dead — already DEAD sentinel.
          continue;
        }

        PcSet key = new PcSet(nextPcs);
        Integer nextId = stateIds.get(key);
        if (nextId == null) {
          // New state.
          if (stateIds.size() >= MAX_STATES) {
            return null; // exceeded limit — signal caller to fall back
          }
          nextId = stateIds.size();
          stateIds.put(key, nextId);
          statePcs.add(nextPcs);
          stateAccepting.add(new boolean[]{result.accepting()});
          stateCaptureOps.add(encodeCapOps(result.captureOps()));
          int[] newRow = new int[alphabetSize];
          Arrays.fill(newRow, DEAD);
          transRows.add(newRow);
          worklist.add(nextId);
        }

        transRows.get(sid)[classId] = nextId;
      }
    }

    int numStates = stateIds.size();
    int[] table = new int[numStates * alphabetSize];
    boolean[] accepting = new boolean[numStates];
    int[][] captureOpsArr = new int[numStates][];

    for (int sid = 0; sid < numStates; sid++) {
      System.arraycopy(transRows.get(sid), 0, table, sid * alphabetSize, alphabetSize);
      accepting[sid] = stateAccepting.get(sid)[0];
      captureOpsArr[sid] = stateCaptureOps.get(sid);
    }

    return new PrecomputedDfa(numStates, 0, table, accepting, captureOpsArr, alphabetMap);
  }

  // ---------------------------------------------------------------------------
  // Package-private static helpers (reusable by NfaUtil or tests)
  // ---------------------------------------------------------------------------

  /**
   * Computes the epsilon closure of the given seed NFA PCs. Does not need positional context
   * because {@code BeginText}/{@code EndText} anchors are not followed during the precompute
   * phase — the DFA is built without positional anchor resolution (those transitions remain
   * dead in the precomputed table and are handled at match time by the precompute DFA treating
   * them as unconditional epsilon jumps when the position matches).
   *
   * <p>For the precompute phase, {@code BeginText} and {@code EndText} are treated as
   * unconditional epsilon jumps so that the DFA reflects the NFA structure independent of
   * position.
   *
   * @param prog the compiled program
   * @param seedPcs the NFA PCs to expand from
   * @return the epsilon closure result containing the NFA PCs, accepting flag, and capture ops
   */
  static EpsilonResult epsilonClosure(Prog prog, int[] seedPcs) {
    int instrCount = prog.getInstructionCount();
    boolean[] visited = new boolean[instrCount];
    List<Integer> resultPcs = new ArrayList<>();
    List<int[]> captureOps = new ArrayList<>();
    boolean isAccepting = false;

    Deque<Integer> worklist = new ArrayDeque<>();
    for (int pc : seedPcs) {
      if (pc >= 0 && pc < instrCount && !visited[pc]) {
        worklist.push(pc);
      }
    }

    while (!worklist.isEmpty()) {
      int pc = worklist.pop();
      if (visited[pc]) {
        continue;
      }
      visited[pc] = true;

      Instr instr = prog.getInstruction(pc);

      switch (instr) {
        case Accept ignored -> {
          isAccepting = true;
          // Accept is a sink; do not follow.
        }
        case Fail ignored -> {
          // Dead end; discard this branch.
        }
        case EpsilonJump ej -> {
          if (!visited[ej.next()]) {
            worklist.push(ej.next());
          }
        }
        case Split s -> {
          if (!visited[s.next1()]) {
            worklist.push(s.next1());
          }
          if (!visited[s.next2()]) {
            worklist.push(s.next2());
          }
        }
        case UnionSplit us -> {
          if (!visited[us.next1()]) {
            worklist.push(us.next1());
          }
          if (!visited[us.next2()]) {
            worklist.push(us.next2());
          }
        }
        case SaveCapture sc -> {
          // Collect capture op: (groupIndex << 1) | (isEnd ? 1 : 0)
          captureOps.add(new int[]{(sc.groupIndex() << 1) | (sc.isStart() ? 0 : 1)});
          if (!visited[sc.next()]) {
            worklist.push(sc.next());
          }
        }
        case BeginText bt -> {
          // Treat as unconditional epsilon during precompute (position-independent DFA build).
          if (!visited[bt.next()]) {
            worklist.push(bt.next());
          }
        }
        case EndText et -> {
          // Treat as unconditional epsilon during precompute.
          if (!visited[et.next()]) {
            worklist.push(et.next());
          }
        }
        case BeginLine bl -> {
          if (!visited[bl.next()]) {
            worklist.push(bl.next());
          }
        }
        case EndLine el -> {
          if (!visited[el.next()]) {
            worklist.push(el.next());
          }
        }
        case WordBoundary wb -> {
          // WordBoundary is position-dependent and cannot be encoded in a precomputed
          // transition table. Patterns containing \b or \B are excluded from precomputed
          // DFA construction by hasDfaUnsafeInstructions() in LazyDfaEngine. This arm
          // is therefore dead under normal execution. If reached, treat as no-op rather
          // than silently corrupting state.
        }
        default -> {
          // Consuming instruction: leaf in the epsilon closure.
          resultPcs.add(pc);
        }
      }
    }

    int[] pcs = resultPcs.stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
    // Flatten capture ops
    int[] flatOps = captureOps.stream()
        .flatMapToInt(Arrays::stream)
        .toArray();

    return new EpsilonResult(pcs, isAccepting, flatOps);
  }

  /**
   * Computes the set of NFA PCs reachable by consuming a character in the given alphabet class.
   *
   * @param prog the compiled program
   * @param nfaPcs the current set of NFA PCs
   * @param classId the alphabet class ID to advance on
   * @param alphabetMap the alphabet equivalence map
   * @return the successor NFA PCs (before epsilon closure), possibly empty
   */
  static int[] move(Prog prog, int[] nfaPcs, int classId, AlphabetMap alphabetMap) {
    // To check if an instruction accepts the given class, we test whether a character
    // representative of that class is accepted. We scan the alphabet map to find such a char.
    // Optimisation: find the first char in [0..65535] that maps to classId.
    // Cache a representative per class.
    char rep = findRepresentative(alphabetMap, classId);

    int[] movedPcs = new int[nfaPcs.length];
    int movedCount = 0;

    for (int pc : nfaPcs) {
      Instr instr = prog.getInstruction(pc);
      switch (instr) {
        case CharMatch cm -> {
          if (rep >= cm.lo() && rep <= cm.hi()) {
            movedPcs[movedCount++] = cm.next();
          }
        }
        case AnyChar ac -> movedPcs[movedCount++] = ac.next();
        case ByteMatch bm -> {
          char lo = (char) Byte.toUnsignedInt(bm.value());
          if (rep == lo) {
            movedPcs[movedCount++] = bm.next();
          }
        }
        case ByteRangeMatch brm -> {
          char lo = (char) Byte.toUnsignedInt(brm.lo());
          char hi = (char) Byte.toUnsignedInt(brm.hi());
          if (rep >= lo && rep <= hi) {
            movedPcs[movedCount++] = brm.next();
          }
        }
        case AnyByte ab -> movedPcs[movedCount++] = ab.next();
        default -> {
          // Non-consuming instructions do not accept characters.
        }
      }
    }

    return Arrays.copyOf(movedPcs, movedCount);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Finds a representative character for the given alphabet class. Scans the character space
   * in order and returns the first character that maps to {@code classId}.
   */
  private static char findRepresentative(AlphabetMap alphabetMap, int classId) {
    for (int c = 0; c < 65536; c++) {
      if (alphabetMap.classOf((char) c) == classId) {
        return (char) c;
      }
    }
    return '\0'; // should never happen for a valid classId
  }

  /**
   * Encodes the raw list of capture ops (already encoded as int values) into an int[] for
   * storage per DFA state. Returns null if the list is empty (no captures in this state).
   */
  private static int[] encodeCapOps(int[] ops) {
    if (ops == null || ops.length == 0) {
      return null;
    }
    return ops;
  }

  // ---------------------------------------------------------------------------
  // Inner types
  // ---------------------------------------------------------------------------

  /**
   * Result of an epsilon-closure computation during DFA construction.
   *
   * @param pcs the sorted, distinct NFA PCs that are leaves (consuming instructions or Accept)
   * @param accepting true if the closure contains the Accept instruction
   * @param captureOps encoded capture operations encountered; each entry is
   *     {@code (groupIndex << 1) | (isEnd ? 1 : 0)}
   */
  record EpsilonResult(int[] pcs, boolean accepting, int[] captureOps) {}

  /**
   * Wrapper around {@code int[]} that provides value-equality semantics for use as a map key.
   */
  private static final class PcSet {

    private final int[] pcs;
    private final int hash;

    PcSet(int[] pcs) {
      this.pcs = pcs;
      this.hash = Arrays.hashCode(pcs);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof PcSet other)) {
        return false;
      }
      return Arrays.equals(pcs, other.pcs);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
