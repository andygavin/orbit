package com.orbit.parse;

/**
 * AST node representing the {@code \K} keep assertion.
 *
 * <p>Resets the reported match start to the current input position. Input consumed before
 * this assertion is used to anchor the match but is excluded from the reported result
 * ({@code group(0)}, {@code start()}, {@code end()}).
 *
 * <p>{@code \K} is illegal inside a character class; the parser throws
 * {@link PatternSyntaxException} if one is encountered there.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public record KeepAssertion() implements Expr {}
