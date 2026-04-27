package com.orbit.prog;

import com.orbit.prefilter.NoopPrefilter;
import com.orbit.util.EngineHint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProgOptimiser#foldEpsilonChains}.
 *
 * <p>Each test constructs a minimal {@link Instr} array directly, calls
 * {@code foldEpsilonChains}, and asserts the expected folded state. No {@code Pattern.compile}
 * is used; all inputs are hand-built to keep the tests fast and isolated.
 */
class ProgOptimiserTest {

  // ---------------------------------------------------------------------------
  // Minimal Metadata fixture used wherever a Prog sub-body must be constructed.
  // ---------------------------------------------------------------------------

  private static final Metadata DUMMY_METADATA = new Metadata(
      EngineHint.DFA_SAFE,
      NoopPrefilter.INSTANCE,
      0,
      0,
      false,
      false,
      Map.of());

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * When no {@link EpsilonJump} instructions are present, the folded array must contain
   * records that are equal to the originals and the PCs must be unchanged.
   */
  @Test
  void noEpsilonJumps_arrayUnchanged() {
    // [CharMatch('a','a',1), Accept], startPc=0, acceptPc=1
    Instr[] instrs = {new CharMatch('a', 'a', 1), new Accept()};

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 1);

    assertThat(result.instructions()).hasSize(2);
    assertThat(result.instructions()[0]).isEqualTo(new CharMatch('a', 'a', 1));
    assertThat(result.instructions()[1]).isEqualTo(new Accept());
    assertThat(result.startPc()).isEqualTo(0);
    assertThat(result.acceptPc()).isEqualTo(1);
  }

  /**
   * A single {@link EpsilonJump} at index 1 must cause the predecessor at index 0 to have
   * its {@code next} rewritten to the epsilon target, bypassing the epsilon hop.
   */
  @Test
  void singleEpsilonJump_predecessor_rewritten() {
    // [CharMatch('a','a',1), EpsilonJump(2), Accept], startPc=0, acceptPc=2
    Instr[] instrs = {
        new CharMatch('a', 'a', 1),
        new EpsilonJump(2),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 2);

    assertThat(result.instructions()).hasSize(3);
    // CharMatch at index 0 must now point directly to Accept at index 2
    assertThat(result.instructions()[0]).isEqualTo(new CharMatch('a', 'a', 2));
    assertThat(result.startPc()).isEqualTo(0);
    assertThat(result.acceptPc()).isEqualTo(2);
  }

  /**
   * A chain of two consecutive {@link EpsilonJump} instructions: the first must be rewritten
   * to point directly to the terminal {@link Accept}, bypassing the intermediate epsilon.
   */
  @Test
  void chainOfTwo_collapses() {
    // [EpsilonJump(1), EpsilonJump(2), Accept], startPc=0, acceptPc=2
    Instr[] instrs = {
        new EpsilonJump(1),
        new EpsilonJump(2),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 2);

    assertThat(result.instructions()).hasSize(3);
    // folded[0] is EpsilonJump pointing to Accept (index 2), not to another EpsilonJump
    assertThat(result.instructions()[0]).isEqualTo(new EpsilonJump(2));
    // folded[1] is also resolved: EpsilonJump(2)
    assertThat(result.instructions()[1]).isEqualTo(new EpsilonJump(2));
  }

  /**
   * When {@code startPc} itself points at an {@link EpsilonJump}, the resolved
   * {@code startPc} in the result must skip to the first non-epsilon instruction.
   */
  @Test
  void startPc_resolved() {
    // [EpsilonJump(1), CharMatch('b','b',2), Accept], startPc=0, acceptPc=2
    Instr[] instrs = {
        new EpsilonJump(1),
        new CharMatch('b', 'b', 2),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 2);

    assertThat(result.startPc()).isEqualTo(1);
    assertThat(result.acceptPc()).isEqualTo(2);
  }

  /**
   * A self-referential {@link EpsilonJump} (cycle of length one) must be detected and
   * left pointing to itself — the fold must complete without infinite recursion.
   */
  @Test
  void selfLoopEpsilonJump_cycleHandled() {
    // [EpsilonJump(0), Accept], startPc=0, acceptPc=1
    Instr[] instrs = {
        new EpsilonJump(0),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 1);

    assertThat(result.instructions()).hasSize(2);
    // Cycle preserved: folded[0] still points to itself
    assertThat(result.instructions()[0]).isEqualTo(new EpsilonJump(0));
  }

  /**
   * A mutual cycle between two {@link EpsilonJump} instructions must be detected and
   * terminated without an infinite loop; neither instruction must point outside the cycle.
   */
  @Test
  void mutualCycle_cycleHandled() {
    // [EpsilonJump(1), EpsilonJump(0), Accept], startPc=0, acceptPc=2
    Instr[] instrs = {
        new EpsilonJump(1),
        new EpsilonJump(0),
        new Accept()
    };

    // Must complete without StackOverflowError or infinite loop
    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 2);

    assertThat(result.instructions()).hasSize(3);
    // Neither folded[0] nor folded[1] should point beyond the cycle pair
    int next0 = ((EpsilonJump) result.instructions()[0]).next();
    int next1 = ((EpsilonJump) result.instructions()[1]).next();
    assertThat(next0).isIn(0, 1);
    assertThat(next1).isIn(0, 1);
  }

  /**
   * A {@link Split} instruction must have both {@code next1} and {@code next2} resolved
   * through their respective {@link EpsilonJump} chains.
   */
  @Test
  void splitBothNextsFolded() {
    // [Split(1,3), EpsilonJump(2), CharMatch('a','a',4), EpsilonJump(4), Accept]
    // startPc=0, acceptPc=4
    Instr[] instrs = {
        new Split(1, 3),
        new EpsilonJump(2),
        new CharMatch('a', 'a', 4),
        new EpsilonJump(4),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 4);

    assertThat(result.instructions()).hasSize(5);
    assertThat(result.instructions()[0]).isEqualTo(new Split(2, 4));
  }

  /**
   * A {@link PossessiveSplit} instruction must have both {@code next1} and {@code next2}
   * resolved through their respective {@link EpsilonJump} chains.
   */
  @Test
  void possessiveSplitBothNextsFolded() {
    // [PossessiveSplit(1,3), EpsilonJump(2), CharMatch('a','a',4), EpsilonJump(4), Accept]
    // startPc=0, acceptPc=4
    Instr[] instrs = {
        new PossessiveSplit(1, 3),
        new EpsilonJump(2),
        new CharMatch('a', 'a', 4),
        new EpsilonJump(4),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 4);

    assertThat(result.instructions()).hasSize(5);
    assertThat(result.instructions()[0]).isEqualTo(new PossessiveSplit(2, 4));
  }

  /**
   * A {@link ConditionalBranchInstr} must have both {@code yesPC} and {@code noPC} resolved
   * through their respective {@link EpsilonJump} chains.
   */
  @Test
  void conditionalBranchBothPcsFolded() {
    // [ConditionalBranchInstr(GROUP_INDEX,1,null,null,1,3),
    //  EpsilonJump(2), Accept, EpsilonJump(2)]
    // startPc=0, acceptPc=2
    Instr[] instrs = {
        new ConditionalBranchInstr(
            ConditionalBranchInstr.Kind.GROUP_INDEX, 1, null, null, 1, 3),
        new EpsilonJump(2),
        new Accept(),
        new EpsilonJump(2)
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(instrs, 0, 2);

    assertThat(result.instructions()).hasSize(4);
    ConditionalBranchInstr folded0 =
        (ConditionalBranchInstr) result.instructions()[0];
    assertThat(folded0.yesPC()).isEqualTo(2);
    assertThat(folded0.noPC()).isEqualTo(2);
  }

  /**
   * A {@link Lookahead} instruction carrying an embedded sub-{@link Prog} that begins with
   * an {@link EpsilonJump} must have its body recursively folded so the sub-program's
   * {@code startPc} resolves to the first real instruction.
   */
  @Test
  void lookaheadBodyFolded() {
    // Sub-body: [EpsilonJump(1), Accept], startPc=0, acceptPc=1
    Instr[] bodyInstrs = {new EpsilonJump(1), new Accept()};
    Prog subProg = new Prog(bodyInstrs, DUMMY_METADATA, 0, 1);

    // Outer: [Lookahead(subProg, 2), EpsilonJump(2), Accept], startPc=0, acceptPc=2
    Instr[] outerInstrs = {
        new Lookahead(subProg, 1),
        new EpsilonJump(2),
        new Accept()
    };

    ProgOptimiser.FoldResult result = ProgOptimiser.foldEpsilonChains(outerInstrs, 0, 2);

    // outer folded[0] must have next resolved to 2 (Accept), not 1 (EpsilonJump)
    Lookahead foldedLookahead = (Lookahead) result.instructions()[0];
    assertThat(foldedLookahead.next()).isEqualTo(2);

    // The sub-body's startPc must be resolved to 1 (Accept), skipping the EpsilonJump at 0
    Prog foldedBody = foldedLookahead.body();
    assertThat(foldedBody.startPc).isEqualTo(1);
  }

  /**
   * Running {@link ProgOptimiser#foldEpsilonChains} twice on its own output must produce
   * an array that is field-by-field equal to the first pass result: the transformation is
   * idempotent.
   */
  @Test
  void idempotent() {
    // Non-trivial array: epsilon chain before a CharMatch
    Instr[] instrs = {
        new EpsilonJump(1),
        new EpsilonJump(2),
        new CharMatch('x', 'x', 3),
        new Accept()
    };

    ProgOptimiser.FoldResult first = ProgOptimiser.foldEpsilonChains(instrs, 0, 3);
    ProgOptimiser.FoldResult second =
        ProgOptimiser.foldEpsilonChains(first.instructions(), first.startPc(), first.acceptPc());

    assertThat(second.startPc()).isEqualTo(first.startPc());
    assertThat(second.acceptPc()).isEqualTo(first.acceptPc());
    assertThat(second.instructions()).hasSize(first.instructions().length);
    for (int i = 0; i < first.instructions().length; i++) {
      assertThat(second.instructions()[i])
          .as("instructions[%d] must be equal after second fold", i)
          .isEqualTo(first.instructions()[i]);
    }
  }

  /**
   * Calling {@link ProgOptimiser#foldEpsilonChains} must not mutate the original input array.
   */
  @Test
  void noMutationOfOriginalProg() {
    Instr[] original = {
        new CharMatch('a', 'a', 1),
        new EpsilonJump(2),
        new Accept()
    };
    // Snapshot the original state
    Instr originalAt0 = original[0];
    Instr originalAt1 = original[1];
    Instr originalAt2 = original[2];

    ProgOptimiser.foldEpsilonChains(original, 0, 2);

    assertThat(original[0]).isSameAs(originalAt0);
    assertThat(original[1]).isSameAs(originalAt1);
    assertThat(original[2]).isSameAs(originalAt2);
  }
}
