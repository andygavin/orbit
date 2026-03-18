package com.orbital.hir;

import com.orbital.parse.Expr;
import com.orbital.parse.Parser;
import com.orbital.hir.AnalysisVisitor;
import com.orbital.hir.HirNode;
import com.orbital.parse.Literal;
import com.orbital.parse.Union;
import com.orbital.parse.CharClass;
import com.orbital.parse.Pair;
import com.orbital.parse.Concat;
import com.orbital.parse.Quantifier;
import com.orbital.parse.Group;
import com.orbital.parse.Anchor;
import com.orbital.parse.Epsilon;
import com.orbital.parse.Backref;
import com.orbital.util.EngineHint;
import com.orbital.prefilter.Prefilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

/**
 * Tests for the AnalysisVisitor and HIR analysis passes.
 */
public class AnalysisVisitorTest {

    @Test
    public void testLiteralAnalysis() {
        // Test literal extraction
        Expr literal = new Literal("hello");
        HirNode hir = AnalysisVisitor.analyze(literal);

        assertEquals(HirNode.NodeType.LITERAL, hir.getType());
        assertNotNull(hir.getPrefix());
        assertTrue(hir.getPrefix().isExact());
        assertEquals("hello", hir.getPrefix().prefix());
        assertEquals("hello", hir.getSuffix().prefix());
    }

    @Test
    public void testCharClassAnalysis() {
        // Test char class (should not contribute to literals)
        Expr charClass = new CharClass(false, List.of(new CharRange('a', 'z')));
        HirNode hir = AnalysisVisitor.analyze(charClass);

        assertEquals(HirNode.NodeType.CHAR_CLASS, hir.getType());
        assertEquals(LiteralSet.EMPTY, hir.getPrefix());
        assertEquals(LiteralSet.EMPTY, hir.getSuffix());
    }

    @Test
    public void testPairAnalysis() {
        // Test pair (transducer) - input side should contribute literals
        Expr pair = new Pair(new Literal("test"), new Literal("output"));
        HirNode hir = AnalysisVisitor.analyze(pair);

        assertEquals(HirNode.NodeType.PAIR, hir.getType());
        assertNotNull(hir.getPrefix());
        assertEquals("test", hir.getPrefix().prefix());
        assertEquals("test", hir.getSuffix().prefix());
    }

    @Test
    public void testConcatAnalysis() {
        // Test concatenation
        Expr concat = new Concat(List.of(
            new Literal("pre"),
            new Literal("fix")
        ));
        HirNode hir = AnalysisVisitor.analyze(concat);

        assertEquals(HirNode.NodeType.CONCAT, hir.getType());
        assertEquals("pre", hir.getPrefix().prefix());
        assertEquals("fix", hir.getSuffix().prefix());
    }

    @Test
    public void testUnionAnalysis() {
        // Test union - should find common literals
        Expr union = new Union(List.of(
            new Literal("hello world"),
            new Literal("hello there")
        ));
        HirNode hir = AnalysisVisitor.analyze(union);

        assertEquals(HirNode.NodeType.UNION, hir.getType());
        assertEquals("hello ", hir.getPrefix().prefix());
        assertEquals("", hir.getSuffix().prefix()); // No common suffix
    }

    @Test
    public void testQuantifierAnalysis() {
        // Test quantifier with min=1
        Expr quantifier = new Quantifier(new Literal("test"), 1, null, false);
        HirNode hir = AnalysisVisitor.analyze(quantifier);

        assertEquals(HirNode.NodeType.QUANTIFIER, hir.getType());
        assertEquals("test", hir.getPrefix().prefix());
        assertEquals("test", hir.getSuffix().prefix());
    }

    @Test
    public void testGroupAnalysis() {
        // Test group
        Expr group = new Group(new Literal("group"), 0, null);
        HirNode hir = AnalysisVisitor.analyze(group);

        assertEquals(HirNode.NodeType.GROUP, hir.getType());
        assertEquals("group", hir.getPrefix().prefix());
        assertEquals("group", hir.getSuffix().prefix());
    }

