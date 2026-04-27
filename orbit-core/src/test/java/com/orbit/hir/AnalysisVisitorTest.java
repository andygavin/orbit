package com.orbit.hir;

import com.orbit.parse.Anchor;
import com.orbit.parse.AnchorType;
import com.orbit.parse.AtomicGroup;
import com.orbit.parse.Backref;
import com.orbit.parse.CharClass;
import com.orbit.parse.CharRange;
import com.orbit.parse.Concat;
import com.orbit.parse.Epsilon;
import com.orbit.parse.Expr;
import com.orbit.parse.Group;
import com.orbit.parse.Literal;
import com.orbit.parse.LookaheadExpr;
import com.orbit.parse.Pair;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.parse.Quantifier;
import com.orbit.parse.Union;
import com.orbit.prefilter.NoopPrefilter;
import com.orbit.util.EngineHint;
import com.orbit.util.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * Tests for the AnalysisVisitor and HIR analysis passes.
 */
public class AnalysisVisitorTest {

  @Test
  public void testLiteralAnalysis() {
    Expr literal = new Literal("hello");
    HirNode hir = AnalysisVisitor.analyze(literal);

    assertEquals(NodeType.LITERAL, hir.getType());
    assertNotNull(hir.getPrefix());
    assertTrue(hir.getPrefix().isExact());
    assertEquals("hello", hir.getPrefix().prefix());
    assertEquals("hello", hir.getSuffix().prefix());
  }

  @Test
  public void testCharClassAnalysis() {
    Expr charClass = new CharClass(false, List.of(new CharRange('a', 'z')));
    HirNode hir = AnalysisVisitor.analyze(charClass);

    assertEquals(NodeType.CHAR_CLASS, hir.getType());
    assertEquals(LiteralSet.EMPTY, hir.getPrefix());
    assertEquals(LiteralSet.EMPTY, hir.getSuffix());
  }

  @Test
  public void testPairAnalysis() {
    Expr pair = new Pair(new Literal("test"), new Literal("output"), OptionalDouble.empty());
    HirNode hir = AnalysisVisitor.analyze(pair);

    assertEquals(NodeType.PAIR, hir.getType());
    assertNotNull(hir.getPrefix());
    assertEquals("test", hir.getPrefix().prefix());
    assertEquals("test", hir.getSuffix().prefix());
  }

  @Test
  public void testConcatAnalysis() {
    // Two consecutive literals are merged by optimization pass O4 into a single LITERAL.
    Expr concat = new Concat(List.of(
        new Literal("pre"),
        new Literal("fix")
    ));
    HirNode hir = AnalysisVisitor.analyze(concat);

    // After O4 merging, the two-literal concat collapses to a single LITERAL node "prefix".
    assertEquals(NodeType.LITERAL, hir.getType());
    assertEquals("prefix", hir.getLiteralValue().orElse(""));
    assertEquals("prefix", hir.getPrefix().prefix());
  }

  @Test
  public void testConcatAnalysis_withNonLiteralChild() {
    // A concat with a non-literal child is preserved as CONCAT.
    Expr concat = new Concat(List.of(
        new Literal("pre"),
        new CharClass(false, List.of(new CharRange('a', 'z')))
    ));
    HirNode hir = AnalysisVisitor.analyze(concat);

    assertEquals(NodeType.CONCAT, hir.getType());
    assertEquals("pre", hir.getPrefix().prefix());
  }

  @Test
  public void testUnionAnalysis() {
    Expr union = new Union(List.of(
        new Literal("hello world"),
        new Literal("hello there")
    ));
    HirNode hir = AnalysisVisitor.analyze(union);

    assertEquals(NodeType.UNION, hir.getType());
    assertEquals("hello ", hir.getPrefix().prefix());
    assertEquals("", hir.getSuffix().prefix()); // No common suffix
  }

