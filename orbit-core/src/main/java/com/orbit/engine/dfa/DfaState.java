package com.orbit.engine.dfa;

import java.util.Arrays;

/**
 * One state in the lazily-constructed DFA.
 *
 * <p>A DFA state is defined by a sorted array of NFA program counter (PC) values representing the
 * epsilon closure of a set of NFA states after consuming some input prefix. Two {@code DfaState}
 * instances with equal {@code nfaPcs} arrays are equal, regardless of their {@code captureOffsets}.
 *
 * <p>{@code captureOffsets} is {@code null} for {@code DFA_SAFE} patterns and non-null for
 * {@code ONE_PASS_SAFE} patterns. Capture offsets are run-specific; they are not part of state
 * equality and are not cached.
 *
 * <p>The static {@link #DEAD} instance represents the sink state: it has no outgoing transitions
 * and is not an accepting state.
 *
 * <p>This class is package-private and is not part of the public API.
 */
public final class DfaState {

  /** The dead (sink) state: no NFA states, not accepting, no transitions possible. */
  public static final DfaState DEAD = new DfaState(new int[0], false, null);

  /** Sorted, deduplicated array of NFA PCs in this DFA state. Never null. */
  public final int[] nfaPcs;

  /** True iff this state contains the prog's acceptPc. Pre-computed at construction time. */
  public final boolean isAccepting;

  /**
   * Capture-offset table for ONE_PASS_SAFE patterns. Null for DFA_SAFE-only patterns.
   *
   * <p>Layout: {@code captureOffsets[2*g]} = input position where {@code SaveCapture(g,
   * isStart=true)} fired. {@code captureOffsets[2*g+1]} = input position where {@code
   * SaveCapture(g, isStart=false)} fired. Value {@code -1} means not yet traversed.
   *
   * <p>Updated on each transition — not mutated in place; new arrays are created per matching
   * call. Not included in equality or hashCode.
   */
  final int[] captureOffsets; // nullable; length = 2 * groupCount

  /** Cached hash code based only on nfaPcs. */
  private final int hash;

  public DfaState(int[] nfaPcs, boolean isAccepting, int[] captureOffsets) {
    this.nfaPcs = nfaPcs;
    this.isAccepting = isAccepting;
    this.captureOffsets = captureOffsets;
    this.hash = Arrays.hashCode(nfaPcs);
  }

  /**
   * Two DFA states are equal iff their NFA PC sets are equal. Capture offsets are intentionally
   * excluded — they are run-specific and must not affect cache lookup.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DfaState other)) return false;
    return Arrays.equals(nfaPcs, other.nfaPcs);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return "DfaState{pcs=" + Arrays.toString(nfaPcs) + ", accepting=" + isAccepting + "}";
  }
}
