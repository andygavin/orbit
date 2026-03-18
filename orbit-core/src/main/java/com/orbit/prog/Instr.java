package com.orbital.prog;

/**
 * Sealed instruction hierarchy for compiled programs.
 */
public sealed interface Instr permits
    CharMatch, Split, SaveCapture, TransOutput, Accept,
    Fail, EpsilonJump, Lookahead, LookaheadNeg,
    BackrefCheck, WordBoundary, ByteMatch, ByteRangeMatch,
    AnyChar, AnyByte, BeginText, EndText, BeginLine, EndLine {

    int next();
}