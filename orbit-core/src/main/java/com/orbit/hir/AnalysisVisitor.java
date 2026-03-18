package com.orbital.hir;

import com.orbital.parse.Expr;
import com.orbital.parse.CharClass;
import com.orbital.parse.Pair;
import com.orbital.parse.Concat;
import com.orbital.parse.Union;
import com.orbital.parse.Quantifier;
import com.orbital.parse.Group;
import com.orbital.parse.Anchor;
import com.orbital.parse.Epsilon;
import com.orbital.parse.Backref;
import com.orbital.util.EngineHint;
import com.orbital.prefilter.Prefilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analysis visitor that performs the five analysis passes.
 */
public class AnalysisVisitor {

    /**
     * Main entry point for analysis.
     */
    public static HirNode analyze(Expr expr) {
        if (expr == null) {
            throw new NullPointerException("Expression cannot be null");
        }

        // Pass 1: Build HIR tree
        HirNode hir = buildHir(expr);

        // Pass 2: Literal extraction
        analyzeLiterals(hir);

        // Pass 3: One-pass safety check
        analyzeOnePassSafety(hir);

        // Pass 4: Output acyclicity and bounded length
        analyzeOutputProperties(hir);

        // Pass 5: Engine classification and prefilter building
        analyzeEngineClassification(hir);
        buildPrefilter(hir);

        return hir;
    }

    // Pass 1: Build HIR tree
    private static HirNode buildHir(Expr expr) {
        if (expr instanceof Literal literal) {
            return new HirNode(HirNode.NodeType.LITERAL, List.of(), literal.span());
        } else if (expr instanceof CharClass charClass) {
            return new HirNode(HirNode.NodeType.CHAR_CLASS, List.of(), charClass.span());
        } else if (expr instanceof Pair pair) {
            HirNode input = buildHir(pair.input());
            HirNode output = buildHir(pair.output());
            return new HirNode(HirNode.NodeType.PAIR, List.of(input, output), pair.span());
        } else if (expr instanceof Concat concat) {
            List<HirNode> children = new ArrayList<>();
            for (Expr child : concat.parts()) {
                children.add(buildHir(child));
            }
            return new HirNode(HirNode.NodeType.CONCAT, children, concat.span());
        } else if (expr instanceof Union union) {
            List<HirNode> children = new ArrayList<>();
            for (Expr child : union.alternatives()) {
                children.add(buildHir(child));
            }
            return new HirNode(HirNode.NodeType.UNION, children, union.span());
        } else if (expr instanceof Quantifier quantifier) {
            HirNode child = buildHir(quantifier.child());
            return new HirNode(HirNode.NodeType.QUANTIFIER, List.of(child), quantifier.span());
        } else if (expr instanceof Group group) {
            HirNode body = buildHir(group.body());
            return new HirNode(HirNode.NodeType.GROUP, List.of(body), group.span());
        } else if (expr instanceof Anchor anchor) {
            return new HirNode(HirNode.NodeType.ANCHOR, List.of(), anchor.span());
        } else if (expr instanceof Epsilon epsilon) {
            return new HirNode(HirNode.NodeType.EPSILON, List.of(), epsilon.span());
        } else if (expr instanceof Backref backref) {
            return new HirNode(HirNode.NodeType.BACKREF, List.of(), backref.span());
        } else {
            throw new AssertionError("Unknown expression type: " + expr.getClass());
        }
    }

