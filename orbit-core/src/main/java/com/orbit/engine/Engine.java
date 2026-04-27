package com.orbit.engine;

import com.orbit.prog.MatchResult;
import com.orbit.prog.Prog;

/**
 * Engine interface for executing compiled programs.
 *
 * <p>The {@code anchoringBounds} and {@code transparentBounds} parameters implement the
 * semantics defined by {@link com.orbit.api.Matcher#useAnchoringBounds(boolean)} and
 * {@link com.orbit.api.Matcher#useTransparentBounds(boolean)}.
 *
 * <p>The {@code regionStart} parameter is the constant start of the matcher's region
 * (from {@link com.orbit.api.Matcher#regionStart()}). It must be distinguished from
 * {@code from}, which is the advancing search cursor and may be greater than
 * {@code regionStart} after zero-length matches.
 */
public interface Engine {

  /**
   * Executes the program against the input.
   *
   * @param prog              the compiled program; must not be null
   * @param input             the input string; must not be null
   * @param from              the starting search index for this call (inclusive); may be greater
   *                          than {@code regionStart} after zero-length matches
   * @param to                the ending search index (exclusive); equals {@code regionEnd}
   * @param lastMatchEnd      the end position of the previous successful match, for {@code \G}
   *                          support; {@code 0} if no previous match exists or for methods that
   *                          do a full reset (e.g. {@code matches()}, {@code lookingAt()})
   * @param anchoringBounds   when {@code true}, anchors ({@code ^}/{@code $}/{@code \A}/
   *                          {@code \z}) are evaluated relative to the region boundaries;
   *                          when {@code false}, they are evaluated against the full input
   * @param transparentBounds when {@code true}, lookahead and lookbehind can see characters
   *                          outside the region; when {@code false} (the default), they are
   *                          blocked at the region boundaries
   * @param regionStart       the constant inclusive start of the matcher's region; used by anchor
   *                          checks when {@code anchoringBounds} is {@code true}; equal to
   *                          {@code from} on the first find call but may differ on subsequent
   *                          calls after zero-length matches advance the cursor
   * @return the match result; never null
   */
  MatchResult execute(Prog prog, String input, int from, int to, int lastMatchEnd,
      boolean anchoringBounds, boolean transparentBounds, int regionStart);
}