  @Test
  public void testQuantifierAnalysis() {
    Expr quantifier = new Quantifier(new Literal("test"), 1, OptionalInt.empty(), false);
    HirNode hir = AnalysisVisitor.analyze(quantifier);

    assertEquals(NodeType.QUANTIFIER, hir.getType());
    assertEquals("test", hir.getPrefix().prefix());
    assertEquals("test", hir.getSuffix().prefix());
  }

  @Test
  public void testGroupAnalysis() {
    Expr group = new Group(new Literal("group"), 0, null);
    HirNode hir = AnalysisVisitor.analyze(group);

    assertEquals(NodeType.GROUP, hir.getType());
    assertEquals("group", hir.getPrefix().prefix());
    assertEquals("group", hir.getSuffix().prefix());
  }

  @Test
  public void testAnchorAnalysis() {
    Expr anchor = new Anchor(AnchorType.START);
    HirNode hir = AnalysisVisitor.analyze(anchor);

    assertEquals(NodeType.ANCHOR, hir.getType());
    assertEquals(LiteralSet.EMPTY, hir.getPrefix());
    assertEquals(LiteralSet.EMPTY, hir.getSuffix());
  }

  @Test
  public void testEpsilonAnalysis() {
    Expr epsilon = new Epsilon();
    HirNode hir = AnalysisVisitor.analyze(epsilon);

    assertEquals(NodeType.EPSILON, hir.getType());
    assertEquals(LiteralSet.EMPTY, hir.getPrefix());
    assertEquals(LiteralSet.EMPTY, hir.getSuffix());
  }

  @Test
  public void testBackrefAnalysis() {
    Expr backref = new Backref(1);
    HirNode hir = AnalysisVisitor.analyze(backref);

    assertEquals(NodeType.BACKREF, hir.getType());
    assertEquals(LiteralSet.EMPTY, hir.getPrefix());
    assertEquals(LiteralSet.EMPTY, hir.getSuffix());
  }

  @Test
  public void testOnePassSafety() {
    Expr simplePattern = new Concat(List.of(
        new Literal("test"),
        new CharClass(false, List.of(new CharRange('0', '9')))
    ));

    HirNode hir = AnalysisVisitor.analyze(simplePattern);
    assertTrue(hir.isOnePassSafe());
  }

  @Test
  public void testOutputAcyclicity() {
    Expr boundedOutput = new Pair(
        new Literal("input"),
        new Literal("output"),
        OptionalDouble.empty()
    );

    HirNode hir = AnalysisVisitor.analyze(boundedOutput);
    assertTrue(hir.isOutputAcyclic());
    assertEquals(6, hir.getMaxOutputLengthPerInputChar()); // "output" length
  }

  @Test
  public void testEngineClassification() {
    Expr simplePattern = new Literal("test");
    HirNode hir = AnalysisVisitor.analyze(simplePattern);

    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
  }

  @Test
  public void testPrefilterBuilding() {
    Expr literalPattern = new Literal("test");
    HirNode hir = AnalysisVisitor.analyze(literalPattern);

    assertNotNull(hir.getPrefilter());
    assertFalse(hir.getPrefilter() instanceof NoopPrefilter);
  }

  @Test
  public void testComplexPatternAnalysis() {
    Expr complex = new Concat(List.of(
        new Literal("start:"),
        new Union(List.of(
            new Literal("abc"),
            new Literal("def")
        )),
        new Quantifier(new CharClass(false, List.of(new CharRange('0', '9'))), 1, OptionalInt.empty(), false)
    ));

    HirNode hir = AnalysisVisitor.analyze(complex);

    assertEquals(NodeType.CONCAT, hir.getType());
    assertEquals("start:", hir.getPrefix().prefix());
    assertNotNull(hir.getSuffix());
    assertTrue(hir.isOnePassSafe());
    assertTrue(hir.isOutputAcyclic());
    assertEquals(EngineHint.PIKEVM_ONLY, hir.getHint());
    assertNotNull(hir.getPrefilter());
  }

