package com.orbital.prog;

/**
 * Compiled program - immutable instruction array.
 */
public final class Prog {

    public final Instr[] instructions;
    public final Metadata metadata;
    public final int startPc;
    public final int acceptPc;

    public Prog(Instr[] instructions, Metadata metadata, int startPc, int acceptPc) {
        this.instructions = instructions.clone(); // Defensive copy
        this.metadata = metadata;
        this.startPc = startPc;
        this.acceptPc = acceptPc;
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