package com.orbit.engine;

import com.orbit.engine.engines.BoundedBacktrackEngine;
import com.orbit.engine.engines.LazyDfaEngine;
import com.orbit.engine.engines.OnePassDfaEngine;
import com.orbit.engine.engines.PikeVmEngine;
import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import com.orbit.prefilter.Prefilter;
import com.orbit.util.EngineHint;

/**
 * Meta-engine that orchestrates prefilter application and engine selection.
 *
 * <p>The execution pipeline is:
 * <ol>
 *   <li>Run the prefilter to find a candidate position (or skip if noop).</li>
 *   <li>Select the appropriate engine based on the program's {@link EngineHint}.</li>
 *   <li>Execute the selected engine from the candidate position.</li>
 * </ol>
 *
 * <p>When a non-default region is active ({@code from != 0} or {@code to != input.length()})
 * or anchoring bounds are disabled, the {@link OnePassDfaEngine} is bypassed in favour of
 * {@link LazyDfaEngine} because the pre-computed DFA bakes in absolute-position anchor
 * semantics and cannot represent region-relative behaviour.
 *
 * <p>Instances are not instantiated — all methods are static.
 */
public class MetaEngine {

  /**
   * All available engine implementations. The PikeVmEngine is used as the universal fallback
   * because it handles every instruction type the compiler can emit.
   */
  private static final PikeVmEngine PIKE_VM = new PikeVmEngine();
  private static final BoundedBacktrackEngine BACKTRACKER = new BoundedBacktrackEngine();
  private static final LazyDfaEngine LAZY_DFA = new LazyDfaEngine();
  private static final OnePassDfaEngine ONE_PASS_DFA = new OnePassDfaEngine();

  private MetaEngine() {}

  /**
   * Returns the engine appropriate for the given hint, taking region and bounds flags into
   * account.
   *
   * <p>Routing table (after region override):
   * <ul>
   *   <li>{@code ONE_PASS_SAFE} with default region and anchoring bounds → {@link OnePassDfaEngine}</li>
   *   <li>{@code ONE_PASS_SAFE} with non-default region or {@code !anchoringBounds} → {@link LazyDfaEngine}</li>
   *   <li>{@code DFA_SAFE} → {@link LazyDfaEngine}</li>
   *   <li>{@code PIKEVM_ONLY} → {@link PikeVmEngine}</li>
   *   <li>{@code NEEDS_BACKTRACKER} → {@link BoundedBacktrackEngine}</li>
   *   <li>{@code GRAMMAR_RULE} and unknown values → {@link PikeVmEngine} (default)</li>
   * </ul>
   *
   * @param hint              the engine-selection hint; must not be null
   * @param from              the inclusive search start
   * @param to                the exclusive search end (equals {@code regionEnd})
   * @param inputLength       the full input length
   * @param anchoringBounds   whether anchoring bounds are active
   * @param transparentBounds whether transparent bounds are active
   * @return the selected engine, never null
   */
  public static Engine getEngine(EngineHint hint, int from, int to, int inputLength,
      boolean anchoringBounds, boolean transparentBounds) {
    return switch (hint) {
      case ONE_PASS_SAFE -> {
        // Bypass OnePassDfaEngine when region is non-default, anchoring is disabled, or
        // transparent bounds are enabled. The precomputed DFA bakes in absolute anchor
        // positions and cannot represent region-relative behaviour.
        boolean defaultRegion = (from == 0 || to == inputLength)
            && to == inputLength;
        // More precise: non-default means either end differs, or start is non-zero at
        // the first find call. We track via from and to passed in.
        boolean regionIsDefault = (to == inputLength) && anchoringBounds && !transparentBounds;
        if (regionIsDefault) {
          yield ONE_PASS_DFA;
        }
        yield LAZY_DFA;
      }
      case DFA_SAFE      -> LAZY_DFA;
      case PIKEVM_ONLY             -> PIKE_VM;
      case NEEDS_BACKTRACKER       -> BACKTRACKER;
      default                      -> PIKE_VM;
    };
  }

  /**
   * Returns the engine appropriate for the given hint (legacy overload with default bounds).
   *
   * @param hint the engine-selection hint; must not be null
   * @return the selected engine, never null
   */
  public static Engine getEngine(EngineHint hint) {
    return switch (hint) {
      case ONE_PASS_SAFE -> ONE_PASS_DFA;
      case DFA_SAFE      -> LAZY_DFA;
      case PIKEVM_ONLY             -> PIKE_VM;
      case NEEDS_BACKTRACKER       -> BACKTRACKER;
      default                      -> PIKE_VM;
    };
  }