    @Test
    public void testAnchorAnalysis() {
        // Test anchor
        Expr anchor = new Anchor(AnchorType.START);
        HirNode hir = AnalysisVisitor.analyze(anchor);

        assertEquals(HirNode.NodeType.ANCHOR, hir.getType());
        assertEquals(LiteralSet.EMPTY, hir.getPrefix());
        assertEquals(LiteralSet.EMPTY, hir.getSuffix());
    }

    @Test
    public void testEpsilonAnalysis() {
        // Test epsilon
        Expr epsilon = new Epsilon();
        HirNode hir = AnalysisVisitor.analyze(epsilon);

        assertEquals(HirNode.NodeType.EPSILON, hir.getType());
        assertEquals(LiteralSet.EMPTY, hir.getPrefix());
        assertEquals(LiteralSet.EMPTY, hir.getSuffix());
    }

    @Test
    public void testBackrefAnalysis() {
        // Test backreference
        Expr backref = new Backref(1);
        HirNode hir = AnalysisVisitor.analyze(backref);

        assertEquals(HirNode.NodeType.BACKREF, hir.getType());
        assertEquals(LiteralSet.EMPTY, hir.getPrefix());
        assertEquals(LiteralSet.EMPTY, hir.getSuffix());
    }

    @Test
    public void testOnePassSafety() {
        // Test one-pass safety analysis
        Expr simplePattern = new Concat(List.of(
            new Literal("test"),
            new CharClass(false, List.of(new CharRange('0', '9')))
        ));

        HirNode hir = AnalysisVisitor.analyze(simplePattern);
        assertTrue(hir.isOnePassSafe());
    }

    @Test
    public void testOutputAcyclicity() {
        // Test output acyclicity
        Expr boundedOutput = new Pair(
            new Literal("input"),
            new Literal("output")
        );

        HirNode hir = AnalysisVisitor.analyze(boundedOutput);
        assertTrue(hir.isOutputAcyclic());
        assertEquals(6, hir.getMaxOutputLengthPerInputChar()); // "output" length
    }

    @Test
    public void testEngineClassification() {
        // Test engine classification
        Expr simplePattern = new Literal("test");
        HirNode hir = AnalysisVisitor.analyze(simplePattern);

        assertEquals(EngineHint.DFA_SAFE, hir.getHint());
    }

    @Test
    public void testPrefilterBuilding() {
        // Test prefilter building
        Expr literalPattern = new Literal("test");
        HirNode hir = AnalysisVisitor.analyze(literalPattern);

        assertNotNull(hir.getPrefilter());
        assertFalse(hir.getPrefilter() instanceof Prefilter.NOOP);
    }

    @Test
    public void testComplexPatternAnalysis() {
        // Test complex pattern with multiple components
        Expr complex = new Concat(List.of(
            new Literal("start:"),
            new Union(List.of(
                new Literal("abc"),
                new Literal("def")
            )),
            new Quantifier(new CharClass(false, List.of(new CharRange('0', '9'))), 1, null, false)
        ));

        HirNode hir = AnalysisVisitor.analyze(complex);

        assertEquals(HirNode.NodeType.CONCAT, hir.getType());
        assertEquals("start:", hir.getPrefix().prefix());
        // Suffix should be the last part's suffix
        assertNotNull(hir.getSuffix());
        assertTrue(hir.isOnePassSafe());
        assertTrue(hir.isOutputAcyclic());
        assertEquals(EngineHint.DFA_SAFE, hir.getHint());
        assertNotNull(hir.getPrefilter());
    }

    @Test
    public void testEmptyPattern() {
        // Test empty pattern (epsilon)
        Expr empty = new Epsilon();
        HirNode hir = AnalysisVisitor.analyze(empty);

        assertEquals(HirNode.NodeType.EPSILON, hir.getType());
        assertEquals(LiteralSet.EMPTY, hir.getPrefix());
        assertEquals(LiteralSet.EMPTY, hir.getSuffix());
        assertTrue(hir.isOnePassSafe());
        assertTrue(hir.isOutputAcyclic());
        assertEquals(EngineHint.DFA_SAFE, hir.getHint());
        assertEquals(Prefilter.NOOP, hir.getPrefilter());
    }

