package com.orbit.api;

import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;
import com.orbit.engine.MetaEngine;
import com.orbit.engine.engines.PikeVmEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Matches a compiled {@link com.orbit.api.Pattern} against an input sequence.
 *
 * <p>Drop-in compatible with {@link java.util.regex.Matcher}. Supports indexed and named
 * capture group access via {@link #group(int)}, {@link #group(String)}, {@link #start(int)},
 * {@link #end(int)}, {@link #start(String)}, and {@link #end(String)}.
 *
 * <p>Instances are <em>not</em> thread-safe. Create one instance per thread or per match
 * operation.
 */
public class Matcher {

  private com.orbit.api.Pattern pattern;
  private final CharSequence input;
  /** Current search cursor; satisfies {@code regionStart <= from <= regionEnd}. */
  private int from;
  /**
   * Exclusive upper bound of the current region; equal to {@code regionEnd}.
   * Retained as a separate field for compatibility with existing call sites.
   */
  private int to;
  private MatchResult lastResult;
  private boolean findFromStart;
  /** Tracks the position up to which input has been consumed by appendReplacement/appendTail. */
  private int lastAppendPos;
  /**
   * The end position of the most recent successful {@link #find()} call. Used to evaluate
   * {@code \G} (BeginG) assertions. Initialised to {@code 0}; reset to {@code 0} by
   * {@link #reset()} and {@link #find(int)}.
   */
  private int lastMatchEnd;
  /**
   * Structural-modification counter. Incremented by {@link #reset()}, {@link #find(int)}, and
   * {@link #find()} so that lazy streams returned by {@link #results()} can detect concurrent
   * modification and throw {@link ConcurrentModificationException}.
   */
  private int modCount;
  /**
   * Set by each match operation. {@code true} when the last operation reached the end of the
   * region — either a successful match whose end position equals {@code to}, or a failed search
   * where the engine scanned up to the region boundary before determining no match exists.
   * {@code false} when the engine determined the result definitively without reaching the
   * region end (e.g. a start-anchored pattern that fails at position 0).
   *
   * <p>This is a precise implementation of {@link java.util.regex.Matcher#hitEnd()} backed by
   * engine-level instrumentation in {@link com.orbit.engine.engines.BoundedBacktrackEngine}
   * and {@link com.orbit.engine.engines.PikeVmEngine}. For
   * {@link com.orbit.engine.engines.LazyDfaEngine} and the prefilter short-circuit path, the
   * value is propagated from the engine's {@link com.orbit.prog.MatchResult#hitEnd()} field.
   */
  private boolean hitEnd;

  // -----------------------------------------------------------------------
  // Region state — JDK Matcher.region() API
  // -----------------------------------------------------------------------

  /** Inclusive start of the current region. Default is {@code 0}. */
  private int regionStart;
  /** Exclusive end of the current region. Default is {@code input.length()}. */
  private int regionEnd;
  /**
   * When {@code true} (the default), {@code ^}/{@code $}/{@code \A}/{@code \z} treat region
   * boundaries as the start/end of input. When {@code false}, anchors are evaluated against
   * the full input string regardless of the region.
   */
  private boolean anchoringBounds;
  /**
   * When {@code false} (the default), lookahead and lookbehind are blocked at the region
   * boundaries. When {@code true}, they can see characters outside the region.
   */
  private boolean transparentBounds;

  /**
   * Creates a new {@code Matcher} for the given pattern and input.
   *
   * <p>The initial region covers the full input {@code [0, input.length())}, anchoring bounds
   * are enabled, and transparent bounds are disabled — matching JDK defaults.
   *
   * @param pattern the compiled pattern; must not be null
   * @param input   the character sequence to match; must not be null
   */
  public Matcher(com.orbit.api.Pattern pattern, CharSequence input) {
    this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
    this.input = Objects.requireNonNull(input, "input must not be null");
    this.regionStart = 0;
    this.regionEnd = input.length();
    this.from = 0;
    this.to = input.length();
    this.lastResult = null;
    this.findFromStart = true;
    this.lastAppendPos = 0;
    this.lastMatchEnd = 0;
    this.anchoringBounds = true;
    this.transparentBounds = false;
  }

  /**
   * Per-thread PikeVmEngine for {@link #matches()}.
   *
   * <p>{@link PikeVmEngine} is not thread-safe (it has mutable instance fields that accumulate
   * NFA state during execution). Using a {@link ThreadLocal} avoids contention and keeps one
   * re-usable instance per thread without the risk of concurrent mutation.
   */
  private static final ThreadLocal<PikeVmEngine> PIKE_VM_TL =
      ThreadLocal.withInitial(PikeVmEngine::new);

  /**
   * Attempts to match the entire region against the pattern.
   *
   * @return true if the entire region matches
   */
  public boolean matches() {
    // Use matchesProg (pattern + implicit EOF anchor) so that all alternatives in an
    // alternation are explored until one spans [from, to] fully. This prevents leftmost-first
    // NFA priority from returning a short match that does not cover the full region (e.g.
    // "a|ad" on "ad" would otherwise report a match at [0,1] rather than [0,2]).
    //
    // PikeVmEngine is used unconditionally because LazyDfaEngine caches DFA transitions
    // without re-evaluating position-dependent anchor checks (EndText, EndZ) for each
    // position: a transition where EndText did not fire is cached as non-accepting and
    // incorrectly reused even when the position later equals the region end. PikeVmEngine
    // evaluates all NFA threads afresh on every call and is always correct here.
    Prog prog = pattern.matchesProg();
    lastResult = PIKE_VM_TL.get().execute(prog, input.toString(), from, to, 0,
        anchoringBounds, transparentBounds, from);
    hitEnd = true; // matches() always spans [from,to], so end of region is always reached
    return lastResult.matches() && lastResult.start() == from && lastResult.end() == to;
  }

  /**
   * Attempts to find the next subsequence of the input sequence that matches the pattern.
   *
   * <p>After a zero-length match the search position is advanced by one character so that
   * subsequent calls do not return the same zero-length match and so that the method
   * terminates.  This matches the behaviour of {@link java.util.regex.Matcher#find()}.
   *
   * @return true if a subsequent match is found
   */
  public boolean find() {
    modCount++;
    Prog prog = pattern.prog();

    if (findFromStart) {
      findFromStart = false;
      // Start at regionStart, not 0, so that region() constrains the first find().
      from = regionStart;
    }

    if (from > to) {
      return false;
    }

    lastResult = MetaEngine.execute(
        prog, input.toString(), from, to, lastMatchEnd, anchoringBounds, transparentBounds,
        regionStart);
    if (lastResult.matches()) {
      int matchEnd = lastResult.end();
      lastMatchEnd = matchEnd;
      hitEnd = matchEnd == to;
      // Advance past zero-length matches to prevent returning the same match repeatedly.
      from = (matchEnd == lastResult.start()) ? matchEnd + 1 : matchEnd;
      return true;
    }

    // Use engine-level hitEnd: true when the engine reached the region boundary during its
    // scan (more input could have changed the result); false when the engine failed before
    // reaching the boundary (e.g. a start-anchored pattern that fails at position 0).
    hitEnd = lastResult.hitEnd();
    return false;
  }

  /**
   * Resets this matcher and then attempts to find a match starting no earlier than
   * character position {@code start}.
   *
   * <p>Performs a full reset (equivalent to calling {@link #reset()}) before beginning the
   * search. The search starts at position {@code start} within the input and proceeds
   * forward. After a zero-length match the search position is advanced by one character.
   *
   * <p>After a successful call, invoking {@link #find()} continues the search from
   * where this call left off.
   *
   * @param start the character index at which to begin the search; must be in
   *              {@code [0, input.length()]}
   * @return true if a match is found starting at or after {@code start}
   * @throws IndexOutOfBoundsException if {@code start < 0} or {@code start > input.length()}
   */
  public boolean find(int start) {
    // find(int start) performs a full reset — region is restored to [0, input.length()].
    // start is validated against the full input, not the current region.
    if (start < 0 || start > input.length()) {
      throw new IndexOutOfBoundsException("Illegal start index");
    }

    // Full reset (mirrors reset() postconditions).
    this.regionStart = 0;
    this.regionEnd = input.length();
    this.from = start;
    this.to = input.length();
    this.lastResult = null;
    this.findFromStart = false;
    this.lastAppendPos = 0;
    this.lastMatchEnd = 0;
    this.anchoringBounds = true;
    this.transparentBounds = false;
    this.modCount++;

    // Guard for exhausted range
    if (from > to) {
      return false;
    }

    lastResult = MetaEngine.execute(
        pattern.prog(), input.toString(), from, to, 0, true, false);
    if (lastResult.matches()) {
      hitEnd = lastResult.end() == to;
      from = (lastResult.end() == lastResult.start()) ? lastResult.end() + 1 : lastResult.end();
      return true;
    }

    hitEnd = lastResult.hitEnd();
    return false;
  }

  /**
   * Attempts to match the input sequence, starting at the beginning of the region
   * ({@code from}), against the pattern.
   *
   * <p>Unlike {@link #matches()}, the entire region does not need to match; the pattern
   * must only match a prefix of the region starting exactly at {@code from}. Unlike
   * {@link #find()}, the match must start at {@code from} exactly.
   *
   * <p>This method does not modify {@code from}, {@code to}, {@code lastAppendPos}, or
   * {@code findFromStart}. A subsequent {@link #find()} call will resume from whatever
   * {@code from} was before this call.
   *
   * <p>After a successful return, {@link #group()}, {@link #start()}, {@link #end()}, and
   * group accessors all work as after a successful {@link #find()} or {@link #matches()}.
   *
   * @return true if the pattern matches a prefix of the region starting at {@code from}
   */
  public boolean lookingAt() {
    lastResult = MetaEngine.execute(
        pattern.prog(), input.toString(), from, to, lastMatchEnd,
        anchoringBounds, transparentBounds);
    boolean matched = lastResult.matches() && lastResult.start() == from;
    hitEnd = matched ? (lastResult.end() == to) : lastResult.hitEnd();
    return matched;
  }

  /**
   * Resets this matcher. The region is restored to {@code [0, input.length())},
   * {@code anchoringBounds} is set to {@code true}, and {@code transparentBounds} is set to
   * {@code false} — matching JDK {@code reset()} semantics.
   *
   * @return this matcher
   */
  public Matcher reset() {
    this.regionStart = 0;
    this.regionEnd = input.length();
    this.from = 0;
    this.to = input.length();
    this.lastResult = null;
    this.findFromStart = true;
    this.lastAppendPos = 0;
    this.lastMatchEnd = 0;
    this.anchoringBounds = true;
    this.transparentBounds = false;
    this.hitEnd = false;
    this.modCount++;
    return this;
  }

  // -----------------------------------------------------------------------
  // Region API — JDK Matcher.region() / regionStart() / regionEnd() etc.
  // -----------------------------------------------------------------------

  /**
   * Sets the limits of this matcher's region.
   *
   * <p>All subsequent match operations search only within {@code [start, end)} of the full
   * input. The search cursor is reset to {@code start}, and all previous match state is
   * cleared. The anchoring and transparent bounds flags are not changed.
   *
   * @param start the inclusive start of the new region; must satisfy {@code 0 <= start <= input.length()}
   * @param end   the exclusive end of the new region; must satisfy {@code start <= end <= input.length()}
   * @return this matcher
   * @throws IndexOutOfBoundsException if any bound is outside the valid range
   */
  public Matcher region(int start, int end) {
    if (start < 0) {
      throw new IndexOutOfBoundsException("start is negative: " + start);
    }
    if (start > input.length()) {
      throw new IndexOutOfBoundsException(
          "start (" + start + ") > input.length() (" + input.length() + ")");
    }
    if (end < start) {
      throw new IndexOutOfBoundsException(
          "end (" + end + ") < start (" + start + ")");
    }
    if (end > input.length()) {
      throw new IndexOutOfBoundsException(
          "end (" + end + ") > input.length() (" + input.length() + ")");
    }
    this.regionStart = start;
    this.regionEnd = end;
    this.from = start;
    this.to = end;
    this.lastResult = null;
    this.findFromStart = true;
    this.lastAppendPos = 0;
    this.lastMatchEnd = 0;
    // anchoringBounds and transparentBounds are deliberately preserved.
    return this;
  }

  /**
   * Returns the start (inclusive) of this matcher's region.
   *
   * @return the region start; {@code 0} by default
   */
  public int regionStart() {
    return regionStart;
  }

  /**
   * Returns the end (exclusive) of this matcher's region.
   *
   * @return the region end; {@code input.length()} by default
   */
  public int regionEnd() {
    return regionEnd;
  }

  /**
   * Sets whether anchors ({@code ^}, {@code $}, {@code \A}, {@code \z}) treat the region
   * boundaries as the start/end of input.
   *
   * <p>When {@code b} is {@code true} (the default), anchors are evaluated relative to the
   * current region. When {@code false}, anchors are evaluated against the full input string.
   *
   * @param b {@code true} to enable anchoring bounds, {@code false} to disable
   * @return this matcher
   */
  public Matcher useAnchoringBounds(boolean b) {
    this.anchoringBounds = b;
    return this;
  }

  /**
   * Returns whether this matcher uses anchoring bounds.
   *
   * @return {@code true} if anchoring bounds are enabled; {@code true} by default
   */
  public boolean hasAnchoringBounds() {
    return anchoringBounds;
  }

  /**
   * Sets whether lookahead and lookbehind assertions can see characters outside the region.
   *
   * <p>When {@code b} is {@code false} (the default), lookahead is blocked at {@code regionEnd}
   * and lookbehind is blocked at {@code regionStart}. When {@code true}, assertions see the
   * full input.
   *
   * @param b {@code true} to enable transparent bounds, {@code false} to disable
   * @return this matcher
   */
  public Matcher useTransparentBounds(boolean b) {
    this.transparentBounds = b;
    return this;
  }

  /**
   * Returns whether this matcher uses transparent bounds.
   *
   * @return {@code true} if transparent bounds are enabled; {@code false} by default
   */
  public boolean hasTransparentBounds() {
    return transparentBounds;
  }

  /**
   * Returns {@code true} if the end of input was hit by the search engine in the last match
   * operation performed by this matcher.
   *
   * <p>Returns {@code true} when:
   * <ul>
   *   <li>the last operation was {@link #matches()} (any result — the engine always spans
   *       {@code [from, to]});</li>
   *   <li>the last operation returned {@code true} and the match end equals the region
   *       boundary {@code to} (more input could extend the match); or</li>
   *   <li>the last operation returned {@code false} and the engine scanned input up to the
   *       region boundary before concluding no match exists (more input could produce one).</li>
   * </ul>
   *
   * <p>Returns {@code false} when:
   * <ul>
   *   <li>no match operation has been performed since the last {@link #reset()};</li>
   *   <li>the last operation returned {@code true} and the match end is strictly inside the
   *       region (more input cannot change the found match); or</li>
   *   <li>the last operation returned {@code false} and the engine determined no match was
   *       possible without reaching the region end — for example, a start-anchored pattern
   *       like {@code ^abc} applied to {@code "xyz"} fails at position 0.</li>
   * </ul>
   *
   * @return {@code true} if the end of input was hit during the last operation
   */
  public boolean hitEnd() {
    return hitEnd;
  }

  /**
   * Returns whether the last match operation succeeded.
   *
   * <p>Returns false if no match has been attempted or the last attempt failed.
   * Mirrors Java 21's {@code MatchResult.hasMatch()}.
   *
   * @return true if the last find or matches call found a match
   */
  public boolean hasMatch() {
    return lastResult != null && lastResult.matches();
  }

  /**
   * Returns the start index of the previous match.
   *
   * @return the start index
   * @throws IllegalStateException if no match has been attempted yet
   */
  public int start() {
    if (lastResult == null) {
      throw new IllegalStateException("No match available");
    }
    return lastResult.start();
  }

  /**
   * Returns the end index (exclusive) of the previous match.
   *
   * @return the end index
   * @throws IllegalStateException if no match has been attempted yet
   */
  public int end() {
    if (lastResult == null) {
      throw new IllegalStateException("No match available");
    }
    return lastResult.end();
  }

  /**
   * Returns the start index of the subsequence captured by the given group during the
   * previous match operation.
   *
   * @param group the 1-based capturing group index; 0 returns the match start
   * @return the start index of the captured group, or -1 if the group did not match
   * @throws IllegalStateException      if no match has been attempted or no match found
   * @throws IndexOutOfBoundsException  if {@code group} is out of range
   */
  public int start(int group) {
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    if (group == 0) {
      return lastResult.start();
    }
    if (group < 1 || group > lastResult.groupSpans().size()) {
      throw new IndexOutOfBoundsException("Group index out of range: " + group);
    }
    return lastResult.groupSpans().get(group - 1)[0];
  }

  /**
   * Returns the end index (exclusive) of the subsequence captured by the given group during
   * the previous match operation.
   *
   * @param group the 1-based capturing group index; 0 returns the match end
   * @return the end index (exclusive) of the captured group, or -1 if the group did not match
   * @throws IllegalStateException      if no match has been attempted or no match found
   * @throws IndexOutOfBoundsException  if {@code group} is out of range
   */
  public int end(int group) {
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    if (group == 0) {
      return lastResult.end();
    }
    if (group < 1 || group > lastResult.groupSpans().size()) {
      throw new IndexOutOfBoundsException("Group index out of range: " + group);
    }
    return lastResult.groupSpans().get(group - 1)[1];
  }

  /**
   * Returns the input subsequence matched by the previous match operation.
   *
   * @return the matched subsequence; never null
   * @throws IllegalStateException if no match has been attempted yet
   */
  public String group() {
    if (lastResult == null) {
      throw new IllegalStateException("No match available");
    }
    return input.subSequence(lastResult.start(), lastResult.end()).toString();
  }

  /**
   * Returns the input subsequence captured by the given group during the previous match
   * operation.
   *
   * @param group the 1-based group index; 0 returns the whole match
   * @return the captured substring, or null if the group did not participate in the match
   * @throws IllegalStateException     if no match has been performed or no match found
   * @throws IndexOutOfBoundsException if {@code group} is out of range
   */
  public String group(int group) {
    if (lastResult == null) {
      throw new IllegalStateException("No match available");
    }
    if (!lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    if (group == 0) {
      return input.subSequence(lastResult.start(), lastResult.end()).toString();
    }
    if (group < 1 || group > lastResult.groups().size()) {
      throw new IndexOutOfBoundsException("Group index out of range: " + group);
    }
    return lastResult.groups().get(group - 1);
  }

  /**
   * Returns the input subsequence captured by the named group during the previous match
   * operation.
   *
   * @param name the capturing group name; must not be null
   * @return the captured substring, or null if the group did not participate in the match
   * @throws IllegalStateException    if no match has been performed or no match found
   * @throws IllegalArgumentException if {@code name} does not correspond to a named group
   */
  public String group(String name) {
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    int index = resolveGroupName(name);
    return group(index);
  }

  /**
   * Returns the start index of the subsequence captured by the named group during the
   * previous match operation.
   *
   * @param name the capturing group name; must not be null
   * @return the start index of the named group, or -1 if the group did not participate
   * @throws IllegalStateException    if no match has been performed or no match found
   * @throws IllegalArgumentException if {@code name} does not correspond to a named group
   */
  public int start(String name) {
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    int index = resolveGroupName(name);
    return start(index);
  }

  /**
   * Returns the end index (exclusive) of the subsequence captured by the named group during
   * the previous match operation.
   *
   * @param name the capturing group name; must not be null
   * @return the end index (exclusive) of the named group, or -1 if the group did not participate
   * @throws IllegalStateException    if no match has been performed or no match found
   * @throws IllegalArgumentException if {@code name} does not correspond to a named group
   */
  public int end(String name) {
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    int index = resolveGroupName(name);
    return end(index);
  }

  /**
   * Resolves a named group to its 1-based numeric index.
   *
   * @param name the group name; must not be null
   * @return the 1-based group index
   * @throws IllegalArgumentException if no group with this name exists in the pattern
   */
  private int resolveGroupName(String name) {
    Integer index = pattern.metadata().groupNames().get(name);
    if (index == null) {
      throw new IllegalArgumentException(
          "No group with name <" + name + "> in pattern");
    }
    return index;
  }

  /**
   * Returns the number of capturing groups in this matcher's pattern.
   *
   * <p>This value is determined solely by the compiled pattern and does not depend on
   * whether a match has been found. This mirrors the contract of
   * {@code java.util.regex.Matcher#groupCount()}.
   *
   * @return the number of capturing groups; zero if the pattern has none
   */
  public int groupCount() {
    return pattern.metadata().groupCount();
  }

  /**
   * Returns an unmodifiable map from named capturing group names to their 1-based indices.
   *
   * <p>Derived from the pattern's metadata. Returns an empty map if the pattern has no
   * named groups.
   *
   * @return an unmodifiable map of group name to 1-based index; never null
   */
  public Map<String, Integer> namedGroups() {
    Map<String, Integer> groups = pattern.metadata().groupNames();
    if (groups.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(groups);
  }

  /**
   * Changes the pattern used by this matcher to find matches in the input.
   *
   * <p>This is a partial reset. The last match result is discarded and the append position
   * is reset to zero. The input, the current region ({@code regionStart}, {@code regionEnd}),
   * the region bounds flags ({@code anchoringBounds}, {@code transparentBounds}), the current
   * search cursor ({@code from}), and the last-match-end position ({@code lastMatchEnd}) are
   * all preserved. The next {@link #find()} call continues from wherever the cursor was.
   *
   * <p>This matches the contract of {@code java.util.regex.Matcher#usePattern(Pattern)}.
   *
   * @param newPattern the new pattern; must not be null
   * @return this matcher
   * @throws NullPointerException if {@code newPattern} is null
   */
  public Matcher usePattern(com.orbit.api.Pattern newPattern) {
    this.pattern = Objects.requireNonNull(newPattern, "newPattern must not be null");
    // Partial reset: clear match state and append position only.
    // Cursor (from), region bounds, anchoring flags, lastMatchEnd, and findFromStart
    // are all deliberately preserved.
    this.lastResult = null;
    this.lastAppendPos = 0;
    return this;
  }

  /**
   * Replaces every subsequence of the input sequence that matches the pattern with the
   * given replacement string.
   *
   * <p>Resets the matcher before searching, mirroring JDK semantics.
   *
   * @param replacement the replacement string; must not be null
   * @return the resulting string with all matches replaced
   * @throws NullPointerException if {@code replacement} is null
   */
  public String replaceAll(String replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");

    reset();
    StringBuilder sb = new StringBuilder();
    int lastEnd = 0;
    boolean found = false;

    while (find()) {
      found = true;
      sb.append(input.subSequence(lastEnd, start()));
      sb.append(applyReplacement(replacement));
      lastEnd = end();
    }

    if (!found) {
      return input.toString();
    }

    sb.append(input.subSequence(lastEnd, input.length()));
    return sb.toString();
  }

  /**
   * Replaces every subsequence of the input that matches the pattern with the string
   * produced by applying {@code replacer} to each {@link java.util.regex.MatchResult}.
   *
   * <p>Resets the matcher before searching, mirroring JDK semantics.
   *
   * @param replacer a function mapping each match result to a replacement string; must not be null
   * @return the resulting string with all matches replaced
   * @throws NullPointerException if {@code replacer} is null
   */
  public String replaceAll(Function<java.util.regex.MatchResult, String> replacer) {
    Objects.requireNonNull(replacer, "replacer must not be null");

    reset();
    StringBuilder sb = new StringBuilder();
    int lastEnd = 0;
    boolean found = false;

    while (find()) {
      found = true;
      sb.append(input.subSequence(lastEnd, start()));
      int expectedMod = modCount;
      sb.append(replacer.apply(toMatchResult()));
      if (modCount != expectedMod) {
        throw new ConcurrentModificationException();
      }
      lastEnd = end();
    }

    if (!found) {
      return input.toString();
    }

    sb.append(input.subSequence(lastEnd, input.length()));
    return sb.toString();
  }

  /**
   * Resets the matcher, finds the first match, replaces it with {@code replacement}, and
   * returns the result. Returns the input unchanged if no match is found.
   *
   * @param replacement the replacement string; must not be null
   * @return the resulting string with the first match replaced, or the original input if no match
   * @throws NullPointerException if {@code replacement} is null
   */
  public String replaceFirst(String replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");

    reset();
    if (!find()) {
      return input.toString();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(input.subSequence(0, start()));
    sb.append(applyReplacement(replacement));
    sb.append(input.subSequence(end(), input.length()));
    return sb.toString();
  }

  /**
   * Resets the matcher, finds the first match, applies {@code replacer} to produce a
   * replacement string, and returns the result. Returns the input unchanged if no match.
   *
   * @param replacer a function mapping the match result to a replacement string; must not be null
   * @return the resulting string with the first match replaced, or the original input if no match
   * @throws NullPointerException if {@code replacer} is null
   */
  public String replaceFirst(Function<java.util.regex.MatchResult, String> replacer) {
    Objects.requireNonNull(replacer, "replacer must not be null");

    reset();
    if (!find()) {
      return input.toString();
    }
    StringBuilder sb = new StringBuilder();
    sb.append(input.subSequence(0, start()));
    int expectedMod = modCount;
    String replacement = replacer.apply(toMatchResult());
    if (modCount != expectedMod) {
      throw new ConcurrentModificationException();
    }
    sb.append(replacement);
    sb.append(input.subSequence(end(), input.length()));
    return sb.toString();
  }

  /**
   * Appends the input between {@code lastAppendPos} and {@link #start()} to {@code sb},
   * then appends {@code replacement}. Advances {@code lastAppendPos} to {@link #end()}.
   *
   * <p>Intended for use in a loop with {@link #appendTail(StringBuilder)}.
   *
   * @param sb          the string builder to append to; must not be null
   * @param replacement the replacement string to append; must not be null
   * @return this matcher
   * @throws NullPointerException  if {@code sb} or {@code replacement} is null
   * @throws IllegalStateException if no match has been found
   */
  public Matcher appendReplacement(StringBuilder sb, String replacement) {
    Objects.requireNonNull(sb, "sb must not be null");
    Objects.requireNonNull(replacement, "replacement must not be null");
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    String applied = applyReplacement(replacement);
    sb.append(input.subSequence(lastAppendPos, start()));
    sb.append(applied);
    lastAppendPos = end();
    return this;
  }

  /**
   * Appends the input between {@code lastAppendPos} and {@link #start()} to {@code sb},
   * then appends the processed replacement string. Advances {@code lastAppendPos} to
   * {@link #end()}.
   *
   * <p>Group references ({@code $1}, {@code ${name}}) and escape sequences ({@code \\},
   * {@code \$}) in the replacement are expanded by {@link #applyReplacement(String)}.
   *
   * @param sb          the string buffer to append to; must not be null
   * @param replacement the replacement string; must not be null
   * @return this matcher
   * @throws NullPointerException      if {@code sb} or {@code replacement} is null
   * @throws IllegalStateException     if no match has been found
   * @throws IllegalArgumentException  if {@code replacement} contains an illegal group reference
   *                                   syntax
   * @throws IndexOutOfBoundsException if {@code replacement} references a group that does not
   *                                   exist
   */
  public Matcher appendReplacement(StringBuffer sb, String replacement) {
    Objects.requireNonNull(sb, "sb must not be null");
    Objects.requireNonNull(replacement, "replacement must not be null");
    if (lastResult == null || !lastResult.matches()) {
      throw new IllegalStateException("No match available");
    }
    String applied = applyReplacement(replacement);
    sb.append(input.subSequence(lastAppendPos, start()));
    sb.append(applied);
    lastAppendPos = end();
    return this;
  }

  /**
   * Appends the remaining input from {@code lastAppendPos} to the end of the input to
   * {@code sb}.
   *
   * @param sb the string builder to append to; must not be null
   * @return {@code sb}
   * @throws NullPointerException if {@code sb} is null
   */
  public StringBuilder appendTail(StringBuilder sb) {
    Objects.requireNonNull(sb, "sb must not be null");
    sb.append(input.subSequence(lastAppendPos, input.length()));
    return sb;
  }

  /**
   * Appends the remaining input from {@code lastAppendPos} to the end of the input to
   * {@code sb}.
   *
   * @param sb the string buffer to append to; must not be null
   * @return {@code sb}
   * @throws NullPointerException if {@code sb} is null
   */
  public StringBuffer appendTail(StringBuffer sb) {
    Objects.requireNonNull(sb, "sb must not be null");
    sb.append(input.subSequence(lastAppendPos, input.length()));
    return sb;
  }

  /**
   * Returns a string in which each metacharacter ({@code \} and {@code $}) in {@code s} is
   * escaped, producing a replacement string that is treated as entirely literal by
   * {@link #appendReplacement(StringBuilder, String)}, {@link #replaceAll(String)}, and
   * {@link #replaceFirst(String)}.
   *
   * <p>This is the inverse of {@link #applyReplacement(String)} for pure-literal input:
   * {@code applyReplacement(quoteReplacement(s))} returns {@code s} for any string {@code s}
   * and any match state.
   *
   * <p>This method is a static utility and has no effect on matcher state.
   *
   * @param s the string to quote; must not be null
   * @return the quoted string; never null
   * @throws NullPointerException if {@code s} is null
   */
  public static String quoteReplacement(String s) {
    Objects.requireNonNull(s, "s must not be null");
    if (!s.contains("\\") && !s.contains("$")) {
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' || c == '$') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Returns a lazily-evaluated stream of all non-overlapping matches in the input from the
   * current position.
   *
   * <p>Resets the matcher first to ensure consistent behaviour, then produces one
   * {@link java.util.regex.MatchResult} snapshot per match. The stream terminates when
   * {@link #find()} returns false.
   *
   * @return a stream of match results; never null
   */
  public Stream<java.util.regex.MatchResult> results() {
    reset();
    // Capture modCount after reset so the spliterator can detect any mutation during traversal.
    final int[] expectedMod = {modCount};
    Spliterator<java.util.regex.MatchResult> sp =
        new Spliterators.AbstractSpliterator<java.util.regex.MatchResult>(
            Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
          @Override
          public boolean tryAdvance(Consumer<? super java.util.regex.MatchResult> action) {
            if (modCount != expectedMod[0]) {
              throw new ConcurrentModificationException();
            }
            if (!find()) {
              return false;
            }
            // Sync to our internal find(); any subsequent user mutation will diverge from this.
            expectedMod[0] = modCount;
            java.util.regex.MatchResult snapshot = toMatchResult();
            action.accept(snapshot);
            // Detect mutation by the consumer (e.g. reset() or find() called inside peek/forEach).
            if (modCount != expectedMod[0]) {
              throw new ConcurrentModificationException();
            }
            return true;
          }
        };
    return StreamSupport.stream(sp, false);
  }

  /**
   * Returns the MatchResult for the last match operation.
   *
   * <p>The returned snapshot is immutable: subsequent find/matches calls do not affect it.
   * Groups 0 through {@link #groupCount()} are accessible via {@code start(n)}, {@code end(n)},
   * and {@code group(n)}.
   *
   * <p>If no match has been attempted, the returned result reports {@code hasMatch() == false}
   * and all start/end/group accessors return {@code -1} or {@code null}. This mirrors the
   * behaviour of {@code java.util.regex.Matcher#toMatchResult()} in Java 20+.
   *
   * @return an immutable {@link java.util.regex.MatchResult}; never null
   */
  public java.util.regex.MatchResult toMatchResult() {
    // Capture an immutable snapshot. Works even when lastResult is null (no match attempted).
    final boolean matched = lastResult != null && lastResult.matches();
    final int snapStart = matched ? lastResult.start() : -1;
    final int snapEnd = matched ? lastResult.end() : -1;
    final String snapGroup0 = matched
        ? input.subSequence(lastResult.start(), lastResult.end()).toString()
        : null;
    final int count = groupCount();
    // Copy group spans and strings at snapshot time.
    final int[] snapGroupStarts = new int[count];
    final int[] snapGroupEnds = new int[count];
    final String[] snapGroups = new String[count];
    if (matched) {
      List<int[]> spans = lastResult.groupSpans();
      List<String> grps = lastResult.groups();
      for (int i = 0; i < count; i++) {
        if (i < spans.size()) {
          snapGroupStarts[i] = spans.get(i)[0];
          snapGroupEnds[i] = spans.get(i)[1];
        } else {
          snapGroupStarts[i] = -1;
          snapGroupEnds[i] = -1;
        }
        if (i < grps.size()) {
          snapGroups[i] = grps.get(i);
        }
      }
    }
    final Map<String, Integer> snapNamedGroups = namedGroups();
    // Capture the input string for groups outside the match region (lookbehind/lookahead).
    final String snapInput = input.toString();

    return new java.util.regex.MatchResult() {
      @Override
      public int start() {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        return snapStart;
      }

      @Override
      public int start(int group) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        if (group == 0) {
          return snapStart;
        }
        if (group < 1 || group > count) {
          throw new IndexOutOfBoundsException("Group index out of range: " + group);
        }
        return snapGroupStarts[group - 1];
      }

      @Override
      public int end() {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        return snapEnd;
      }

      @Override
      public int end(int group) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        if (group == 0) {
          return snapEnd;
        }
        if (group < 1 || group > count) {
          throw new IndexOutOfBoundsException("Group index out of range: " + group);
        }
        return snapGroupEnds[group - 1];
      }

      @Override
      public String group() {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        return snapGroup0;
      }

      @Override
      public String group(int group) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        if (group == 0) {
          return snapGroup0;
        }
        if (group < 1 || group > count) {
          throw new IndexOutOfBoundsException("Group index out of range: " + group);
        }
        return snapGroups[group - 1];
      }

      @Override
      public int groupCount() {
        return count;
      }

      @Override
      public boolean hasMatch() {
        return matched;
      }

      @Override
      public Map<String, Integer> namedGroups() {
        return snapNamedGroups;
      }

      @Override
      public int start(String name) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        Integer idx = snapNamedGroups.get(name);
        if (idx == null) {
          throw new IllegalArgumentException("No group with name <" + name + "> in pattern");
        }
        return start(idx);
      }

      @Override
      public int end(String name) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        Integer idx = snapNamedGroups.get(name);
        if (idx == null) {
          throw new IllegalArgumentException("No group with name <" + name + "> in pattern");
        }
        return end(idx);
      }

      @Override
      public String group(String name) {
        if (!matched) {
          throw new IllegalStateException("No match result");
        }
        Integer idx = snapNamedGroups.get(name);
        if (idx == null) {
          throw new IllegalArgumentException("No group with name <" + name + "> in pattern");
        }
        return group(idx);
      }

      @Override
      public String toString() {
        return snapGroup0 != null ? snapGroup0 : "";
      }
    };
  }

  /**
   * Processes a replacement string, expanding group references and interpreting escape
   * sequences, and returns the resulting string.
   *
   * <p>The following tokens are recognised:
   * <ul>
   *   <li>{@code \x} — the character {@code x} literally; a trailing {@code \} without a
   *       following character throws {@link IllegalArgumentException}.</li>
   *   <li>{@code $n} or {@code $nn...} — numeric group reference; back-off is applied when
   *       the accumulated number exceeds {@link #groupCount()} and the reference is
   *       multi-digit.</li>
   *   <li>{@code ${name}} — named group reference; throws {@link IllegalArgumentException}
   *       if the name is not in the pattern.</li>
   *   <li>Any other character — appended literally.</li>
   * </ul>
   *
   * <p>This method has no side effects on matcher state.
   *
   * @param replacement the replacement string to process; must not be null
   * @return the fully expanded replacement string; never null
   * @throws IllegalArgumentException  if the replacement string contains an illegal escape or
   *                                   group reference syntax
   * @throws IndexOutOfBoundsException if a numeric group reference exceeds {@link #groupCount()}
   *                                   after back-off
   */
  private String applyReplacement(String replacement) {
    StringBuilder result = new StringBuilder();
    int i = 0;
    int len = replacement.length();

    while (i < len) {
      char c = replacement.charAt(i);

      if (c == '\\') {
        i++;
        if (i >= len) {
          throw new IllegalArgumentException("character to be escaped is missing");
        }
        result.append(replacement.charAt(i));
        i++;

      } else if (c == '$') {
        i++;
        if (i >= len) {
          throw new IllegalArgumentException(
              "Illegal group reference: group index is missing");
        }
        char next = replacement.charAt(i);

        if (next == '{') {
          i++; // skip '{'
          int nameStart = i;
          while (i < len && replacement.charAt(i) != '}') {
            i++;
          }
          if (i >= len) {
            throw new IllegalArgumentException(
                "named capturing group is missing trailing '}'");
          }
          String name = replacement.substring(nameStart, i);
          i++; // skip '}'
          Integer groupIdx = namedGroups().get(name);
          if (groupIdx == null) {
            throw new IllegalArgumentException("No group with name <" + name + ">");
          }
          String groupValue = group(groupIdx);
          if (groupValue != null) {
            result.append(groupValue);
          }

        } else if (next >= '0' && next <= '9') {
          int refNum = 0;
          while (i < len && replacement.charAt(i) >= '0' && replacement.charAt(i) <= '9') {
            refNum = refNum * 10 + (replacement.charAt(i) - '0');
            i++;
          }
          // Back-off: only when multi-digit and still exceeds groupCount
          while (refNum > groupCount() && refNum >= 10) {
            refNum /= 10;
            i--;
          }
          if (refNum > groupCount()) {
            throw new IndexOutOfBoundsException("No group " + refNum);
          }
          String groupValue = group(refNum);
          if (groupValue != null) {
            result.append(groupValue);
          }

        } else {
          throw new IllegalArgumentException("Illegal group reference");
        }

      } else {
        result.append(c);
        i++;
      }
    }

    return result.toString();
  }

}