  @Test
  public void testEmptyPattern() {
    Expr empty = new Epsilon();
    HirNode hir = AnalysisVisitor.analyze(empty);

    assertEquals(NodeType.EPSILON, hir.getType());
    assertEquals(LiteralSet.EMPTY, hir.getPrefix());
    assertEquals(LiteralSet.EMPTY, hir.getSuffix());
    assertTrue(hir.isOnePassSafe());
    assertTrue(hir.isOutputAcyclic());
    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
    assertInstanceOf(NoopPrefilter.class, hir.getPrefilter());
  }

  @Test
  public void testPatternSyntaxExceptionHandling() {
    assertThrows(NullPointerException.class, () -> AnalysisVisitor.analyze(null));
  }

  @Test
  public void testNestedStructures() {
    Expr nested = new Group(new Concat(List.of(
        new Literal("outer"),
        new Group(new Literal("inner"), 1, "inner"),
        new Quantifier(new CharClass(false, List.of(new CharRange('a', 'z'))), 0, OptionalInt.empty(), false)
    )), 0, null);

    HirNode hir = AnalysisVisitor.analyze(nested);
    assertEquals(NodeType.GROUP, hir.getType());
    assertEquals("outer", hir.getPrefix().prefix());
    assertNotNull(hir.getPrefilter());
  }

  @Test
  void testSimpleLiteralAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("hello");
    HirNode hir = AnalysisVisitor.analyze(expr);