    @Test
    public void testPatternSyntaxExceptionHandling() {
        // Test that AnalysisVisitor handles null gracefully
        assertThrows(NullPointerException.class, () -> {
            AnalysisVisitor.analyze(null);
        });
    }

    @Test
    public void testNestedStructures() {
        // Test nested structures
        Expr nested = new Group(new Concat(List.of(
            new Literal("outer"),
            new Group(new Literal("inner"), 1, "inner"),
            new Quantifier(new CharClass(false, List.of(new CharRange('a', 'z'))), 0, null, false)
        )), 0, null);

        HirNode hir = AnalysisVisitor.analyze(nested);
        assertEquals(HirNode.NodeType.GROUP, hir.getType());
        assertEquals("outer", hir.getPrefix().prefix());
        assertNotNull(hir.getPrefilter());
    }

    // Additional tests for existing functionality
    @Test
    void testSimpleLiteralAnalysis() throws Exception {
        Expr expr = Parser.parse("hello");
        HirNode hir = AnalysisVisitor.analyze(expr);

        assertEquals(HirNode.NodeType.LITERAL, hir.getType());
        assertNotNull(hir.getPrefix());
        assertTrue(hir.getPrefix().innerLiterals().isEmpty());
        assertEquals(com.orbital.util.EngineHint.DFA_SAFE, hir.getHint());
    }

    @Test
    void testUnionAnalysis() throws Exception {
        Expr expr = Parser.parse("a|b|c");
        HirNode hir = AnalysisVisitor.analyze(expr);

        assertEquals(HirNode.NodeType.UNION, hir.getType());
        assertNotNull(hir.getPrefix());
        assertTrue(hir.getPrefix().innerLiterals().size() >= 3);
    }

    @Test
    void testQuantifierAnalysis() throws Exception {
        Expr expr = Parser.parse("a+");
        HirNode hir = AnalysisVisitor.analyze(expr);

        assertEquals(HirNode.NodeType.QUANTIFIER, hir.getType());
        assertNotNull(hir.getPrefix());
    }

    @Test
    void testComplexPatternAnalysis() throws Exception {
        Expr expr = Parser.parse("a(b|c)*d");
        HirNode hir = AnalysisVisitor.analyze(expr);

        assertEquals(HirNode.NodeType.CONCAT, hir.getType());
        assertNotNull(hir.getPrefix());
    }

    @Test
    void testEmptyPatternAnalysis() throws Exception {
        Expr expr = Parser.parse("");
        HirNode hir = AnalysisVisitor.analyze(expr);

        assertEquals(HirNode.NodeType.EPSILON, hir.getType());
        assertEquals(com.orbital.util.EngineHint.DFA_SAFE, hir.getHint());
    }

    @Test
    void testAnchors() throws Exception {
        Expr expr = Parser.parse("^hello$");
        HirNode hir = AnalysisVisitor.analyze(expr);
        assertEquals(HirNode.NodeType.CONCAT, hir.getType());
    }

    @Test
    void testCharClassAnalysis() throws Exception {
        Expr expr = Parser.parse("[abc]");
        HirNode hir = AnalysisVisitor.analyze(expr);
        assertEquals(HirNode.NodeType.CHAR_CLASS, hir.getType());
        assertEquals(com.orbital.util.EngineHint.DFA_SAFE, hir.getHint());
    }

    @Test
    void testDotAnalysis() throws Exception {
        Expr expr = Parser.parse(".");
        HirNode hir = AnalysisVisitor.analyze(expr);
        assertEquals(HirNode.NodeType.CHAR_CLASS, hir.getType());
        assertEquals(com.orbital.util.EngineHint.DFA_SAFE, hir.getHint());
    }

    @Test
    void testNestedGroupsAnalysis() throws Exception {
        Expr expr = Parser.parse("(a(b)c)");
        HirNode hir = AnalysisVisitor.analyze(expr);
        assertEquals(HirNode.NodeType.GROUP, hir.getType());
    }
}