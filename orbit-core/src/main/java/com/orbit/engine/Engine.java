package com.orbital.engine;

import com.orbital.prog.MatchResult;
import com.orbital.prog.Prog;

/**
 * Engine interface for executing compiled programs.
 */
public interface Engine {

    /**
     * Executes the program against the input.
     */
    MatchResult execute(Prog prog, String input, int from, int to);
}