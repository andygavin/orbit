package com.orbital.engine.engines;

import com.orbital.prog.MatchResult;
import com.orbital.prog.Prog;
import com.orbital.prog.Instr;

/**
 * One-pass DFA engine for patterns that are one-pass safe.
 */
public class OnePassDfaEngine implements Engine {

    @Override
    public MatchResult execute(Prog prog, String input, int from, int to) {
        // Simple implementation for now
        if (from >= to || input == null) {
            return new MatchResult(false, -1, -1, new java.util.ArrayList<>(), null);
        }

        // For one-pass safe patterns, we can use a simple DFA approach
        Instr firstInstr = prog.getInstruction(0);
        if (firstInstr instanceof Instr.CharMatch) {
            char lo = ((Instr.CharMatch) firstInstr).lo();
            char hi = ((Instr.CharMatch) firstInstr).hi();
            char c = input.charAt(from);
            if (c >= lo && c <= hi) {
                return new MatchResult(true, from, from + 1, new java.util.ArrayList<>(), null);
            }
        }

        return new MatchResult(false, -1, -1, new java.util.ArrayList<>(), null);
    }
}