    // Pass 2: Literal extraction
    private static void analyzeLiterals(HirNode node) {
        switch (node.getType()) {
            case LITERAL:
                Literal literal = (Literal) node.getSpan().getSource();
                node.setPrefix(new LiteralSet(literal.value(), "", List.of(), true));
                node.setSuffix(node.getPrefix());
                break;

            case CHAR_CLASS:
                // Char classes don't contribute to literals
                break;

            case PAIR:
                // Input side only
                analyzeLiterals(node.getChildren().get(0));
                node.setPrefix(node.getChildren().get(0).getPrefix());
                node.setSuffix(node.getChildren().get(0).getSuffix());
                break;

            case CONCAT:
                // Combine children prefixes/suffixes
                if (node.getChildren().isEmpty()) {
                    node.setPrefix(LiteralSet.EMPTY);
                    node.setSuffix(LiteralSet.EMPTY);
                } else {
                    // Prefix is first child's prefix
                    node.setPrefix(node.getChildren().get(0).getPrefix());

                    // Suffix is last child's suffix
                    node.setSuffix(node.getChildren().get(node.getChildren().size() - 1).getSuffix());
                }
                break;

            case UNION:
                // Union of literals from all alternatives
                if (node.getChildren().isEmpty()) {
                    node.setPrefix(LiteralSet.EMPTY);
                    node.setSuffix(LiteralSet.EMPTY);
                } else {
                    List<String> innerLiterals = new ArrayList<>();
                    boolean allExact = true;
                    LiteralSet commonPrefix = null;
                    LiteralSet commonSuffix = null;

                    for (HirNode child : node.getChildren()) {
                        analyzeLiterals(child);
                        innerLiterals.addAll(child.getPrefix().innerLiterals());
                        innerLiterals.addAll(child.getSuffix().innerLiterals());

                        if (!child.getPrefix().isExact()) {
                            allExact = false;
                        }

                        if (commonPrefix == null) {
                            commonPrefix = child.getPrefix();
                            commonSuffix = child.getSuffix();
                        } else {
                            // Find common prefix/suffix
                            commonPrefix = findCommonPrefix(commonPrefix, child.getPrefix());
                            commonSuffix = findCommonSuffix(commonSuffix, child.getSuffix());
                        }
                    }

                    node.setPrefix(commonPrefix);
                    node.setSuffix(commonSuffix);
                }
                break;

            case QUANTIFIER:
                // Handle quantifiers specially
                analyzeLiterals(node.getChildren().get(0));
                if (node.getChildren().get(0).getPrefix().isExact()) {
                    // If min = 0, we can't guarantee prefix
                    node.setPrefix(LiteralSet.EMPTY);
                } else {
                    node.setPrefix(node.getChildren().get(0).getPrefix());
                }
                node.setSuffix(node.getChildren().get(0).getSuffix());
                break;

            case GROUP:
                analyzeLiterals(node.getChildren().get(0));
                node.setPrefix(node.getChildren().get(0).getPrefix());
                node.setSuffix(node.getChildren().get(0).getSuffix());
                break;

            case ANCHOR:
            case EPSILON:
            case BACKREF:
                node.setPrefix(LiteralSet.EMPTY);
                node.setSuffix(LiteralSet.EMPTY);
                break;
        }

        // Recurse to children
        for (HirNode child : node.getChildren()) {
            analyzeLiterals(child);
        }
    }

    // Helper methods for literal analysis
    private static LiteralSet findCommonPrefix(LiteralSet a, LiteralSet b) {
        if (a.isExact() && b.isExact()) {
            String common = findCommonStringPrefix(a.prefix(), b.prefix());
            return common.isEmpty() ? LiteralSet.EMPTY : new LiteralSet(common, "", List.of(), true);
        }
        return LiteralSet.EMPTY;
    }

    private static LiteralSet findCommonSuffix(LiteralSet a, LiteralSet b) {
        if (a.isExact() && b.isExact()) {
            String common = findCommonStringSuffix(a.suffix(), b.suffix());
            return common.isEmpty() ? LiteralSet.EMPTY : new LiteralSet("", common, List.of(), true);
        }
        return LiteralSet.EMPTY;
    }

    private static String findCommonStringPrefix(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int commonLen = 0;
        while (commonLen < minLen && a.charAt(commonLen) == b.charAt(commonLen)) {
            commonLen++;
        }
        return a.substring(0, commonLen);
    }

    private static String findCommonStringSuffix(String a, String b) {
        int aLen = a.length();
        int bLen = b.length();
        int commonLen = 0;
        while (commonLen < aLen && commonLen < bLen &&
               a.charAt(aLen - 1 - commonLen) == b.charAt(bLen - 1 - commonLen)) {
            commonLen++;
        }
        return a.substring(aLen - commonLen);
    }

    // Pass 3: One-pass safety check
    private static void analyzeOnePassSafety(HirNode node) {
        boolean isSafe = checkOnePassSafety(node);
        node.setOnePassSafe(isSafe);

        for (HirNode child : node.getChildren()) {
            analyzeOnePassSafety(child);
        }
    }

