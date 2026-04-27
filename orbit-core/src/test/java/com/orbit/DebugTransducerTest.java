package com.orbit;

import com.orbit.api.Transducer;
import com.orbit.prog.Prog;
import com.orbit.prog.Instr;
import com.orbit.transducer.TransducerGraph;
import org.junit.jupiter.api.Test;

class DebugTransducerTest {
    @Test
    void debugAToB() {
        Transducer t = Transducer.compile("a:b");
        Prog prog = t.prog();
        System.out.println("=== Prog for a:b ===");
        System.out.println("startPc=" + prog.startPc + " acceptPc=" + prog.acceptPc);
        for (int i = 0; i < prog.instructions.length; i++) {
            System.out.println("  [" + i + "] " + prog.instructions[i]);
        }
        
        TransducerGraph g = TransducerGraph.fromProg(prog);
        System.out.println("\n=== fromProg graph ===");
        System.out.println("numStates=" + g.numStates + " startState=" + g.startState);
        for (int s = 0; s < g.numStates; s++) {
            System.out.println("  state " + s + " final=" + g.isFinal[s] + " arcs=" + g.outArcs.get(s));
        }
        
        TransducerGraph rm = g.rmEpsilon();
        System.out.println("\n=== after rmEpsilon ===");
        System.out.println("numStates=" + rm.numStates + " startState=" + rm.startState);
        for (int s = 0; s < rm.numStates; s++) {
            System.out.println("  state " + s + " final=" + rm.isFinal[s] + " arcs=" + rm.outArcs.get(s));
        }
        
        TransducerGraph inv = rm.invert();
        System.out.println("\n=== after invert ===");
        System.out.println("numStates=" + inv.numStates + " startState=" + inv.startState);
        for (int s = 0; s < inv.numStates; s++) {
            System.out.println("  state " + s + " final=" + inv.isFinal[s] + " arcs=" + inv.outArcs.get(s));
        }
        
        Prog invertedProg = inv.toProg();
        System.out.println("\n=== inverted Prog ===");
        System.out.println("startPc=" + invertedProg.startPc + " acceptPc=" + invertedProg.acceptPc);
        for (int i = 0; i < invertedProg.instructions.length; i++) {
            System.out.println("  [" + i + "] " + invertedProg.instructions[i]);
        }
        
        Transducer invT = t.invert();
        System.out.println("\napplyUp(b) = " + invT.tryApplyUp("b"));
    }
}
