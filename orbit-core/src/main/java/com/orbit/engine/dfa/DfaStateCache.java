package com.orbit.engine.dfa;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-{@link com.orbit.prog.Prog} cache for {@link DfaState} objects and their outgoing
 * transitions.
 *
 * <p>One {@code DfaStateCache} is created per {@code Prog} instance. It serves two purposes:
 *
 * <ol>
 *   <li><em>State interning:</em> deduplicates {@code DfaState} objects with equal NFA-PC sets so
 *       that identity comparison suffices during matching.
 *   <li><em>Transition caching:</em> maps (state, alphabet class ID) pairs to the next {@code
 *       DfaState}, avoiding repeated epsilon-closure computation.
 * </ol>
 *
 * <p>The cache is bounded at {@link #MAX_STATES} (1,024) entries. When the entry count reaches
 * this limit, all states and transitions are discarded and {@link #isSaturated()} returns {@code
 * true} for the current call. This prompts {@link com.orbit.engine.engines.LazyDfaEngine} to fall
 * back to {@link com.orbit.engine.engines.PikeVmEngine} for the remainder of that {@code execute()}
 * invocation.
 *
 * <p>All operations use {@code ConcurrentHashMap} and {@code AtomicInteger}; this class is safe
 * for concurrent use by multiple matcher threads.
 *
 * <p>This class is package-private and is not part of the public API.
 */
public final class DfaStateCache {

  /** Maximum number of DFA states before a full cache flush is triggered. */
  static final int MAX_STATES = 1024;

  /**
   * Interns DfaState objects by nfaPcs equality. The canonical instance for a given PC set is the
   * value; the key is used only for lookup.
   */
  private final ConcurrentHashMap<DfaState, DfaState> stateIntern = new ConcurrentHashMap<>();

  /**
   * Maps each canonical DfaState to its outgoing transition table. The array is indexed by
   * alphabet class ID; each slot holds the next canonical DfaState (or null if not yet computed).
   */
  private final ConcurrentHashMap<DfaState, DfaState[]> transitions = new ConcurrentHashMap<>();

  private final AtomicInteger stateCount = new AtomicInteger(0);
  private final AtomicBoolean saturated = new AtomicBoolean(false);

  /**
   * Returns the canonical {@code DfaState} for the given candidate, interning it if absent.
   *
   * <p>Returns {@code null} if the cache has reached {@link #MAX_STATES}. When null is returned,
   * the cache is also flushed so that future calls start fresh.
   *
   * @param candidate the DFA state to intern; must not be null
   * @return the canonical instance, or null if the cache is saturated
   */
  public DfaState intern(DfaState candidate) {
    // DEAD is a singleton; never intern it.
    if (candidate == DfaState.DEAD || candidate.nfaPcs.length == 0) {
      return DfaState.DEAD;
    }

    DfaState existing = stateIntern.get(candidate);
    if (existing != null) {
      return existing;
    }

    // Attempt to add a new state.
    if (stateCount.get() >= MAX_STATES) {
      flush();
      saturated.set(true);
      return null;
    }

    DfaState canonical = stateIntern.computeIfAbsent(candidate, k -> {
      stateCount.incrementAndGet();
      return k;
    });

    // Re-check after insertion in case concurrent threads pushed us over the limit.
    if (stateCount.get() >= MAX_STATES) {
      flush();
      saturated.set(true);
      return null;
    }

    return canonical;
  }

  /**
   * Returns the cached next state for the given (state, class) pair, or {@code null} if not yet
   * computed.
   *
   * @param from the source DFA state (must be a canonical interned state)
   * @param alphabetClassId the alphabet equivalence class ID of the consumed character
   * @return the cached next state, or null
   */
  public DfaState getCachedTransition(DfaState from, int alphabetClassId) {
    DfaState[] table = transitions.get(from);
    if (table == null || alphabetClassId >= table.length) {
      return null;
    }
    return table[alphabetClassId];
  }

  /**
   * Stores a transition from the given state on the given alphabet class.
   *
   * <p>If {@code from} is not in the cache, does nothing.
   *
   * @param from the source DFA state
   * @param alphabetClassId the alphabet equivalence class ID
   * @param to the destination DFA state
   * @param classCount total number of alphabet classes (used to size the transition table)
   */
  public void putTransition(DfaState from, int alphabetClassId, DfaState to, int classCount) {
    if (!stateIntern.containsKey(from) && from != DfaState.DEAD) {
      return; // from was not interned; discard
    }
    transitions.compute(from, (k, table) -> {
      if (table == null) {
        table = new DfaState[classCount];
      }
      if (alphabetClassId < table.length) {
        table[alphabetClassId] = to;
      }
      return table;
    });
  }

  /**
   * Returns {@code true} if the cache has been saturated and flushed. Callers should fall back to
   * PikeVmEngine when this returns true.
   *
   * <p>After returning true, this method resets the flag so that subsequent calls return false
   * (the cache has been flushed and is ready to rebuild).
   */
  public boolean isSaturated() {
    return saturated.getAndSet(false);
  }

  /**
   * Clears all interned states and transitions, resetting the count to zero. Called automatically
   * when the cache reaches {@link #MAX_STATES}.
   */
  public void flush() {
    stateIntern.clear();
    transitions.clear();
    stateCount.set(0);
  }
}
