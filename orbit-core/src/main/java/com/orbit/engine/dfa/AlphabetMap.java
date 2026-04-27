package com.orbit.engine.dfa;

import com.orbit.prog.AnyByte;
import com.orbit.prog.ByteMatch;
import com.orbit.prog.ByteRangeMatch;
import com.orbit.prog.CharMatch;
import com.orbit.prog.Instr;
import com.orbit.prog.Prog;

import java.util.TreeSet;

/**
 * Reduces the DFA branching factor by partitioning the character space into equivalence classes.
 *
 * <p>Two characters belong to the same equivalence class if and only if they behave identically
 * for the given pattern — that is, they always lead to the same next DFA state from any DFA state.
 * This reduces the number of distinct transitions from up to 65,536 (one per {@code char} value)
 * to typically 2–64.
 *
 * <p><strong>Construction:</strong> The constructor scans all {@code CharMatch}, {@code ByteMatch},
 * and {@code ByteRangeMatch} instructions in the {@code Prog} to identify breakpoints in the
 * character space, then assigns a class ID (0-indexed) to each interval between breakpoints.
 *
 * <p><strong>Usage:</strong> At match time, call {@link #classOf(char)} to map an input character
 * to its class ID before looking up a cached DFA transition. Two characters with the same class ID
 * always lead to the same next DFA state.
 *
 * <p><strong>Immutability:</strong> Instances are immutable after construction and safe to share
 * across threads and across matching calls.
 *
 * <p>This class is package-private and is not part of the public API.
 */
public final class AlphabetMap {

  /** Maximum number of classes before alphabet normalization is skipped. */
  private static final int MAX_CLASSES = 512;

  /** Number of distinct equivalence classes. */
  public final int classCount;

  /**
   * Maps each char (0..65535) to its equivalence class ID. May be a byte[], short[], or int[]
   * depending on classCount.
   */
  private final int[] classOf;

  private AlphabetMap(int classCount, int[] classOf) {
    this.classCount = classCount;
    this.classOf = classOf;
  }

  /**
   * Builds an {@code AlphabetMap} for the given compiled program.
   *
   * @param prog the compiled program; must not be null
   * @return a new {@code AlphabetMap} for {@code prog}
   */
  public static AlphabetMap build(Prog prog) {
    TreeSet<Integer> breakpoints = new TreeSet<>();
    breakpoints.add(0);
    breakpoints.add(65536);

    for (Instr instr : prog.instructions) {
      switch (instr) {
        case CharMatch cm -> {
          breakpoints.add((int) cm.lo());
          breakpoints.add(((int) cm.hi()) + 1);
        }
        case ByteMatch bm -> {
          int b = Byte.toUnsignedInt(bm.value());
          breakpoints.add(b);
          breakpoints.add(b + 1);
        }
        case ByteRangeMatch brm -> {
          int lo = Byte.toUnsignedInt(brm.lo());
          int hi = Byte.toUnsignedInt(brm.hi());
          breakpoints.add(lo);
          breakpoints.add(hi + 1);
        }
        case AnyByte ab -> {
          // AnyByte matches all bytes; no breakpoints needed
        }
        default -> {
          // Other instructions don't create character breakpoints
        }
      }
    }

    int[] bpArray = breakpoints.stream().mapToInt(Integer::intValue).toArray();
    int numIntervals = bpArray.length - 1;

    // If too many classes, fall back to identity mapping (raw char as class ID).
    if (numIntervals > MAX_CLASSES) {
      int[] identity = new int[65536];
      for (int i = 0; i < 65536; i++) {
        identity[i] = i;
      }
      return new AlphabetMap(65536, identity);
    }

    int[] classOf = new int[65536];
    for (int i = 0; i < numIntervals; i++) {
      int lo = bpArray[i];
      int hi = bpArray[i + 1]; // exclusive
      for (int c = lo; c < hi; c++) {
        classOf[c] = i;
      }
    }

    return new AlphabetMap(numIntervals, classOf);
  }

  /**
   * Returns the equivalence class ID for the given character.
   *
   * @param ch the character to classify
   * @return the class ID in [0, classCount)
   */
  public int classOf(char ch) {
    return classOf[ch];
  }
}
