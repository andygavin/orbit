package com.orbit.prog;

/**
 * Sealed instruction hierarchy for compiled programs.
 */
public sealed interface Instr permits
    CharMatch, Split, UnionSplit, PossessiveSplit, AtomicCommit, SaveCapture, TransOutput, Accept,
    Fail, EpsilonJump, Lookahead, LookaheadNeg,
    LookbehindPos, LookbehindNeg,
    BackrefCheck, WordBoundary, ByteMatch, ByteRangeMatch,
    AnyChar, AnyByte, BeginText, EndText, BeginLine, EndLine,
    EndZ, BeginG,
    BalancePushInstr, BalancePopInstr, BalanceCheckInstr, ConditionalBranchInstr,
    RepeatMin, RepeatReturn, ResetMatchStart {

    int next();
}