    private static boolean checkOnePassSafety(HirNode node) {
        switch (node.getType()) {
            case PAIR:
                // Check both input and output sides
                return checkOnePassSafety(node.getChildren().get(0)) &&
                       checkOnePassSafety(node.getChildren().get(1));

            case CONCAT:
            case UNION:
                for (HirNode child : node.getChildren()) {
                    if (!checkOnePassSafety(child)) {
                        return false;
                    }
                }
                return true;

            case QUANTIFIER:
                // Check if child writes any registers
                return node.getChildren().get(0).getType() != HirNode.NodeType.PAIR;

            default:
                return true;
        }
    }

    // Pass 4: Output acyclicity and bounded length
    private static void analyzeOutputProperties(HirNode node) {
        boolean isAcyclic = checkOutputAcyclicity(node);
        int maxOutputLength = calculateMaxOutputLength(node);

        node.setOutputAcyclic(isAcyclic);
        node.setMaxOutputLengthPerInputChar(maxOutputLength);

        for (HirNode child : node.getChildren()) {
            analyzeOutputProperties(child);
        }
    }

    private static boolean checkOutputAcyclicity(HirNode node) {
        switch (node.getType()) {
            case PAIR:
                // Check if output is acyclic
                return isOutputAcyclic(node.getChildren().get(1));

            case QUANTIFIER:
                // Check if unbounded quantifier on output
                if (node.getChildren().get(0).getType() == HirNode.NodeType.PAIR) {
                    // Check if output side of child is unbounded
                    return node.getChildren().get(0).getChildren().get(1).getMaxOutputLengthPerInputChar() == 0;
                }
                return true;

            default:
                return true;
        }
    }

    private static boolean isOutputAcyclic(HirNode node) {
        switch (node.getType()) {
            case LITERAL:
                return true;

            case PAIR:
                // Check if output has cycles
                return true;

            case CONCAT:
            case UNION:
                for (HirNode child : node.getChildren()) {
                    if (!isOutputAcyclic(child)) {
                        return false;
                    }
                }
                return true;

            case QUANTIFIER:
                // Check if unbounded repetition of output
                return node.getChildren().get(0).getMaxOutputLengthPerInputChar() == 0;

            default:
                return true;
        }
    }

    private static int calculateMaxOutputLength(HirNode node) {
        switch (node.getType()) {
            case PAIR:
                // Output side length
                return node.getChildren().get(1).getMaxOutputLengthPerInputChar();

            case CONCAT:
                return node.getChildren().stream()
                    .map(HirNode::getMaxOutputLengthPerInputChar)
                    .reduce(0, Math::addExact);

            case UNION:
                return node.getChildren().stream()
                    .map(HirNode::getMaxOutputLengthPerInputChar)
                    .max(Integer::compare)
                    .orElse(0);

            case QUANTIFIER:
                int childLength = node.getChildren().get(0).getMaxOutputLengthPerInputChar();
                // For bounded quantifiers, calculate total length
                // For unbounded, return 0 (will be checked separately)
                return childLength;

            default:
                return 0;
        }
    }

    // Pass 5: Engine classification
    private static void analyzeEngineClassification(HirNode node) {
        EngineHint hint = classifyEngine(node);
        node.setHint(hint);

        for (HirNode child : node.getChildren()) {
            analyzeEngineClassification(child);
        }
    }

    private static EngineHint classifyEngine(HirNode node) {
        switch (node.getType()) {
            case PAIR:
                // Transducers need special handling
                return EngineHint.PIKEVM_ONLY;

            case BACKREF:
                // Backreferences require backtracking
                return EngineHint.NEEDS_BACKTRACKER;

            case ANCHOR:
                // Anchors are DFA safe
                return EngineHint.DFA_SAFE;

            default:
                // For now, assume DFA_SAFE for all other nodes
                return EngineHint.DFA_SAFE;
        }
    }

    // Build prefilter
    private static void buildPrefilter(HirNode node) {
        if (node.getPrefix().getTotalLiteralCount() > 0) {
            // Build appropriate prefilter based on literal count
            int literalCount = node.getPrefix().getTotalLiteralCount();
            if (literalCount <= 10) {
                node.setPrefilter(new VectorLiteralPrefilter(node.getPrefix()));
            } else if (literalCount <= 500) {
                node.setPrefilter(new AhoCorasickPrefilter(node.getPrefix()));
            } else {
                node.setPrefilter(new AhoCorasickPrefilter(node.getPrefix(), true));
            }
        } else {
            node.setPrefilter(Prefilter.NOOP);
        }

        for (HirNode child : node.getChildren()) {
            buildPrefilter(child);
        }
    }
}