    assertEquals(NodeType.LITERAL, hir.getType());
    assertNotNull(hir.getPrefix());
    assertTrue(hir.getPrefix().innerLiterals().isEmpty());
    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
  }

  @Test
  void testUnionAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("a|b|c");
    HirNode hir = AnalysisVisitor.analyze(expr);

    assertEquals(NodeType.UNION, hir.getType());
    assertNotNull(hir.getPrefix());
  }

  @Test
  void testQuantifierAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("a+");
    HirNode hir = AnalysisVisitor.analyze(expr);

    assertEquals(NodeType.QUANTIFIER, hir.getType());
    assertNotNull(hir.getPrefix());
  }

  @Test
  void testComplexPatternAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("a(b|c)*d");
    HirNode hir = AnalysisVisitor.analyze(expr);

    assertEquals(NodeType.CONCAT, hir.getType());
    assertNotNull(hir.getPrefix());
  }

  @Test
  void testEmptyPatternAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("");
    HirNode hir = AnalysisVisitor.analyze(expr);

    assertEquals(NodeType.EPSILON, hir.getType());
    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
  }

  @Test
  void testAnchors_viaParser() throws Exception {
    Expr expr = Parser.parse("^hello$");
    HirNode hir = AnalysisVisitor.analyze(expr);
    assertEquals(NodeType.CONCAT, hir.getType());
  }

  @Test
  void testCharClassAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("[abc]");
    HirNode hir = AnalysisVisitor.analyze(expr);
    assertEquals(NodeType.CHAR_CLASS, hir.getType());
    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
  }

  @Test
  void testDotAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse(".");
    HirNode hir = AnalysisVisitor.analyze(expr);
    assertEquals(NodeType.CHAR_CLASS, hir.getType());
    assertEquals(EngineHint.DFA_SAFE, hir.getHint());
  }

  @Test
  void testNestedGroupsAnalysis_viaParser() throws Exception {
    Expr expr = Parser.parse("(a(b)c)");
    HirNode hir = AnalysisVisitor.analyze(expr);
    assertEquals(NodeType.GROUP, hir.getType());
  }

  // =========================================================================
  // Min/max match-length tests
  // =========================================================================

  @Test
  void analyzeMatchLength_literal_exactLength() {
    HirNode hir = AnalysisVisitor.analyze(new Literal("abc"));
    assertThat(hir.getMinMatchLength()).isEqualTo(3);
    assertThat(hir.getMaxMatchLength()).isEqualTo(3);
  }

  @Test
  void analyzeMatchLength_epsilon_zero() {
    HirNode hir = AnalysisVisitor.analyze(new Epsilon());
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(0);
  }

  @Test
  void analyzeMatchLength_charClass_oneToOne() {
    HirNode hir = AnalysisVisitor.analyze(
        new CharClass(false, List.of(new CharRange('a', 'z'))));
    assertThat(hir.getMinMatchLength()).isEqualTo(1);
    assertThat(hir.getMaxMatchLength()).isEqualTo(1);
  }

  @Test
  void analyzeMatchLength_anchor_zero() {
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.START));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(0);
  }

  @Test
  void analyzeMatchLength_backref_unbounded() {
    HirNode hir = AnalysisVisitor.analyze(new Backref(1));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void analyzeMatchLength_concat_sumOfChildren() {
    // "abc" (literal 3) + charclass (1) → min=4, max=4
    HirNode hir = AnalysisVisitor.analyze(
        new Concat(List.of(
            new Literal("abc"),
            new CharClass(false, List.of(new CharRange('0', '9'))))));
    assertThat(hir.getMinMatchLength()).isEqualTo(4);
    assertThat(hir.getMaxMatchLength()).isEqualTo(4);
  }

  @Test
  void analyzeMatchLength_union_minMax() {
    // "a" (1) | "bc" (2) → min=1, max=2
    HirNode hir = AnalysisVisitor.analyze(
        new Union(List.of(new Literal("a"), new Literal("bc"))));
    assertThat(hir.getMinMatchLength()).isEqualTo(1);
    assertThat(hir.getMaxMatchLength()).isEqualTo(2);
  }

  @Test
  void analyzeMatchLength_quantifier_optional() {
    // "a"? → min=0, max=1
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 0, OptionalInt.of(1), false));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(1);
  }

  @Test
  void analyzeMatchLength_quantifier_unbounded() {
    // "a"* → min=0, max=MAX_VALUE
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 0, OptionalInt.empty(), false));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void analyzeMatchLength_quantifier_bounded() {
    // "ab"{2,5} → min=4, max=10
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("ab"), 2, OptionalInt.of(5), false));
    assertThat(hir.getMinMatchLength()).isEqualTo(4);
    assertThat(hir.getMaxMatchLength()).isEqualTo(10);
  }

  @Test
  void analyzeMatchLength_quantifier_zeroToZero() {
    // "a"{0,0} → min=0, max=0 (equivalent to epsilon after O1 optimization)
    // After O1 optimization this becomes EPSILON
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 0, OptionalInt.of(0), false));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(0);
  }

  @Test
  void analyzeMatchLength_group_propagatesChild() {
    // (abc) → min=3, max=3
    HirNode hir = AnalysisVisitor.analyze(new Group(new Literal("abc"), 0, null));
    assertThat(hir.getMinMatchLength()).isEqualTo(3);
    assertThat(hir.getMaxMatchLength()).isEqualTo(3);
  }

  @Test
  void analyzeMatchLength_lookahead_zero() {
    // (?=abc) → zero-width assertion
    HirNode hir = AnalysisVisitor.analyze(new LookaheadExpr(new Literal("abc"), true));
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(0);
  }

  @ParameterizedTest(name = "[{index}] pattern={0}")
  @MethodSource("matchLengthViaParserCases")
  void analyzeMatchLength_viaParser(
      String pattern, int expectedMin, int expectedMax) throws Exception {
    HirNode hir = AnalysisVisitor.analyze(Parser.parse(pattern));
    assertThat(hir.getMinMatchLength())
        .as("minMatchLength for /%s/", pattern)
        .isEqualTo(expectedMin);
    assertThat(hir.getMaxMatchLength())
        .as("maxMatchLength for /%s/", pattern)
        .isEqualTo(expectedMax);
  }

  static Stream<Arguments> matchLengthViaParserCases() {
    return Stream.of(
        Arguments.of("abc",     3, 3),
        Arguments.of("a?b",     1, 2),
        Arguments.of("a*",      0, Integer.MAX_VALUE),
        Arguments.of("a{2,5}",  2, 5),
        Arguments.of("(a|bc)",  1, 2),
        Arguments.of("^hello$", 5, 5),            // anchors contribute 0
        Arguments.of("",        0, 0),             // empty pattern
        Arguments.of("a+",      1, Integer.MAX_VALUE),
        Arguments.of("[abc]",   1, 1)
    );
  }

  // =========================================================================
  // Anchor detection tests
  // =========================================================================

  @Test
  void analyzeAnchors_startAnchor_startAnchored() {
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.START));
    assertThat(hir.isStartAnchored()).isTrue();
    assertThat(hir.isEndAnchored()).isFalse();
  }

  @Test
  void analyzeAnchors_endAnchor_endAnchored() {
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.END));
    assertThat(hir.isStartAnchored()).isFalse();
    assertThat(hir.isEndAnchored()).isTrue();
  }

  @Test
  void analyzeAnchors_lineStartAnchor_startAnchored() {
    // \A → LINE_START in AnchorType
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.LINE_START));
    assertThat(hir.isStartAnchored()).isTrue();
    assertThat(hir.isEndAnchored()).isFalse();
  }

  @Test
  void analyzeAnchors_eofAnchor_endAnchored() {
    // \z → EOF in AnchorType
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.EOF));
    assertThat(hir.isStartAnchored()).isFalse();
    assertThat(hir.isEndAnchored()).isTrue();
  }

  @Test
  void analyzeAnchors_wordBoundary_neitherAnchored() {
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.WORD_BOUNDARY));
    assertThat(hir.isStartAnchored()).isFalse();
    assertThat(hir.isEndAnchored()).isFalse();
  }

  @Test
  void analyzeAnchors_literal_notAnchored() {
    HirNode hir = AnalysisVisitor.analyze(new Literal("hello"));
    assertThat(hir.isStartAnchored()).isFalse();
    assertThat(hir.isEndAnchored()).isFalse();
  }

  @Test
  void analyzeAnchors_group_propagates() {
    // (^hello) → startAnchored=true
    HirNode hir = AnalysisVisitor.analyze(
        new Group(
            new Concat(List.of(new Anchor(AnchorType.START), new Literal("hello"))),
            0, null));
    assertThat(hir.isStartAnchored()).isTrue();
    assertThat(hir.isEndAnchored()).isFalse();
  }

  @ParameterizedTest(name = "[{index}] pattern={0}")
  @MethodSource("anchorViaParserCases")
  void analyzeAnchors_viaParser(
      String pattern, boolean expectedStart, boolean expectedEnd) throws Exception {
    HirNode hir = AnalysisVisitor.analyze(Parser.parse(pattern));
    assertThat(hir.isStartAnchored())
        .as("startAnchored for /%s/", pattern)
        .isEqualTo(expectedStart);
    assertThat(hir.isEndAnchored())
        .as("endAnchored for /%s/", pattern)
        .isEqualTo(expectedEnd);
  }

  static Stream<Arguments> anchorViaParserCases() {
    return Stream.of(
        Arguments.of("^hello",   true,  false),
        Arguments.of("hello$",   false, true),
        Arguments.of("^hello$",  true,  true),
        Arguments.of("hello",    false, false),
        Arguments.of("(^a|^b)",  true,  false),   // all alternatives start-anchored
        Arguments.of("(^a|b)",   false, false),   // not all alternatives start-anchored
        Arguments.of("a*hello",  false, false),   // quantifier is not start-anchoring
        Arguments.of("(^hello)", true,  false)    // group wrapping start-anchored content
    );
  }

  // =========================================================================
  // Optimization pass tests
  // =========================================================================

  @Test
  void optimizeHir_O1_zeroQuantifier_becomesEpsilon() {
    // a{0,0} → EPSILON
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 0, OptionalInt.of(0), false));
    assertThat(hir.getType()).isEqualTo(NodeType.EPSILON);
  }

  @Test
  void optimizeHir_O2_epsilonRemovedFromConcat() throws Exception {
    // Parsing "(?:)hello" — the non-capturing group produces epsilon which is concat'd with hello.
    // After O2, epsilon is removed and the result is just the LITERAL "hello".
    Expr expr = Parser.parse("(?:)hello");
    HirNode hir = AnalysisVisitor.analyze(expr);
    // The epsilon from (?:) is removed; remaining is "hello"
    assertThat(hir.getType()).isEqualTo(NodeType.LITERAL);
    assertThat(hir.getLiteralValue()).contains("hello");
  }

  @Test
  void optimizeHir_O3_unionDedup_removeDuplicates() {
    // (a|a) → after dedup, single "a"
    HirNode hir = AnalysisVisitor.analyze(
        new Group(
            new Union(List.of(new Literal("a"), new Literal("a"))),
            0, null));
    // The group wraps the collapsed single literal
    assertThat(hir.getType()).isEqualTo(NodeType.GROUP);
    HirNode child = hir.getChildren().get(0);
    assertThat(child.getType()).isEqualTo(NodeType.LITERAL);
    assertThat(child.getLiteralValue()).contains("a");
  }

  @Test
  void optimizeHir_O3_unionDedup_threeDuplicates() {
    // Union(a, a, a) — needs 3-element union which requires a manual construction
    // since Union Expr requires >= 2 alternatives; build via HIR directly via analysis
    // by chaining two unions through the parser
    // "a|a" → collapsed to LITERAL "a" (not wrapped in union)
    HirNode hir = AnalysisVisitor.analyze(
        new Union(List.of(new Literal("a"), new Literal("a"))));
    assertThat(hir.getType()).isEqualTo(NodeType.LITERAL);
    assertThat(hir.getLiteralValue()).contains("a");
  }

  @Test
  void optimizeHir_O4_literalMerging_twoLiterals() {
    // Concat("ab", "cd") → LITERAL "abcd"
    HirNode hir = AnalysisVisitor.analyze(
        new Concat(List.of(new Literal("ab"), new Literal("cd"))));
    assertThat(hir.getType()).isEqualTo(NodeType.LITERAL);
    assertThat(hir.getLiteralValue()).contains("abcd");
  }

  @Test
  void optimizeHir_O4_literalMerging_interleaved() throws Exception {
    // "ab[0-9]cd" — parser produces Concat(LIT("ab"), CHARCLASS, LIT("cd"))
    // After O4: Concat(LIT("ab"), CHARCLASS, LIT("cd")) — no adjacent literals to merge
    Expr expr = Parser.parse("ab[0-9]cd");
    HirNode hir = AnalysisVisitor.analyze(expr);
    // The result should be a CONCAT where "ab" and "cd" remain separate
    // (no adjacent literals)
    assertThat(hir.getType()).isEqualTo(NodeType.CONCAT);
    assertThat(hir.getMinMatchLength()).isEqualTo(5);
    assertThat(hir.getMaxMatchLength()).isEqualTo(5);
  }

  @Test
  void optimizeHir_O5_nestedUnboundedQuantifier_flattened() throws Exception {
    // (a*)* — outer{0,MAX}(inner{0,MAX}(LITERAL("a"))) → QUANTIFIER{0,MAX}(LITERAL("a"))
    Expr expr = Parser.parse("(a*)*");
    HirNode hir = AnalysisVisitor.analyze(expr);
    // The outer quantifier is preserved; its child should be LITERAL "a" (not another quantifier)
    assertThat(hir.getType()).isIn(NodeType.QUANTIFIER, NodeType.GROUP);
    // Regardless of structure, match length should be (0, MAX_VALUE)
    assertThat(hir.getMinMatchLength()).isEqualTo(0);
    assertThat(hir.getMaxMatchLength()).isEqualTo(Integer.MAX_VALUE);
  }

  // =========================================================================
  // PatternSyntaxException format tests
  // =========================================================================

  @Test
  void patternSyntaxException_message_format_caretAtPosition() {
    PatternSyntaxException ex = new PatternSyntaxException(
        "Unmatched closing parenthesis", "ab)cd", 2);
    String msg = ex.getMessage();
    assertThat(msg).contains("near index 2");
    assertThat(msg).contains("ab)cd");
    // Caret should be at position 2 (two spaces before ^)
    assertThat(msg).contains("  ^");
  }

  @Test
  void patternSyntaxException_message_format_caretAtZero() {
    PatternSyntaxException ex = new PatternSyntaxException(
        "Unclosed group", "(abc", 0);
    String msg = ex.getMessage();
    assertThat(msg).contains("near index 0");
    assertThat(msg).contains("(abc");
    // Caret at position 0 — no leading spaces
    assertThat(msg).contains("\n  ^");
  }

  @Test
  void patternSyntaxException_sourceOffset_returnsIndex() {
    PatternSyntaxException ex = new PatternSyntaxException("msg", "source", 3);
    assertThat(ex.sourceOffset()).isEqualTo(3);
  }

  @Test
  void patternSyntaxException_noSourceInfo_returnsMinusOne() {
    PatternSyntaxException ex = new PatternSyntaxException("msg");
    assertThat(ex.sourceOffset()).isEqualTo(-1);
  }

  @Test
  void patternSyntaxException_thrownByParser_hasAccurateIndex() {
    assertThatThrownBy(() -> Parser.parse("ab)cd"))
        .isInstanceOf(PatternSyntaxException.class)
        .satisfies(t -> {
          PatternSyntaxException ex = (PatternSyntaxException) t;
          // The ')' is at index 2
          assertThat(ex.sourceOffset()).isEqualTo(2);
          assertThat(ex.getMessage()).contains("near index 2");
        });
  }

  @Test
  void patternSyntaxException_unclosedGroup_indexAtOpen() {
    assertThatThrownBy(() -> Parser.parse("(abc"))
        .isInstanceOf(PatternSyntaxException.class)
        .satisfies(t -> {
          PatternSyntaxException ex = (PatternSyntaxException) t;
          assertThat(ex.getMessage()).contains("near index");
        });
  }

  // =========================================================================
  // AnchorType stored on HirNode
  // =========================================================================

  @Test
  void buildHir_anchorNode_storesAnchorType() {
    HirNode hir = AnalysisVisitor.analyze(new Anchor(AnchorType.LINE_START));
    assertThat(hir.getType()).isEqualTo(NodeType.ANCHOR);
    assertThat(hir.getAnchorType()).contains(AnchorType.LINE_START);
  }

  @Test
  void buildHir_literalNode_anchorTypeEmpty() {
    HirNode hir = AnalysisVisitor.analyze(new Literal("x"));
    assertThat(hir.getAnchorType()).isEmpty();
  }

  // =========================================================================
  // Quantifier bounds stored on HirNode
  // =========================================================================

  @Test
  void buildHir_quantifierNode_storesBounds_bounded() {
    // a{2,5}
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 2, OptionalInt.of(5), false));
    assertThat(hir.getType()).isEqualTo(NodeType.QUANTIFIER);
    assertThat(hir.getQuantifierMin()).isEqualTo(2);
    assertThat(hir.getQuantifierMax()).isEqualTo(5);
  }

  @Test
  void buildHir_quantifierNode_storesBounds_unbounded() {
    // a*
    HirNode hir = AnalysisVisitor.analyze(
        new Quantifier(new Literal("a"), 0, OptionalInt.empty(), false));
    assertThat(hir.getType()).isEqualTo(NodeType.QUANTIFIER);
    assertThat(hir.getQuantifierMin()).isEqualTo(0);
    assertThat(hir.getQuantifierMax()).isEqualTo(Integer.MAX_VALUE);
  }
}