  /**
   * Executes the full meta-engine pipeline: prefilter then engine, with region and bounds
   * parameters.
   *
   * <p>When the program is start-anchored, any prefilter candidate that does not land
   * exactly at {@code from} is treated as a definitive no-match — a start-anchored pattern
   * cannot match starting after the search origin.
   *
   * @param prog              the compiled program; must not be null
   * @param input             the input string; must not be null
   * @param from              the starting search position for this call (inclusive); may be
   *                          greater than {@code regionStart} after zero-length matches
   * @param to                the ending search position (exclusive); equals {@code regionEnd}
   * @param lastMatchEnd      the end position of the previous successful match; {@code 0} if none
   * @param anchoringBounds   when {@code true}, anchors treat region boundaries as input edges;
   *                          when {@code false}, anchors see the full input
   * @param transparentBounds when {@code true}, lookaround sees outside the region; when
   *                          {@code false} (default), lookaround is blocked at region boundaries
   * @param regionStart       the constant inclusive start of the matcher's region; anchors use
   *                          this when {@code anchoringBounds} is {@code true}
   * @return the match result; never null
   */
  public static MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart) {
    Prefilter prefilter = prog.metadata.prefilter();
    boolean startAnchored = prog.metadata.startAnchored();

    // If the prefilter is trivial (noop), skip it and run the engine directly.
    // Non-trivial prefilters can narrow the search range to a candidate start position.
    int candidate;
    if (prefilter.isTrivial()) {
      candidate = from;
    } else {
      candidate = prefilter.findFirst(input, from, to);
      if (candidate < 0) {
        // Prefilter found no candidate in [from, to].
        // For start-anchored patterns, the engine would fail at position 'from' without
        // scanning to 'to' — hitEnd = false. For non-anchored patterns, the prefilter's
        // scan represents the engine covering the full range — hitEnd = true.
        boolean hitEnd = !startAnchored;
        return new MatchResult(false, -1, -1, java.util.List.of(),
            java.util.List.of(), null, 0L, 0L, 0, hitEnd);
      }
      // A start-anchored pattern can only match at exactly the search origin. If the
      // prefilter found a candidate further along, no match is possible.
      // hitEnd = false: the anchor failed at position 'from' without reaching 'to'.
      if (startAnchored && candidate != from) {
        return new MatchResult(false, -1, -1, java.util.List.of(),
            java.util.List.of(), null, 0L, 0L, 0, false);
      }
    }

    // Select and run the engine with full region context.
    Engine engine = getEngine(prog.metadata.hint(), regionStart, to, input.length(),
        anchoringBounds, transparentBounds);
    return engine.execute(prog, input, candidate, to, lastMatchEnd,
        anchoringBounds, transparentBounds, regionStart);
  }

  /**
   * Executes the full meta-engine pipeline: prefilter then engine, with region and bounds
   * parameters. Uses {@code from} as the region start (for call sites that do not track region
   * separately, e.g. one-shot operations where {@code from == regionStart}).
   *
   * @param prog              the compiled program; must not be null
   * @param input             the input string; must not be null
   * @param from              the starting search position (inclusive); also used as region start
   * @param to                the ending search position (exclusive); equals {@code regionEnd}
   * @param lastMatchEnd      the end position of the previous successful match; {@code 0} if none
   * @param anchoringBounds   when {@code true}, anchors treat region boundaries as input edges
   * @param transparentBounds when {@code true}, lookaround sees outside the region
   * @return the match result; never null
   */
  public static MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds) {
    return execute(prog, input, from, to, lastMatchEnd, anchoringBounds, transparentBounds, from);
  }

  /**
   * Executes the full meta-engine pipeline with {@code lastMatchEnd = 0} and default bounds.
   *
   * <p>Convenience overload for call sites that do not track previous match positions
   * (e.g. one-shot {@code matches()} or {@code lookingAt()} operations).
   *
   * @param prog  the compiled program; must not be null
   * @param input the input string; must not be null
   * @param from  the starting search position (inclusive)
   * @param to    the ending search position (exclusive)
   * @return the match result; never null
   */
  public static MatchResult execute(Prog prog, String input, int from, int to) {
    return execute(prog, input, from, to, 0, true, false);
  }

  /**
   * Executes the full meta-engine pipeline with default bounds.
   *
   * <p>Convenience overload for call sites that track previous match positions but use
   * the default region and bounds (anchoring enabled, transparent disabled).
   *
   * @param prog         the compiled program; must not be null
   * @param input        the input string; must not be null
   * @param from         the starting search position (inclusive)
   * @param to           the ending search position (exclusive)
   * @param lastMatchEnd the end position of the previous successful match; {@code 0} if none
   * @return the match result; never null
   */
  public static MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd) {
    return execute(prog, input, from, to, lastMatchEnd, true, false);
  }
}
