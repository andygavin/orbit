package com.orbit.prog;

import java.util.Objects;

/**
 * Post-compile optimisation passes on a {@link Prog} instruction array.
 *
 * <p>Currently implements OPT-A: EpsilonJump chain folding. Every PC field in every
 * instruction is rewritten so that it skips over chains of {@link EpsilonJump} instructions
 * and points directly at the first non-epsilon instruction in that chain. The result is
 * semantically equivalent to the input but eliminates the dispatch overhead of pure epsilon
 * hops during execution.
 *
 * <p>Instances are immutable and safe for concurrent use from multiple threads.
 */
public final class ProgOptimiser {

  private ProgOptimiser() {}

  /**
   * Result of an epsilon-chain fold: a new instruction array and the resolved start and
   * accept PCs.
   *
   * <p>Instances are immutable and safe for use by multiple concurrent threads.
   *
   * @param instructions the folded instruction array; never null
   * @param startPc      the resolved entry program counter
   * @param acceptPc     the resolved accept program counter
   */
  public record FoldResult(Instr[] instructions, int startPc, int acceptPc) {
    /** Creates a {@code FoldResult}. */
    public FoldResult {
      Objects.requireNonNull(instructions, "instructions must not be null");
    }
  }

  /**
   * Folds all epsilon-jump chains in the given instruction array.
   *
   * <p>For every PC field in every instruction, follows {@link EpsilonJump} chains to their
   * non-epsilon target and replaces the field with that target PC. The result is semantically
   * equivalent to the input: every execution path that previously passed through one or more
   * {@link EpsilonJump} instructions now jumps directly to the first non-epsilon instruction
   * in that chain.
   *
   * <p>Cycles in the {@link EpsilonJump} graph are detected per-chain and terminated at the
   * cycle head; the engine's existing invalid-PC guard ({@code pc < 0 || pc >= length})
   * ensures safe handling of degenerate cycle programs.
   *
   * <p>Sub-programs embedded in {@link Lookahead}, {@link LookaheadNeg}, {@link LookbehindPos},
   * {@link LookbehindNeg}, and {@link ConditionalBranchInstr} are folded recursively.
   *
   * @param instructions the instruction array from a compiled {@link Prog}; must not be null
   * @param startPc      the entry program counter
   * @param acceptPc     the accept program counter
   * @return a {@link FoldResult} with the folded array and resolved PCs; never null
   * @throws NullPointerException if {@code instructions} is null
   */
  public static FoldResult foldEpsilonChains(
      Instr[] instructions, int startPc, int acceptPc) {
    Objects.requireNonNull(instructions, "instructions must not be null");
    Instr[] folded = foldInstructions(instructions);
    int resolvedStart = resolve(folded, startPc);
    int resolvedAccept = resolve(folded, acceptPc);
    return new FoldResult(folded, resolvedStart, resolvedAccept);
  }

  /**
   * Produces a new instruction array of the same length where every PC field in every
   * instruction has been resolved through any {@link EpsilonJump} chain.
   *
   * @param instructions the source instruction array; must not be null
   * @return a new array with all PC fields resolved; never null
   */
  private static Instr[] foldInstructions(Instr[] instructions) {
    int n = instructions.length;
    Instr[] folded = new Instr[n];
    for (int i = 0; i < n; i++) {
      folded[i] = rewrite(instructions, instructions[i]);
    }
    return folded;
  }

  /**
   * Follows {@link EpsilonJump} links starting at {@code pc} until the chain terminates at a
   * non-epsilon instruction, an out-of-bounds PC, or a cycle.
   *
   * <p>Cycle detection uses a per-call {@code boolean[]} so the function is stateless and
   * re-entrant.
   *
   * @param instructions the instruction array to traverse; must not be null
   * @param pc           the starting program counter
   * @return the PC of the first non-epsilon instruction in the chain; returns {@code pc} if
   *         it is out-of-bounds or part of a cycle
   */
  private static int resolve(Instr[] instructions, int pc) {
    boolean[] visited = new boolean[instructions.length];
    int current = pc;
    while (true) {
      if (current < 0 || current >= instructions.length) {
        return current; // out-of-bounds: engine guard handles it
      }
      if (visited[current]) {
        return current; // cycle detected: return cycle head
      }
      Instr instr = instructions[current];
      if (!(instr instanceof EpsilonJump ej)) {
        return current; // non-epsilon target: done
      }
      visited[current] = true;
      current = ej.next();
    }
  }

