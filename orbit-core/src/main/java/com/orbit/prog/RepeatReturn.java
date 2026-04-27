package com.orbit.prog;

/**
 * Body-terminator for a {@link RepeatMin} mandatory-repetition loop.
 *
 * <p>Marks the end of a repeat body. When {@code BoundedBacktrackEngine} calls {@code rec()}
 * for one iteration of the body, hitting this instruction causes {@code rec()} to return the
 * current input position, returning control to the {@link RepeatMin} loop.
 *
 * <p>This instruction is always immediately followed in the program by the first instruction
 * of the continuation (i.e., the instruction at {@link RepeatMin#bodyEnd()}).
 */
public record RepeatReturn() implements Instr {

    @Override
    public int next() {
        throw new UnsupportedOperationException("RepeatReturn has no next instruction");
    }
}
