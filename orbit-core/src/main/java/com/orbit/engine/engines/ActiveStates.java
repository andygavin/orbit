package com.orbit.engine.engines;

import java.util.Arrays;

/**
 * Pre-allocated slab of NFA thread state for one position step in the PikeVM simulation.
 *
 * <p>Replaces the {@code List<int[]> threads} pattern. Instead of allocating a new
 * {@code int[]} per active thread per input position, all capture data is stored in a flat
 * {@code long[]} slab. A {@link SparseSet} tracks which program counters are currently active,
 * giving O(1) membership queries and O(1) clear.
 *
 * <p>Layout of {@code slotTable}: state {@code id} occupies {@code stride} contiguous longs
 * starting at offset {@code id * stride}:
 * <pre>
 *   slotTable[id * stride + 2*i]     group i start position (Long.MIN_VALUE = unset)
 *   slotTable[id * stride + 2*i + 1] group i end   position (Long.MIN_VALUE = unset)
 * </pre>
 *
 * <p>The stride is {@code Math.max(1, numGroups * 2)}. When there are no capturing groups
 * the slab still uses stride 1 to avoid zero-length arrays.
 *
 * <p>Thread priority is implicit in {@link SparseSet} insertion order: the first thread to
 * reach a given PC wins. No explicit priority field is stored.
 *
 * <p>Instances are <em>not</em> thread-safe. One pair ({@code curr}/{@code next}) is created
 * per {@code execute()} call and reused across all position steps.
 */
final class ActiveStates {

  /** Set of active program counters in this step. */
  final SparseSet set;

  /**
   * Flat slot storage. {@code slotTable[stateId * stride + slotIdx]} holds the slot value
   * for {@code stateId} at index {@code slotIdx}. Initialised to {@link Long#MIN_VALUE};
   * unset slots always hold {@link Long#MIN_VALUE}.
   */
  final long[] slotTable;

  /** Number of slots per state: {@code Math.max(1, numGroups * 2)}. */
  final int stride;

  /**
   * Creates a new empty {@code ActiveStates} for an NFA with {@code numStates} instructions
   * and {@code stride} slots per state.
   *
   * @param numStates the number of instructions in the compiled program; determines table width
   * @param stride    the number of slots per state ({@code Math.max(1, numGroups * 2)}); must
   *                  be >= 1
   */
  ActiveStates(int numStates, int stride) {
    this.set = new SparseSet(numStates);
    this.stride = stride;
    this.slotTable = new long[numStates * stride];
    Arrays.fill(slotTable, Long.MIN_VALUE);
  }

  /**
   * Copies {@code stride} slots from state {@code from} to state {@code to} within this table.
   *
   * <p>Used to propagate capture slots when a consuming instruction fires.
   *
   * @param from source state id; must satisfy {@code 0 <= from < numStates}
   * @param to   destination state id; must satisfy {@code 0 <= to < numStates}
   */
  void copySlots(int from, int to) {
    System.arraycopy(slotTable, from * stride, slotTable, to * stride, stride);
  }

  /**
   * Returns the value of slot {@code slotIdx} for the given {@code stateId}.
   *
   * @param stateId the program counter; must satisfy {@code 0 <= stateId < numStates}
   * @param slotIdx the slot index; must satisfy {@code 0 <= slotIdx < stride}
   * @return the slot value; {@link Long#MIN_VALUE} means unset
   */
  long getSlot(int stateId, int slotIdx) {
    return slotTable[stateId * stride + slotIdx];
  }

  /**
   * Sets the value of slot {@code slotIdx} for the given {@code stateId}.
   *
   * @param stateId the program counter; must satisfy {@code 0 <= stateId < numStates}
   * @param slotIdx the slot index; must satisfy {@code 0 <= slotIdx < stride}
   * @param val     the value to store; use {@link Long#MIN_VALUE} to mark a slot as unset
   */
  void setSlot(int stateId, int slotIdx, long val) {
    slotTable[stateId * stride + slotIdx] = val;
  }
}