  /**
   * Rewrites a single instruction, replacing every PC field with its resolved value.
   *
   * <p>For instructions that embed a sub-{@link Prog} body ({@link Lookahead},
   * {@link LookaheadNeg}, {@link LookbehindPos}, {@link LookbehindNeg},
   * {@link ConditionalBranchInstr}), the body is folded recursively via
   * {@link #foldProg(Prog)}.
   *
   * @param instructions the source instruction array used for PC resolution
   * @param instr        the instruction to rewrite; must not be null
   * @return a new instruction with all PC fields resolved, or {@code instr} unchanged for
   *         terminal instructions ({@link Accept}, {@link Fail}); never null
   */
  private static Instr rewrite(Instr[] instructions, Instr instr) {
    return switch (instr) {
      case EpsilonJump ej ->
          new EpsilonJump(resolve(instructions, ej.next()));

      case Split s ->
          new Split(
              resolve(instructions, s.next1()),
              resolve(instructions, s.next2()));

      case UnionSplit us ->
          new UnionSplit(
              resolve(instructions, us.next1()),
              resolve(instructions, us.next2()));

      case PossessiveSplit ps ->
          new PossessiveSplit(
              resolve(instructions, ps.next1()),
              resolve(instructions, ps.next2()));

      case ConditionalBranchInstr cb ->
          new ConditionalBranchInstr(
              cb.kind(),
              cb.refIndex(),
              cb.refName(),
              cb.lookaheadBody() == null ? null : foldProg(cb.lookaheadBody()),
              resolve(instructions, cb.yesPC()),
              resolve(instructions, cb.noPC()));

      case Lookahead la ->
          new Lookahead(foldProg(la.body()), resolve(instructions, la.next()));

      case LookaheadNeg lan ->
          new LookaheadNeg(foldProg(lan.body()), resolve(instructions, lan.next()));

      case LookbehindPos lbp ->
          new LookbehindPos(
              foldProg(lbp.body()), lbp.minLen(), lbp.maxLen(), resolve(instructions, lbp.next()));

      case LookbehindNeg lbn ->
          new LookbehindNeg(
              foldProg(lbn.body()), lbn.minLen(), lbn.maxLen(), resolve(instructions, lbn.next()));

      case CharMatch cm ->
          new CharMatch(cm.lo(), cm.hi(), resolve(instructions, cm.next()));

      case AnyChar ac ->
          new AnyChar(resolve(instructions, ac.next()));

      case AnyByte ab ->
          new AnyByte(resolve(instructions, ab.next()));

      case ByteMatch bm ->
          new ByteMatch(bm.value(), resolve(instructions, bm.next()));

      case ByteRangeMatch brm ->
          new ByteRangeMatch(brm.lo(), brm.hi(), resolve(instructions, brm.next()));

      case BeginText bt ->
          new BeginText(resolve(instructions, bt.next()));

      case EndText et ->
          new EndText(resolve(instructions, et.next()));

      case BeginLine bl ->
          new BeginLine(resolve(instructions, bl.next()), bl.unixLines(), bl.perlNewlines());

      case EndLine el ->
          new EndLine(resolve(instructions, el.next()), el.multiline(), el.unixLines(),
              el.perlNewlines());

      case EndZ ez ->
          new EndZ(resolve(instructions, ez.next()), ez.unixLines(), ez.perlNewlines());

      case BeginG bg ->
          new BeginG(resolve(instructions, bg.next()));

      case WordBoundary wb ->
          new WordBoundary(resolve(instructions, wb.next()), wb.negated(), wb.unicodeCase());

      case SaveCapture sc ->
          new SaveCapture(sc.groupIndex(), sc.isStart(), resolve(instructions, sc.next()));

      case BackrefCheck bc ->
          new BackrefCheck(bc.groupIndex(), bc.caseInsensitive(), resolve(instructions, bc.next()));

      case TransOutput to ->
          new TransOutput(to.delta(), resolve(instructions, to.next()));

      case AtomicCommit ac ->
          new AtomicCommit(resolve(instructions, ac.next()), ac.loopCommit());

      case BalancePushInstr bp ->
          new BalancePushInstr(bp.name(), resolve(instructions, bp.next()));

      case BalancePopInstr bpop ->
          new BalancePopInstr(bpop.name(), resolve(instructions, bpop.next()));

      case BalanceCheckInstr bci ->
          new BalanceCheckInstr(bci.name(), resolve(instructions, bci.next()));

      case ResetMatchStart rms ->
          new ResetMatchStart(resolve(instructions, rms.next()));

      case Accept a -> a;
      case Fail f -> f;

      case RepeatMin rm ->
          new RepeatMin(
              resolve(instructions, rm.bodyStart()),
              resolve(instructions, rm.bodyEnd()),
              rm.count());

      case RepeatReturn rr -> rr;
    };
  }

  /**
   * Recursively folds the epsilon-jump chains within an embedded sub-{@link Prog}.
   *
   * <p>The sub-program's own {@code startPc} and {@code acceptPc} are resolved through the
   * folded instruction array, so the engine enters the sub-program at the first real
   * instruction rather than an epsilon-jump.
   *
   * @param subProg the sub-program to fold; returns {@code subProg} unchanged if null or empty
   * @return a new {@link Prog} with folded instructions and resolved PCs, or {@code subProg}
   *         if it was null or contained no instructions
   */
  private static Prog foldProg(Prog subProg) {
    if (subProg == null || subProg.getInstructionCount() == 0) {
      return subProg;
    }
    Instr[] foldedInstrs = foldInstructions(subProg.instructions);
    int resolvedStart = resolve(foldedInstrs, subProg.startPc);
    int resolvedAccept = resolve(foldedInstrs, subProg.acceptPc);
    return new Prog(foldedInstrs, subProg.metadata, resolvedStart, resolvedAccept);
  }
}
