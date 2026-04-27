package com.orbit.engine.engines;

/**
 * O(1) membership set for integer identifiers in the range {@code [0, capacity)}.
 *
 * <p>Uses two parallel arrays — a dense array holding members in insertion order and a sparse
 * array mapping each id to its index in the dense array. Membership is tested in O(1) by a
 * two-way cross-check; clearing is O(1) by resetting the size counter without zeroing either
 * array. This makes {@code SparseSet} significantly cheaper than {@code boolean[]} for the
 * visited-PC tracking in {@code PikeVmEngine}, where {@code Arrays.fill} over the full
 * instruction array was previously called at every input position.
 *
 * <p>Instances are <em>not</em> thread-safe. Create one instance per thread or per match
 * operation.
 */
final class SparseSet {

  /** Members in insertion order; dense[0..size) are valid. */
  private final int[] dense;

  /**
   * sparse[id] = index in dense[] iff id is a member. May contain stale values for
   * non-members; the two-way cross-check in {@link #contains} distinguishes them.
   */
  private final int[] sparse;

  /** Number of current members. */
  private int size;

  /**
   * Creates a new empty set that can hold ids in {@code [0, capacity)}.
   *
   * @param capacity the exclusive upper bound on ids; must be non-negative
   */
  SparseSet(int capacity) {
    dense = new int[capacity];
    sparse = new int[capacity];
  }

  /**
   * Returns {@code true} iff {@code id} is a current member of this set. O(1).
   *
   * @param id the identifier to test; must satisfy {@code 0 <= id < capacity}
   * @return {@code true} if {@code id} is present
   */
  boolean contains(int id) {
    int idx = sparse[id];
    return idx < size && dense[idx] == id;
  }

  /**
   * Adds {@code id} to the set if not already present. O(1).
   *
   * @param id the identifier to add; must satisfy {@code 0 <= id < capacity}
   */
  void add(int id) {
    if (!contains(id)) {
      sparse[id] = size;
      dense[size++] = id;
    }
  }

  /**
   * Removes all members in O(1) by resetting the size counter.
   *
   * <p>Neither {@code dense} nor {@code sparse} is zeroed; stale values are masked by the
   * size counter and the two-way cross-check.
   */
  void clear() {
    size = 0;
  }

  /**
   * Returns the current number of members.
   *
   * @return the member count; never negative
   */
  int size() {
    return size;
  }

  /**
   * Returns the i-th member in insertion order.
   *
   * @param i the index; must satisfy {@code 0 <= i < size()}
   * @return the id at position {@code i}
   */
  int get(int i) {
    return dense[i];
  }
}
