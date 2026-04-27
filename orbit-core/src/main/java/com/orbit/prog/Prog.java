package com.orbit.prog;

import com.orbit.engine.dfa.PrecomputedDfa;

/**
 * Compiled program - immutable instruction array.
 *
 * <p>After construction, {@link #precomputedDfa} may be set once by {@code Pattern} when the
 * pattern is classified as {@code ONE_PASS_SAFE} and the NFA fits within the DFA state limit.
 * It is {@code null} otherwise. All other fields are immutable from construction.
 */
public final class Prog {

    public final Instr[] instructions;
    public final Metadata metadata;
    public final int startPc;
    public final int acceptPc;

    /**
     * Precomputed flat-array DFA for {@code ONE_PASS_SAFE} patterns.
     *
     * <p>Set by {@code Pattern} after compilation if the NFA expands within the state limit;
     * {@code null} if the pattern is not one-pass safe or the DFA exceeded the state limit.
     * The {@code OnePassDfaEngine} reads this field; other engines ignore it.
     */
    public volatile PrecomputedDfa precomputedDfa;

    public Prog(Instr[] instructions, Metadata metadata, int startPc, int acceptPc) {
        this.instructions = instructions.clone(); // Defensive copy
        this.metadata = metadata;
        this.startPc = startPc;
        this.acceptPc = acceptPc;
        this.precomputedDfa = null;
    }

    /**
     * Returns the instruction at the given program counter.
     */
    public Instr getInstruction(int pc) {
        return instructions[pc];
    }

    /**
     * Returns the total number of instructions.
     */
    public int getInstructionCount() {
        return instructions.length;
    }
}