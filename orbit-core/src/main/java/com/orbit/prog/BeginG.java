package com.orbit.prog;

/**
 * {@code \G} anchor assertion — position equals the end of the previous match.
 *
 * <p>Passes when the current position equals the {@code lastMatchEnd} value supplied by the
 * {@link com.orbit.api.Matcher}. On the first call (or after {@code reset()}),
 * {@code lastMatchEnd} is {@code 0}, so {@code \G} behaves like {@code ^} (matches only at
 * position 0).
 *
 * <p>Because the outcome of this instruction changes between successive {@code find()} calls,
 * patterns containing {@code BeginG} must not be routed to the lazy DFA engine. The
 * {@code LazyDfaEngine.hasDfaUnsafeInstructions} guard enforces this.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @param next the program counter of the next instruction on success
 */
public record BeginG(int next) implements Instr {}
