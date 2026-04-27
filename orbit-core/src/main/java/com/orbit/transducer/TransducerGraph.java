package com.orbit.transducer;

import com.orbit.prefilter.NoopPrefilter;
import com.orbit.prog.Accept;
import com.orbit.prog.CharMatch;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Fail;
import com.orbit.prog.Instr;
import com.orbit.prog.Metadata;
import com.orbit.prog.Prog;
import com.orbit.prog.ProgOptimiser;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.Split;
import com.orbit.prog.TransOutput;
import com.orbit.prog.UnionSplit;
import com.orbit.util.EngineHint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Intermediate graph representation of a finite-state transducer.
 *
 * <p>A {@code TransducerGraph} is a directed graph where each arc carries an input label
 * ({@code ilabel}) and an output label ({@code olabel}) as raw Unicode BMP code points, or
 * {@link Arc#EPS} (0) for the absence of a label. All algebraic operations ({@link #rmEpsilon},
 * {@link #invert}, {@link #compose}, {@link #toProg}) are pure: they return new graphs and never
 * modify the receiver or any argument.
 *
 * <p>This class is package-private. It is constructed by {@link #fromProg} and consumed by
 * {@link #toProg}; the lifetime of every instance is bounded by a single {@code compose} or
 * {@code invert} call on {@link com.orbit.api.Transducer}.
 *
 * <p>Instances are <em>not</em> thread-safe. Do not share a graph across threads.
 */
public final class TransducerGraph {

  public final int startState;
  public final int numStates;
  /** Outgoing arcs per state; {@code outArcs.get(s)} is the mutable arc list for state {@code s}. */
  public final List<List<Arc>> outArcs;
  /** {@code isFinal[s]} is true when state {@code s} is an accepting state. */
  public final boolean[] isFinal;

  TransducerGraph(int startState, int numStates, List<List<Arc>> outArcs, boolean[] isFinal) {
    this.startState = startState;
    this.numStates = numStates;
    this.outArcs = outArcs;
    this.isFinal = isFinal;
  }

  // ---------------------------------------------------------------------------
  // Internal mutation helpers (used only during construction)
  // ---------------------------------------------------------------------------

  /** Allocates a new state ID, expanding {@code outArcs} and {@code isFinal} to accommodate. */
  private static int addState(List<List<Arc>> arcs, boolean[] finalFlags, int[] counterHolder) {
    int id = counterHolder[0]++;
    while (arcs.size() <= id) {
      arcs.add(new ArrayList<>());
    }
    // isFinal is grown separately via growFinal
    return id;
  }

  // ---------------------------------------------------------------------------
  // Factory
  // ---------------------------------------------------------------------------

  /**
   * Converts a compiled {@link Prog} to a {@code TransducerGraph}.
   *
   * <p>The graph is a direct structural translation: one state per Prog PC, plus additional
   * intermediate states for multi-character {@link TransOutput} deltas. No epsilon-closure
   * collapsing occurs here; call {@link #rmEpsilon} afterwards.
   *
   * @param prog the compiled program; must be graph-eligible
   * @return a new {@code TransducerGraph}; never null
   */
  public static TransducerGraph fromProg(Prog prog) {
    int baseCount = prog.instructions.length;
    // Allocate one ArrayList per base state; fresh states are appended as needed.
    List<List<Arc>> arcs = new ArrayList<>(baseCount);
    for (int i = 0; i < baseCount; i++) {
      arcs.add(new ArrayList<>());
    }
    boolean[] finalFlags = new boolean[baseCount];

    // nextFreshState tracks the next ID to allocate for intermediate states created
    // during multi-character TransOutput chains.
    int nextFreshState = baseCount;

    for (int s = 0; s < baseCount; s++) {
      Instr instr = prog.instructions[s];
      switch (instr) {
        case CharMatch cm -> {
          for (char c = cm.lo(); c <= cm.hi(); c++) {
            ensureState(arcs, s);
            arcs.get(s).add(new Arc(c, Arc.EPS, cm.next()));
          }
        }
        case TransOutput to -> {
          String delta = to.delta();
          int next = to.next();
          ensureState(arcs, s);
          if (delta.isEmpty()) {
            arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, next));
          } else if (delta.length() == 1) {
            arcs.get(s).add(new Arc(Arc.EPS, delta.charAt(0), next));
          } else {
            // Chain of insertion arcs through fresh intermediate states.
            int prev = s;
            for (int i = 0; i < delta.length() - 1; i++) {
              int fresh = nextFreshState++;
              ensureState(arcs, fresh);
              arcs.get(prev).add(new Arc(Arc.EPS, delta.charAt(i), fresh));
              prev = fresh;
            }
            arcs.get(prev).add(new Arc(Arc.EPS, delta.charAt(delta.length() - 1), next));
          }
        }
        case Split sp -> {
          ensureState(arcs, s);
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, sp.next1()));
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, sp.next2()));
        }
        case UnionSplit us -> {
          ensureState(arcs, s);
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, us.next1()));
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, us.next2()));
        }
        case EpsilonJump ej -> {
          ensureState(arcs, s);
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, ej.next()));
        }
        case SaveCapture sc -> {
          ensureState(arcs, s);
          arcs.get(s).add(new Arc(Arc.EPS, Arc.EPS, sc.next()));
        }
        case Accept ignored -> {
          // Ensure the state list entry exists even for Accept.
          ensureState(arcs, s);
          if (s < finalFlags.length) {
            finalFlags[s] = true;
          }
        }
        case Fail ignored -> {
          ensureState(arcs, s);
          // Dead state: no arcs, not final.
        }
        default -> {
          // Disqualifying instruction — should not reach here if eligibility check passed.
          ensureState(arcs, s);
        }
      }
    }

    // Grow isFinal to cover all states including any fresh intermediate ones.
    int totalStates = nextFreshState;
    boolean[] fullFinal = Arrays.copyOf(finalFlags, totalStates);
    // Ensure arcs list covers all states.
    while (arcs.size() < totalStates) {
      arcs.add(new ArrayList<>());
    }

    return new TransducerGraph(prog.startPc, totalStates, arcs, fullFinal);
  }

  /** Expands the arc list so that state {@code id} has a valid entry. */
  private static void ensureState(List<List<Arc>> arcs, int id) {
    while (arcs.size() <= id) {
      arcs.add(new ArrayList<>());
    }
  }

  // ---------------------------------------------------------------------------
  // rmEpsilon
  // ---------------------------------------------------------------------------

  /**
   * Removes all true-epsilon arcs (where {@link Arc#isTrueEpsilon()} is true) and returns a new
   * graph that is semantically equivalent but epsilon-free for true epsilons.
   *
   * <p>Insertion arcs ({@code ilabel=EPS, olabel!=EPS}) are preserved. The algorithm computes the
   * epsilon-closure of each state and propagates finality and non-epsilon arcs through closures.
   * Unreachable states are pruned and state IDs are compacted.
   *
   * @return a new epsilon-reduced {@code TransducerGraph}; never null
   */
  public TransducerGraph rmEpsilon() {
    // Step 1: compute true-epsilon closure for each state.
    @SuppressWarnings("unchecked")
    List<Integer>[] closure = new List[numStates];
    for (int s = 0; s < numStates; s++) {
      closure[s] = computeEpsilonClosure(s);
    }

    // Step 2: build new graph.
    List<List<Arc>> newArcs = new ArrayList<>(numStates);
    for (int i = 0; i < numStates; i++) {
      newArcs.add(new ArrayList<>());
    }
    boolean[] newFinal = new boolean[numStates];

    for (int s = 0; s < numStates; s++) {
      for (int t : closure[s]) {
        if (isFinal[t]) {
          newFinal[s] = true;
        }
        for (Arc a : outArcs.get(t)) {
          if (!a.isTrueEpsilon()) {
            newArcs.get(s).add(a);
          }
        }
      }
    }

    // Step 3: prune unreachable states via BFS and re-index.
    return pruneAndReindex(startState, numStates, newArcs, newFinal);
  }

  /**
   * Computes the set of states reachable from {@code start} following only true-epsilon arcs
   * (BFS). The start state itself is always included.
   */
  private List<Integer> computeEpsilonClosure(int start) {
    List<Integer> result = new ArrayList<>();
    boolean[] visited = new boolean[numStates];
    Queue<Integer> queue = new ArrayDeque<>();
    queue.add(start);
    visited[start] = true;
    while (!queue.isEmpty()) {
      int s = queue.poll();
      result.add(s);
      for (Arc a : outArcs.get(s)) {
        if (a.isTrueEpsilon() && !visited[a.nextstate()]) {
          visited[a.nextstate()] = true;
          queue.add(a.nextstate());
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // normalizeArcs
  // ---------------------------------------------------------------------------

  /**
   * Merges consuming-then-insertion arc pairs into single labelled arcs.
   *
   * <p>After {@link #rmEpsilon}, arcs that carry both input and output are split into two
   * sequential arcs: a consuming arc {@code (char, EPS, mid)} followed by a single insertion arc
   * {@code (EPS, olabel, next)} from the intermediate state {@code mid}. The standard composition
   * algorithm requires that both labels reside on a single arc; this method performs that merge.
   *
   * <p>Merge condition: for each consuming arc {@code (ilabel, EPS, mid)} where state {@code mid}
   * has exactly one outgoing arc and that arc is an insertion arc {@code (EPS, olabel, next)},
   * replace the consuming arc with {@code (ilabel, olabel, next)}. State {@code mid} becomes
   * unreachable and is removed by the pruning step.
   *
   * <p>Arcs where the target state has zero arcs, more than one arc, or a non-insertion arc are
   * left unchanged. Pure insertion arcs in the source state are always left unchanged.
   *
   * @return a new {@code TransducerGraph} with merged arcs and unreachable states pruned; never null
   */
  TransducerGraph normalizeArcs() {
    List<List<Arc>> newArcs = new ArrayList<>(numStates);
    for (int s = 0; s < numStates; s++) {
      newArcs.add(new ArrayList<>());
    }
    boolean[] newFinal = Arrays.copyOf(isFinal, numStates);

    for (int s = 0; s < numStates; s++) {
      for (Arc a : outArcs.get(s)) {
        if (a.isConsuming() && a.olabel() == Arc.EPS) {
          int mid = a.nextstate();
          if (mid < numStates) {
            List<Arc> midArcs = outArcs.get(mid);
            if (midArcs.size() == 1 && midArcs.get(0).isInsertion()) {
              Arc ins = midArcs.get(0);
              // Merge: (ilabel, EPS, mid) + (EPS, olabel, next) -> (ilabel, olabel, next)
              newArcs.get(s).add(new Arc(a.ilabel(), ins.olabel(), ins.nextstate()));
              continue;
            }
          }
        }
        // No merge: keep the arc as-is.
        newArcs.get(s).add(a);
      }
    }

    return pruneAndReindex(startState, numStates, newArcs, newFinal);
  }

  // ---------------------------------------------------------------------------
  // invert
  // ---------------------------------------------------------------------------

  /**
   * Returns a new graph with {@code ilabel} and {@code olabel} swapped on every arc.
   *
   * <p>The operation is O(V+E). The {@code startState} and {@code isFinal} arrays are identical
   * to those of the receiver.
   *
   * @return the inverted graph; never null
   */
  public TransducerGraph invert() {
    List<List<Arc>> inverted = new ArrayList<>(numStates);
    for (int s = 0; s < numStates; s++) {
      List<Arc> newArcs = new ArrayList<>(outArcs.get(s).size());
      for (Arc a : outArcs.get(s)) {
        newArcs.add(new Arc(a.olabel(), a.ilabel(), a.nextstate()));
      }
      inverted.add(newArcs);
    }
    return new TransducerGraph(startState, numStates, inverted, isFinal.clone());
  }

  // ---------------------------------------------------------------------------
  // compose
  // ---------------------------------------------------------------------------

  /**
   * Computes the functional composition of this transducer with {@code other}.
   *
   * <p>For all strings {@code x} and {@code z}, the composed transducer maps {@code x} to
   * {@code z} if there exists a {@code y} such that {@code this} maps {@code x} to {@code y}
   * and {@code other} maps {@code y} to {@code z}.
   *
   * <p>Both graphs are epsilon-reduced internally before composition begins. The result is also
   * epsilon-reduced before being returned.
   *
   * @param other the second transducer in the composition; must not be null
   * @return the composed graph; never null
   */
  public TransducerGraph compose(TransducerGraph other) {
    // Both graphs must be epsilon-free and arc-normalized for the composition algorithm.
    // normalizeArcs() merges consuming-then-insertion arc pairs into single labelled arcs
    // so that the standard composition case matching (a1.olabel == a2.ilabel) works correctly.
    TransducerGraph g1 = this.rmEpsilon().normalizeArcs();
    TransducerGraph g2 = other.rmEpsilon().normalizeArcs();

    // State pairs are encoded as: s1 * g2.numStates + s2.
    // We use a HashMap<Long, Integer> keyed by encoded pair -> composed state ID.
    Map<Long, Integer> pairToId = new HashMap<>();
    List<List<Arc>> composedArcs = new ArrayList<>();
    List<Boolean> finalList = new ArrayList<>();
    int[] counter = {0};

    Queue<long[]> queue = new ArrayDeque<>();

    // Allocate the start state.
    int composedStart = getOrCreateComposedState(
        pairToId, composedArcs, finalList, counter,
        g1.startState, g2.startState, g1, g2, queue);

    while (!queue.isEmpty()) {
      long[] pair = queue.poll();
      int s1 = (int) pair[0];
      int s2 = (int) pair[1];
      int id = pairToId.get(encodeKey(s1, s2, g2.numStates));

      List<Arc> arcs1 = g1.outArcs.get(s1);
      List<Arc> arcs2 = g2.outArcs.get(s2);

      // Cases 1 and 2: iterate all pairs (a1, a2).
      for (Arc a1 : arcs1) {
        for (Arc a2 : arcs2) {
          // Case 1: both consuming, output of a1 matches input of a2.
          if (a1.isConsuming() && a2.isConsuming() && a1.olabel() == a2.ilabel()) {
            int nextId = getOrCreateComposedState(
                pairToId, composedArcs, finalList, counter,
                a1.nextstate(), a2.nextstate(), g1, g2, queue);
            composedArcs.get(id).add(new Arc(a1.ilabel(), a2.olabel(), nextId));
          }
          // Case 2: a1 is insertion, a2 is consuming, a1's output matches a2's input.
          else if (a1.isInsertion() && a2.isConsuming() && a1.olabel() == a2.ilabel()) {
            int nextId = getOrCreateComposedState(
                pairToId, composedArcs, finalList, counter,
                a1.nextstate(), a2.nextstate(), g1, g2, queue);
            composedArcs.get(id).add(new Arc(Arc.EPS, a2.olabel(), nextId));
          }
        }
        // Case 4: FST1 consuming arc with EPS olabel — g1 advances consuming input,
        // g2 stays. This handles multi-character transducers where intermediate consuming
        // steps produce no output (the output is deferred to a later arc via an insertion chain).
        if (a1.isConsuming() && a1.olabel() == Arc.EPS) {
          int nextId = getOrCreateComposedState(
              pairToId, composedArcs, finalList, counter,
              a1.nextstate(), s2, g1, g2, queue);
          composedArcs.get(id).add(new Arc(a1.ilabel(), Arc.EPS, nextId));
        }
      }

      // Case 3: FST2 insertion arcs — FST1 stays at s1, FST2 advances independently.
      for (Arc a2 : arcs2) {
        if (a2.isInsertion()) {
          int nextId = getOrCreateComposedState(
              pairToId, composedArcs, finalList, counter,
              s1, a2.nextstate(), g1, g2, queue);
          composedArcs.get(id).add(new Arc(Arc.EPS, a2.olabel(), nextId));
        }
      }
    }

    int totalComposed = counter[0];
    boolean[] composedFinal = new boolean[totalComposed];
    for (int i = 0; i < totalComposed; i++) {
      composedFinal[i] = finalList.get(i);
    }

    // Return the epsilon-reduced composed graph.
    return new TransducerGraph(composedStart, totalComposed, composedArcs, composedFinal)
        .rmEpsilon();
  }

  private static long encodeKey(int s1, int s2, int numStates2) {
    return s1 * (long) numStates2 + s2;
  }

  private static int getOrCreateComposedState(
      Map<Long, Integer> pairToId,
      List<List<Arc>> composedArcs,
      List<Boolean> finalList,
      int[] counter,
      int s1, int s2,
      TransducerGraph g1, TransducerGraph g2,
      Queue<long[]> queue) {
    long key = encodeKey(s1, s2, g2.numStates);
    Integer existing = pairToId.get(key);
    if (existing != null) {
      return existing;
    }
    int newId = counter[0]++;
    pairToId.put(key, newId);
    composedArcs.add(new ArrayList<>());
    boolean fin = (s1 < g1.numStates && g1.isFinal[s1])
        && (s2 < g2.numStates && g2.isFinal[s2]);
    finalList.add(fin);
    queue.add(new long[]{s1, s2});
    return newId;
  }

  // ---------------------------------------------------------------------------
  // toProg
  // ---------------------------------------------------------------------------

  /**
   * Compiles this graph back to a {@link Prog} suitable for execution by the {@code PikeVmEngine}.
   *
   * <p>The graph must be epsilon-free (contain no arc where {@link Arc#isTrueEpsilon()} is
   * true); if any such arc is found, {@link IllegalStateException} is thrown.
   *
   * <p>The returned {@code Prog} is post-processed by {@link ProgOptimiser#foldEpsilonChains}.
   *
   * @return the compiled program; never null
   * @throws IllegalStateException if any true-epsilon arc remains in this graph
   */
  public Prog toProg() {
    // Validate: no true-epsilon arcs allowed.
    for (int s = 0; s < numStates; s++) {
      for (Arc a : outArcs.get(s)) {
        if (a.isTrueEpsilon()) {
          throw new IllegalStateException(
              "toProg called on graph with true-epsilon arcs; call rmEpsilon first");
        }
      }
    }

    // Pass 1: compute instruction size for each state and prefix sums (state_pc).
    int[] stateSize = new int[numStates];
    for (int s = 0; s < numStates; s++) {
      stateSize[s] = computeStateSize(s);
    }

    int acceptPc = 0;
    for (int s = 0; s < numStates; s++) {
      acceptPc += stateSize[s];
    }
    int totalInstructions = acceptPc + 1; // +1 for the shared Accept

    // Prefix sums: state_pc[s] = sum of stateSize[0..s-1].
    int[] statePc = new int[numStates];
    statePc[0] = 0;
    for (int s = 1; s < numStates; s++) {
      statePc[s] = statePc[s - 1] + stateSize[s - 1];
    }

    // Pass 2: emit instructions.
    Instr[] instructions = new Instr[totalInstructions];

    for (int s = 0; s < numStates; s++) {
      int cursor = statePc[s];
      List<Arc> arcs = outArcs.get(s);
      int k = arcs.size();

      // --- Preamble ---
      if (isFinal[s]) {
        if (k == 0) {
          instructions[cursor] = new EpsilonJump(acceptPc);
          continue;
        } else {
          // Layout: [Split] [EpsilonJump->Accept] [k-1 Split fan-out] [arc blocks...]
          // Split targets: left = cursor+1 (EpsilonJump), right = cursor+2 (fan-out block).
          int splitPc = cursor;
          instructions[splitPc] = new Split(splitPc + 1, splitPc + 2);
          cursor++;
          instructions[cursor++] = new EpsilonJump(acceptPc);
          // cursor is now at start of the split fan-out block
        }
      } else if (k == 0) {
        instructions[cursor] = new Fail();
        continue;
      }

      // --- Compute arc start PCs ---
      int[] arcPc = computeArcPcs(arcs, cursor);

      // --- Emit k-1 Split instructions (left-spine chain) ---
      // Split[i] is at cursor+i; its right target is the next split cursor+i+1 (if exists)
      // or the last arc arcPc[k-1] (for the final split).
      for (int i = 0; i < k - 1; i++) {
        int nextSplitOrArc = (i + 1 < k - 1) ? (cursor + i + 1) : arcPc[k - 1];
        instructions[cursor + i] = new Split(arcPc[i], nextSplitOrArc);
      }
      cursor += (k - 1);

      // --- Emit arc instruction blocks ---
      for (int i = 0; i < k; i++) {
        Arc arc = arcs.get(i);
        // cursor must equal arcPc[i] here.
        if (arc.isConsuming()) {
          instructions[cursor++] = new CharMatch((char) arc.ilabel(), (char) arc.ilabel(), cursor);
          if (arc.olabel() != Arc.EPS) {
            instructions[cursor++] = new TransOutput(
                String.valueOf((char) arc.olabel()), cursor);
          }
          instructions[cursor++] = new EpsilonJump(statePc[arc.nextstate()]);
        } else if (arc.isInsertion()) {
          instructions[cursor++] = new TransOutput(
              String.valueOf((char) arc.olabel()), cursor);
          instructions[cursor++] = new EpsilonJump(statePc[arc.nextstate()]);
        } else {
          throw new IllegalStateException(
              "Unexpected true-epsilon arc in toProg emission loop");
        }
      }
    }

    // Append the shared Accept instruction.
    instructions[acceptPc] = new Accept();

    // Build Metadata — graph-derived transducers are always PIKEVM_ONLY transducers.
    // outputPrecedesInput = true: graph-derived progs may emit TransOutput before CharMatch
    // (inverted structure), so the output buffer must not be reset between consuming steps.
    Metadata metadata = new Metadata(
        EngineHint.PIKEVM_ONLY,
        NoopPrefilter.INSTANCE,
        0,
        0,
        false,
        true,
        Map.of(),
        false,
        false,
        false,
        true);  // outputPrecedesInput = true for all graph-derived progs

    int resolvedStart = statePc[startState];

    // Final step: fold epsilon chains.
    ProgOptimiser.FoldResult folded = ProgOptimiser.foldEpsilonChains(
        instructions, resolvedStart, acceptPc);

    return new Prog(folded.instructions(), metadata, folded.startPc(), folded.acceptPc());
  }

  /**
   * Computes the total instruction count for a single state.
   *
   * <p>The layout for state {@code s} with {@code k} outgoing arcs is:
   * <ul>
   *   <li>Preamble: 0, 1, or 2 instructions depending on finality and arc count.</li>
   *   <li>Split fan-out: {@code k - 1} Split instructions.</li>
   *   <li>Arc blocks: sum of per-arc costs.</li>
   * </ul>
   */
  private int computeStateSize(int s) {
    List<Arc> arcs = outArcs.get(s);
    int k = arcs.size();

    int preamble;
    if (isFinal[s] && k == 0) {
      preamble = 1; // EpsilonJump -> Accept
    } else if (isFinal[s]) {
      preamble = 2; // Split + EpsilonJump -> Accept
    } else if (k == 0) {
      preamble = 1; // Fail
    } else {
      preamble = 0;
    }

    int splitCount = k > 1 ? k - 1 : 0;

    int arcCosts = 0;
    for (Arc a : arcs) {
      arcCosts += arcCost(a);
    }

    return preamble + splitCount + arcCosts;
  }

  private static int arcCost(Arc arc) {
    if (arc.isConsuming() && arc.olabel() != Arc.EPS) {
      return 3; // CharMatch + TransOutput + EpsilonJump
    } else if (arc.isConsuming()) {
      return 2; // CharMatch + EpsilonJump
    } else if (arc.isInsertion()) {
      return 2; // TransOutput + EpsilonJump
    } else {
      throw new IllegalStateException(
          "True-epsilon arc encountered in arcCost; call rmEpsilon before toProg");
    }
  }

  /**
   * Computes the start PC of each arc's instruction block, given that the split fan-out block
   * starts at {@code splitBlockStart}.
   *
   * <p>Arc 0 starts immediately after the (k-1) Split instructions. Each subsequent arc starts
   * at the previous arc's start plus the cost of the previous arc's instructions.
   */
  private int[] computeArcPcs(List<Arc> arcs, int splitBlockStart) {
    int k = arcs.size();
    int[] arcPc = new int[k];
    arcPc[0] = splitBlockStart + (k - 1); // arcs start after all k-1 Split instructions
    for (int i = 1; i < k; i++) {
      arcPc[i] = arcPc[i - 1] + arcCost(arcs.get(i - 1));
    }
    return arcPc;
  }

  // ---------------------------------------------------------------------------
  // Reachability pruning and state re-indexing
  // ---------------------------------------------------------------------------

  /**
   * Prunes unreachable states from a graph via BFS from {@code startState}, then compacts state
   * IDs to a dense range starting from 0.
   *
   * @param startState the entry state before pruning
   * @param numStates  the total number of states before pruning
   * @param arcs       outgoing arcs indexed by original state ID
   * @param finalFlags finality flags indexed by original state ID
   * @return a new {@code TransducerGraph} with only reachable states and dense IDs
   */
  private static TransducerGraph pruneAndReindex(
      int startState, int numStates, List<List<Arc>> arcs, boolean[] finalFlags) {
    boolean[] reachable = new boolean[numStates];
    Queue<Integer> queue = new ArrayDeque<>();
    if (startState < numStates) {
      reachable[startState] = true;
      queue.add(startState);
    }
    while (!queue.isEmpty()) {
      int s = queue.poll();
      for (Arc a : arcs.get(s)) {
        int ns = a.nextstate();
        if (ns < numStates && !reachable[ns]) {
          reachable[ns] = true;
          queue.add(ns);
        }
      }
    }

    // Build a mapping from old state ID to new (compacted) ID.
    int[] oldToNew = new int[numStates];
    Arrays.fill(oldToNew, -1);
    int newCount = 0;
    for (int s = 0; s < numStates; s++) {
      if (reachable[s]) {
        oldToNew[s] = newCount++;
      }
    }

    // Build compacted structures.
    List<List<Arc>> newArcs = new ArrayList<>(newCount);
    for (int i = 0; i < newCount; i++) {
      newArcs.add(new ArrayList<>());
    }
    boolean[] newFinal = new boolean[newCount];

    for (int s = 0; s < numStates; s++) {
      if (!reachable[s]) {
        continue;
      }
      int ns = oldToNew[s];
      newFinal[ns] = finalFlags[s];
      for (Arc a : arcs.get(s)) {
        int nextOld = a.nextstate();
        if (nextOld >= 0 && nextOld < numStates && reachable[nextOld]) {
          newArcs.get(ns).add(new Arc(a.ilabel(), a.olabel(), oldToNew[nextOld]));
        }
      }
    }

    int newStart = (startState < numStates && reachable[startState])
        ? oldToNew[startState] : 0;
    return new TransducerGraph(newStart, newCount, newArcs, newFinal);
  }
}
