package com.orbit.prog;

/**
 * Mandatory-repetition loop.
 *
 * <p>Emitted by the compiler in place of unrolling when the mandatory minimum count exceeds
 * the unroll threshold. The body occupies instruction addresses {@code [bodyStart, bodyEnd)},
 * where the instruction at {@code bodyEnd - 1} is a {@link RepeatReturn}.
 *
 * <p>Engines that support this instruction (currently only {@code BoundedBacktrackEngine})
 * execute the body exactly {@code count} times. Patterns that contain this instruction are
 * always routed to {@code BoundedBacktrackEngine} via {@code EngineHint.NEEDS_BACKTRACKER}.
 *
 * @param bodyStart first instruction of the body (inclusive)
 * @param bodyEnd   first instruction after the body / {@link RepeatReturn} (exclusive)
 * @param count     number of mandatory repetitions; always &gt; 0
 */
public record RepeatMin(int bodyStart, int bodyEnd, int count) implements Instr {

    @Override
    public int next() {
        // The "next" of this instruction, in terms of linear instruction flow, is bodyEnd.
        return bodyEnd;
    }
}
