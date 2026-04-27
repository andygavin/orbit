package com.orbit.hir;

import com.orbit.parse.Anchor;
import com.orbit.parse.AnchorType;
import com.orbit.parse.AtomicGroup;
import com.orbit.parse.BalanceGroupExpr;
import com.orbit.parse.Backref;
import com.orbit.parse.CharClass;
import com.orbit.parse.Concat;
import com.orbit.parse.ConditionalExpr;
import com.orbit.parse.Epsilon;
import com.orbit.parse.Expr;
import com.orbit.parse.FlagExpr;
import com.orbit.parse.Group;
import com.orbit.parse.LookbehindExpr;
import com.orbit.parse.LookaheadExpr;
import com.orbit.parse.Literal;
import com.orbit.parse.Pair;
import com.orbit.parse.Quantifier;
import com.orbit.parse.Union;
import com.orbit.prefilter.AhoCorasickPrefilter;
import com.orbit.prefilter.LiteralIndexOfPrefilter;
import com.orbit.prefilter.NoopPrefilter;
import com.orbit.prefilter.Prefilter;
import com.orbit.prefilter.VectorLiteralPrefilter;
import com.orbit.util.EngineHint;
import com.orbit.util.NodeType;
import com.orbit.util.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Performs the full sequence of analysis passes over an {@link Expr} tree, producing an
 * annotated {@link HirNode} tree ready for engine selection and prefilter construction.
 *
 * <p>Pass ordering:
 * <ol>
 *   <li>Pass 1 — {@code buildHir}: lower {@link Expr} to {@link HirNode}.</li>
 *   <li>Pass 2 — {@code optimizeHir}: dead-code elimination and quantifier rewriting.</li>
 *   <li>Pass 3 — {@code analyzeLiterals}: extract prefix/suffix literal sets.</li>
 *   <li>Pass 4 — {@code analyzeMatchLength}: compute min/max match-length bounds.</li>
 *   <li>Pass 5 — {@code analyzeAnchors}: detect start/end anchoring.</li>
 *   <li>Pass 6 — {@code analyzeOnePassSafety}: one-pass safety check.</li>
 *   <li>Pass 7 — {@code analyzeOutputProperties}: output acyclicity and bounded length.</li>
 *   <li>Pass 8 — {@code analyzeEngineClassification}: engine hint selection.</li>
 *   <li>Pass 9 — {@code buildPrefilter}: attach prefilters to the root.</li>
 * </ol>
 *
 * <p>Instances of this class are stateless; all methods are static.
 *
 * <p>This class is <em>not</em> intended for subclassing.
 */
public class AnalysisVisitor {

  /**
   * Quantifier minimum threshold above which the compiler emits a {@code RepeatMin}
   * instruction instead of unrolling.  Must match the constant in {@code Pattern}.
   */
  private static final int LARGE_QUANTIFIER_UNROLL_THRESHOLD = 1_000;

  /**
   * Analyzes the given expression tree and returns a fully annotated {@link HirNode} tree.
   *
   * @param expr the expression to analyze; must not be null
   * @return the annotated HIR root node; never null
   * @throws NullPointerException if {@code expr} is null
   */
  public static HirNode analyze(Expr expr) {
    Objects.requireNonNull(expr, "expr must not be null");

    // Pass 1: Build HIR tree
    HirNode hir = buildHir(expr);

    // Pass 2: Optimization (dead-code elimination, literal merging, etc.)
    hir = optimizeHir(hir);

    // Pass 3: Literal extraction
    analyzeLiterals(hir);

    // Pass 4: Min/max match length
    analyzeMatchLength(hir);

    // Pass 5: Anchor position detection
    analyzeAnchors(hir);

    // Pass 6: One-pass safety check
    analyzeOnePassSafety(hir);

    // Pass 7: Output acyclicity and bounded length
    analyzeOutputProperties(hir);

    // Pass 8: Engine classification and prefilter building
    analyzeEngineClassification(hir);
    buildPrefilter(hir);

    return hir;
  }

  // -------------------------------------------------------------------------
  // Pass 1: Build HIR tree
  // -------------------------------------------------------------------------

  private static HirNode buildHir(Expr expr) {
    if (expr instanceof Literal literal) {
      return new HirNode(NodeType.LITERAL, List.of(), literal.span(), literal.value());
    } else if (expr instanceof CharClass charClass) {
      return new HirNode(NodeType.CHAR_CLASS, List.of(), charClass.span());
    } else if (expr instanceof Pair pair) {
      HirNode input = buildHir(pair.input());
      HirNode output = buildHir(pair.output());
      return new HirNode(NodeType.PAIR, List.of(input, output), pair.span());
    } else if (expr instanceof Concat concat) {
      List<HirNode> children = new ArrayList<>();
      for (Expr child : concat.parts()) {
        children.add(buildHir(child));
      }
      return new HirNode(NodeType.CONCAT, children, concat.span());
    } else if (expr instanceof Union union) {
      List<HirNode> children = new ArrayList<>();
      for (Expr child : union.alternatives()) {
        children.add(buildHir(child));
      }
      return new HirNode(NodeType.UNION, children, union.span());
    } else if (expr instanceof Quantifier quantifier) {
      HirNode child = buildHir(quantifier.child());
      NodeType qType = quantifier.possessive()
          ? NodeType.POSSESSIVE_QUANTIFIER
          : (quantifier.lazy() ? NodeType.LAZY_QUANTIFIER : NodeType.QUANTIFIER);
      HirNode node = new HirNode(qType, List.of(child), quantifier.span());
      node.setQuantifierMin(quantifier.min());
      node.setQuantifierMax(quantifier.max().orElse(Integer.MAX_VALUE));
      return node;
    } else if (expr instanceof Group group) {
      HirNode body = buildHir(group.body());
      return new HirNode(NodeType.GROUP, List.of(body), group.span());
    } else if (expr instanceof Anchor anchor) {
      HirNode node = new HirNode(NodeType.ANCHOR, List.of(), anchor.span());
      node.setAnchorType(anchor.type());
      return node;
    } else if (expr instanceof Epsilon epsilon) {
      return new HirNode(NodeType.EPSILON, List.of(), epsilon.span());
    } else if (expr instanceof Backref backref) {
      return new HirNode(NodeType.BACKREF, List.of(), backref.span());
    } else if (expr instanceof LookaheadExpr la) {
      HirNode body = buildHir(la.body());
      NodeType type = la.positive() ? NodeType.LOOKAHEAD : NodeType.LOOKAHEAD_NEG;
      return new HirNode(type, List.of(body), la.span());
    } else if (expr instanceof LookbehindExpr lb) {
      HirNode body = buildHir(lb.body());
      NodeType type = lb.positive() ? NodeType.LOOKBEHIND : NodeType.LOOKBEHIND_NEG;
      return new HirNode(type, List.of(body), lb.span());
    } else if (expr instanceof FlagExpr fe) {
      HirNode body = buildHir(fe.body());
      return new HirNode(NodeType.FLAG_EXPR, List.of(body), fe.span());
    } else if (expr instanceof AtomicGroup ag) {
      HirNode body = buildHir(ag.body());
      return new HirNode(NodeType.ATOMIC_GROUP, List.of(body), ag.span());
    } else if (expr instanceof BalanceGroupExpr bg) {
      HirNode body = buildHir(bg.body());
      return new HirNode(NodeType.BALANCE_GROUP, List.of(body), bg.span());
    } else if (expr instanceof ConditionalExpr cond) {
      HirNode yes = buildHir(cond.yes());
      HirNode noAlt = buildHir(cond.noAlt());
      return new HirNode(NodeType.CONDITIONAL, List.of(yes, noAlt), cond.span());
    } else if (expr instanceof com.orbit.parse.KeepAssertion ka) {
      return new HirNode(NodeType.KEEP_ASSERTION, List.of(), ka.span());
    } else {
      throw new AssertionError("Unknown expression type: " + expr.getClass());
    }
  }

  // -------------------------------------------------------------------------
  // Pass 2: HIR optimization
  // -------------------------------------------------------------------------

  /**
   * Rewrites the subtree rooted at {@code node} using rules O1–O5 and returns the
   * (possibly replaced) root node. Applies rules bottom-up in a single traversal.
   *
   * <p>Rules applied:
   * <ul>
   *   <li>O1 — Zero-width quantifier ({@code min==0 && max==0}) → {@code EPSILON}.</li>
   *   <li>O2 — Epsilon children removed from {@code CONCAT} nodes; single-child concat
   *       collapsed to its child.</li>
   *   <li>O3 — Structurally duplicate children removed from {@code UNION} nodes;
   *       single-child union collapsed.</li>
   *   <li>O4 — Consecutive {@code LITERAL} children in a {@code CONCAT} merged into one.</li>
   *   <li>O5 — Nested identical unbounded quantifiers flattened: {@code (a*)*} → {@code a*}.</li>
   * </ul>
   *
   * @param node the root of the subtree to optimize; must not be null
   * @return the (possibly replaced) root node; never null
   */
  private static HirNode optimizeHir(HirNode node) {
    // Bottom-up: optimize children first.
    List<HirNode> optimizedChildren = new ArrayList<>(node.getChildren().size());
    for (HirNode child : node.getChildren()) {
      optimizedChildren.add(optimizeHir(child));
    }
    node.replaceChildren(optimizedChildren);

    // O1 — Zero-width quantifier elimination.
    if (isQuantifierType(node.getType())
        && node.getQuantifierMin() == 0
        && node.getQuantifierMax() == 0) {
      return new HirNode(NodeType.EPSILON, List.of(), node.getSpan());
    }

    // O2 — Epsilon elimination from CONCAT.
    if (node.getType() == NodeType.CONCAT) {
      node = applyEpsilonElimination(node);
      // After O2 the node may have been replaced; fall through to O4.
    }

    // O3 — Union deduplication.
    if (node.getType() == NodeType.UNION) {
      node = applyUnionDeduplication(node);
    }

    // O4 — Literal concatenation merging (only valid if node is still a CONCAT).
    if (node.getType() == NodeType.CONCAT) {
      node = applyLiteralMerging(node);
    }

    // O5 — Nested identical unbounded quantifier flattening.
    if (isUnboundedQuantifierType(node.getType())) {
      node = applyQuantifierFlattening(node);
    }

    return node;
  }

  /**
   * Returns {@code true} if {@code type} is one of the three quantifier node types.
   *
   * @param type the node type to test; must not be null
   * @return {@code true} for quantifier types
   */
  private static boolean isQuantifierType(NodeType type) {
    return type == NodeType.QUANTIFIER
        || type == NodeType.LAZY_QUANTIFIER
        || type == NodeType.POSSESSIVE_QUANTIFIER;
  }

  /**
   * Returns {@code true} if {@code type} is a greedy or lazy (but not possessive) quantifier
   * node type with unbounded semantics. Used for Rule O5 applicability.
   *
   * @param type the node type to test; must not be null
   * @return {@code true} for greedy or lazy quantifier types
   */
  private static boolean isUnboundedQuantifierType(NodeType type) {
    return type == NodeType.QUANTIFIER || type == NodeType.LAZY_QUANTIFIER;
  }

  /**
   * Applies Rule O2 to a {@code CONCAT} node: removes {@code EPSILON} children and
   * collapses single-child and empty results.
   *
   * @param node a {@code CONCAT} node; must not be null
   * @return the rewritten node; may be a different instance if collapsed
   */
  private static HirNode applyEpsilonElimination(HirNode node) {
    List<HirNode> children = node.getChildren();
    boolean hasEpsilon = children.stream()
        .anyMatch(c -> c.getType() == NodeType.EPSILON);
    if (!hasEpsilon) {
      return node;
    }
    List<HirNode> filtered = new ArrayList<>(children.size());
    for (HirNode child : children) {
      if (child.getType() != NodeType.EPSILON) {
        filtered.add(child);
      }
    }
    if (filtered.isEmpty()) {
      return new HirNode(NodeType.EPSILON, List.of(), node.getSpan());
    }
    if (filtered.size() == 1) {
      return filtered.get(0);
    }
    node.replaceChildren(filtered);
    return node;
  }

  /**
   * Applies Rule O3 to a {@code UNION} node: removes structurally duplicate children
   * and collapses single-child results.
   *
   * @param node a {@code UNION} node; must not be null
   * @return the rewritten node; may be a different instance if collapsed
   */
  private static HirNode applyUnionDeduplication(HirNode node) {
    List<HirNode> children = node.getChildren();
    List<HirNode> deduped = new ArrayList<>(children.size());
    for (HirNode child : children) {
      boolean isDuplicate = false;
      for (HirNode kept : deduped) {
        if (structurallyEqual(child, kept)) {
          isDuplicate = true;
          break;
        }
      }
      if (!isDuplicate) {
        deduped.add(child);
      }
    }
    if (deduped.size() == children.size()) {
      return node;
    }
    if (deduped.size() == 1) {
      return deduped.get(0);
    }
    node.replaceChildren(deduped);
    return node;
  }

  /**
   * Returns {@code true} if two {@link HirNode} instances are structurally identical.
   *
   * <p>Structural equality requires: same {@link NodeType}, same literal value, same
   * {@link AnchorType}, and pairwise structural equality of all children.
   *
   * @param a the first node; must not be null
   * @param b the second node; must not be null
   * @return {@code true} if {@code a} and {@code b} are structurally equal
   */
  private static boolean structurallyEqual(HirNode a, HirNode b) {
    if (a.getType() != b.getType()) {
      return false;
    }
    if (!Objects.equals(a.getLiteralValue().orElse(null), b.getLiteralValue().orElse(null))) {
      return false;
    }
    if (!Objects.equals(a.getAnchorType().orElse(null), b.getAnchorType().orElse(null))) {
      return false;
    }
    List<HirNode> ac = a.getChildren();
    List<HirNode> bc = b.getChildren();
    if (ac.size() != bc.size()) {
      return false;
    }
    for (int i = 0; i < ac.size(); i++) {
      if (!structurallyEqual(ac.get(i), bc.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Applies Rule O4 to a {@code CONCAT} node: merges runs of consecutive {@code LITERAL}
   * children into a single {@code LITERAL} node.
   *
   * @param node a {@code CONCAT} node; must not be null
   * @return the rewritten node (same instance with updated children list)
   */
  private static HirNode applyLiteralMerging(HirNode node) {
    List<HirNode> children = node.getChildren();
    // Quick check — avoid allocation when no two consecutive literals exist.
    boolean hasConsecutive = false;
    for (int i = 0; i < children.size() - 1; i++) {
      if (children.get(i).getType() == NodeType.LITERAL
          && children.get(i + 1).getType() == NodeType.LITERAL) {
        hasConsecutive = true;
        break;
      }
    }
    if (!hasConsecutive) {
      return node;
    }

    List<HirNode> merged = new ArrayList<>(children.size());
    int i = 0;
    while (i < children.size()) {
      HirNode current = children.get(i);
      if (current.getType() == NodeType.LITERAL) {
        // Collect a run of consecutive literals.
        StringBuilder sb = new StringBuilder(current.getLiteralValue().orElse(""));
        SourceSpan runSpan = current.getSpan();
        int j = i + 1;
        while (j < children.size() && children.get(j).getType() == NodeType.LITERAL) {
          sb.append(children.get(j).getLiteralValue().orElse(""));
          runSpan = SourceSpan.combine(runSpan, children.get(j).getSpan());
          j++;
        }
        merged.add(new HirNode(NodeType.LITERAL, List.of(), runSpan, sb.toString()));
        i = j;
      } else {
        merged.add(current);
        i++;
      }
    }

    if (merged.size() == 1) {
      return merged.get(0);
    }
    node.replaceChildren(merged);
    return node;
  }

  /**
   * Applies Rule O5: flattens nested identical unbounded quantifiers.
   *
   * <p>Specifically, when {@code node} is a {@code QUANTIFIER} or {@code LAZY_QUANTIFIER}
   * with {@code min=0, max=MAX_VALUE}, and its single child is the same quantifier type
   * also with {@code min=0, max=MAX_VALUE}, and that child's single grandchild is
   * <em>not</em> itself a quantifier, replaces the outer quantifier's child with the
   * grandchild (skipping the intermediate level).
   *
   * @param node a greedy or lazy quantifier node; must not be null
   * @return the (possibly rewritten) node
   */
  private static HirNode applyQuantifierFlattening(HirNode node) {
    if (node.getQuantifierMin() != 0 || node.getQuantifierMax() != Integer.MAX_VALUE) {
      return node;
    }
    if (node.getChildren().isEmpty()) {
      return node;
    }
    HirNode child = node.getChildren().get(0);
    if (child.getType() != node.getType()) {
      return node;
    }
    if (child.getQuantifierMin() != 0 || child.getQuantifierMax() != Integer.MAX_VALUE) {
      return node;
    }
    if (child.getChildren().isEmpty()) {
      return node;
    }
    HirNode grandchild = child.getChildren().get(0);
    if (isQuantifierType(grandchild.getType())) {
      // Guard against infinite loops; do not flatten if grandchild is also a quantifier.
      return node;
    }
    // Flatten: outer{0,MAX}(inner{0,MAX}(grandchild)) → outer{0,MAX}(grandchild)
    node.replaceChildren(List.of(grandchild));
    return node;
  }

  // -------------------------------------------------------------------------
  // Pass 3: Literal extraction
  // -------------------------------------------------------------------------

  /**
   * Populates the prefix and suffix {@link LiteralSet} fields of {@code node} and all
   * descendants. Analysis is bottom-up within each case branch.
   *
   * @param node the root of the subtree to analyze; must not be null
   */
  private static void analyzeLiterals(HirNode node) {
    switch (node.getType()) {
      case LITERAL:
        String literalValue = node.getLiteralValue().orElse("");
        node.setPrefix(new LiteralSet(literalValue, "", List.of(), true));
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
        // Analyze children first so their prefix/suffix are available
        for (HirNode child : node.getChildren()) {
          analyzeLiterals(child);
        }
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
          LiteralSet commonPrefix = null;
          LiteralSet commonSuffix = null;

          for (HirNode child : node.getChildren()) {
            analyzeLiterals(child);
            innerLiterals.addAll(child.getPrefix().innerLiterals());
            innerLiterals.addAll(child.getSuffix().innerLiterals());

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
      case LAZY_QUANTIFIER:
      case POSSESSIVE_QUANTIFIER:
        // Analyze child first
        analyzeLiterals(node.getChildren().get(0));
        // Only propagate the child's prefix when the quantifier is required (min >= 1).
        // If min == 0, the quantified content may be skipped entirely, so the child's
        // prefix is NOT a guaranteed prefix of the overall match — using it in a prefilter
        // would cause the prefilter to reject valid positions where the quantifier matches
        // zero times (e.g., a?b on "b" would wrongly reject because "a" is not present).
        if (node.getQuantifierMin() >= 1) {
          node.setPrefix(node.getChildren().get(0).getPrefix());
          node.setSuffix(node.getChildren().get(0).getSuffix());
        } else {
          node.setPrefix(LiteralSet.EMPTY);
          node.setSuffix(LiteralSet.EMPTY);
        }
        break;

      case GROUP:
        // Analyze child first
        analyzeLiterals(node.getChildren().get(0));
        node.setPrefix(node.getChildren().get(0).getPrefix());
        node.setSuffix(node.getChildren().get(0).getSuffix());
        break;

      case ANCHOR:
      case EPSILON:
      case BACKREF:
      case KEEP_ASSERTION:
        node.setPrefix(LiteralSet.EMPTY);
        node.setSuffix(LiteralSet.EMPTY);
        break;

      case LOOKAHEAD:
      case LOOKAHEAD_NEG:
      case LOOKBEHIND:
      case LOOKBEHIND_NEG:
        // Zero-width assertions do not contribute to literal prefixes
        node.setPrefix(LiteralSet.EMPTY);
        node.setSuffix(LiteralSet.EMPTY);
        break;

      case FLAG_EXPR:
      case ATOMIC_GROUP:
      case BALANCE_GROUP:
        // Propagate from the single child
        analyzeLiterals(node.getChildren().get(0));
        node.setPrefix(node.getChildren().get(0).getPrefix());
        node.setSuffix(node.getChildren().get(0).getSuffix());
        break;

      case CONDITIONAL:
        // Either branch may match — no guaranteed prefix/suffix
        for (HirNode child : node.getChildren()) {
          analyzeLiterals(child);
        }
        node.setPrefix(LiteralSet.EMPTY);
        node.setSuffix(LiteralSet.EMPTY);
        break;
    }

    // Recurse to children for node types whose primary analysis branch did not recurse.
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
    while (commonLen < aLen && commonLen < bLen
        && a.charAt(aLen - 1 - commonLen) == b.charAt(bLen - 1 - commonLen)) {
      commonLen++;
    }
    return a.substring(aLen - commonLen);
  }

  // -------------------------------------------------------------------------
  // Pass 4: Min/max match-length analysis
  // -------------------------------------------------------------------------

  /**
   * Computes and sets the {@code minMatchLength} and {@code maxMatchLength} fields on
   * {@code node} and all descendants via a post-order traversal.
   *
   * @param node the root of the subtree to analyze; must not be null
   */
  private static void analyzeMatchLength(HirNode node) {
    // Post-order: compute children first.
    for (HirNode child : node.getChildren()) {
      analyzeMatchLength(child);
    }
    int min;
    int max;
    switch (node.getType()) {
      case LITERAL -> {
        int len = node.getLiteralValue().map(String::length).orElse(0);
        min = len;
        max = len;
      }
      case CHAR_CLASS -> {
        min = 1;
        max = 1;
      }
      case EPSILON, ANCHOR, LOOKAHEAD, LOOKAHEAD_NEG, LOOKBEHIND, LOOKBEHIND_NEG -> {
        min = 0;
        max = 0;
      }
      case BACKREF -> {
        min = 0;
        max = Integer.MAX_VALUE;
      }
      case CONCAT -> {
        min = 0;
        max = 0;
        for (HirNode child : node.getChildren()) {
          min = saturatingAdd(min, child.getMinMatchLength());
          max = saturatingAdd(max, child.getMaxMatchLength());
        }
      }
      case UNION -> {
        if (node.getChildren().isEmpty()) {
          min = 0;
          max = 0;
        } else {
          min = Integer.MAX_VALUE;
          max = 0;
          for (HirNode child : node.getChildren()) {
            min = Math.min(min, child.getMinMatchLength());
            max = Math.max(max, child.getMaxMatchLength());
          }
        }
      }
      case QUANTIFIER, LAZY_QUANTIFIER, POSSESSIVE_QUANTIFIER -> {
        HirNode child = node.getChildren().get(0);
        int qMin = node.getQuantifierMin();
        int qMax = node.getQuantifierMax(); // Integer.MAX_VALUE = unbounded
        min = saturatingMul(child.getMinMatchLength(), qMin);
        max = (qMax == Integer.MAX_VALUE)
            ? Integer.MAX_VALUE
            : saturatingMul(child.getMaxMatchLength(), qMax);
      }
      case GROUP, FLAG_EXPR, ATOMIC_GROUP, BALANCE_GROUP -> {
        HirNode child = node.getChildren().get(0);
        min = child.getMinMatchLength();
        max = child.getMaxMatchLength();
      }
      case CONDITIONAL -> {
        // Union of yes/no branch lengths
        if (node.getChildren().isEmpty()) {
          min = 0;
          max = 0;
        } else {
          min = Integer.MAX_VALUE;
          max = 0;
          for (HirNode child : node.getChildren()) {
            min = Math.min(min, child.getMinMatchLength());
            max = Math.max(max, child.getMaxMatchLength());
          }
        }
      }
      case PAIR -> {
        // Only the input side counts for match length.
        HirNode inputChild = node.getChildren().get(0);
        min = inputChild.getMinMatchLength();
        max = inputChild.getMaxMatchLength();
      }
      default -> {
        min = 0;
        max = 0;
      }
    }
    node.setMinMatchLength(min);
    node.setMaxMatchLength(max);
  }

  /**
   * Saturating addition: returns {@link Integer#MAX_VALUE} if either operand is
   * {@code MAX_VALUE} or the sum would exceed {@code MAX_VALUE}.
   *
   * @param a first operand; must be {@code >= 0}
   * @param b second operand; must be {@code >= 0}
   * @return the saturating sum
   */
  static int saturatingAdd(int a, int b) {
    if (a == Integer.MAX_VALUE || b == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    long sum = (long) a + b;
    return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
  }

  /**
   * Saturating multiplication: returns {@link Integer#MAX_VALUE} if either operand is
   * {@code MAX_VALUE} (and the other is non-zero) or the product would exceed
   * {@code MAX_VALUE}.
   *
   * @param a first operand; must be {@code >= 0}
   * @param b second operand; must be {@code >= 0}
   * @return the saturating product
   */
  static int saturatingMul(int a, int b) {
    if (a == 0 || b == 0) {
      return 0;
    }
    if (a == Integer.MAX_VALUE || b == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    long product = (long) a * b;
    return product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
  }

  // -------------------------------------------------------------------------
  // Pass 5: Anchor position detection
  // -------------------------------------------------------------------------

  /**
   * Computes and sets the {@code startAnchored} and {@code endAnchored} fields on
   * {@code node} and all descendants via a post-order traversal.
   *
   * <p>A node is start-anchored if every possible match must begin at position 0
   * (or line start). A node is end-anchored if every possible match must end at the
   * last position of the input. See the spec for exact propagation rules.
   *
   * @param node the root of the subtree to analyze; must not be null
   */
  private static void analyzeAnchors(HirNode node) {
    for (HirNode child : node.getChildren()) {
      analyzeAnchors(child);
    }

    boolean start;
    boolean end;
    switch (node.getType()) {
      case ANCHOR -> {
        AnchorType type = node.getAnchorType().orElseThrow(
            () -> new AssertionError("ANCHOR node missing AnchorType"));
        start = (type == AnchorType.START
            || type == AnchorType.LINE_START
            || type == AnchorType.BOF);
        end = (type == AnchorType.END
            || type == AnchorType.LINE_END
            || type == AnchorType.EOF);
      }
      case CONCAT -> {
        List<HirNode> children = node.getChildren();
        start = !children.isEmpty() && children.get(0).isStartAnchored();
        end = !children.isEmpty() && children.get(children.size() - 1).isEndAnchored();
      }
      case UNION -> {
        start = !node.getChildren().isEmpty()
            && node.getChildren().stream().allMatch(HirNode::isStartAnchored);
        end = !node.getChildren().isEmpty()
            && node.getChildren().stream().allMatch(HirNode::isEndAnchored);
      }
      case GROUP, FLAG_EXPR, ATOMIC_GROUP, BALANCE_GROUP -> {
        HirNode child = node.getChildren().get(0);
        start = child.isStartAnchored();
        end = child.isEndAnchored();
      }
      case CONDITIONAL -> {
        // Conservative: not anchored unless all branches agree
        start = !node.getChildren().isEmpty()
            && node.getChildren().stream().allMatch(HirNode::isStartAnchored);
        end = !node.getChildren().isEmpty()
            && node.getChildren().stream().allMatch(HirNode::isEndAnchored);
      }
      default -> {
        start = false;
        end = false;
      }
    }
    node.setStartAnchored(start);
    node.setEndAnchored(end);
  }

  // -------------------------------------------------------------------------
  // Pass 6: One-pass safety check
  // -------------------------------------------------------------------------

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
        return checkOnePassSafety(node.getChildren().get(0))
            && checkOnePassSafety(node.getChildren().get(1));

      case CONCAT:
      case UNION:
        for (HirNode child : node.getChildren()) {
          if (!checkOnePassSafety(child)) {
            return false;
          }
        }
        return true;

      case QUANTIFIER:
      case LAZY_QUANTIFIER:
      case POSSESSIVE_QUANTIFIER:
        // Check if child writes any registers
        return node.getChildren().get(0).getType() != NodeType.PAIR;

      default:
        return true;
    }
  }

  // -------------------------------------------------------------------------
  // Pass 7: Output acyclicity and bounded length
  // -------------------------------------------------------------------------

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
      case LAZY_QUANTIFIER:
      case POSSESSIVE_QUANTIFIER:
        // Check if unbounded quantifier on output
        if (node.getChildren().get(0).getType() == NodeType.PAIR) {
          // Check if output side of child is unbounded
          return node.getChildren().get(0).getChildren().get(1)
              .getMaxOutputLengthPerInputChar() == 0;
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
      case LAZY_QUANTIFIER:
      case POSSESSIVE_QUANTIFIER:
        // Check if unbounded repetition of output
        return node.getChildren().get(0).getMaxOutputLengthPerInputChar() == 0;

      default:
        return true;
    }
  }

  private static int calculateMaxOutputLength(HirNode node) {
    switch (node.getType()) {
      case LITERAL:
        // A literal's output length is its string length
        return node.getLiteralValue().map(String::length).orElse(0);

      case PAIR:
        // Output side length — compute it from the output child
        HirNode outputChild = node.getChildren().get(1);
        analyzeOutputProperties(outputChild);
        return outputChild.getMaxOutputLengthPerInputChar();

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
      case LAZY_QUANTIFIER:
      case POSSESSIVE_QUANTIFIER:
        return node.getChildren().get(0).getMaxOutputLengthPerInputChar();

      case CHAR_CLASS:
        // A char class matches one character — output is 1
        return 1;

      default:
        return 0;
    }
  }

  // -------------------------------------------------------------------------
  // Pass 8: Engine classification
  // -------------------------------------------------------------------------

  private static void analyzeEngineClassification(HirNode node) {
    // Post-order: classify children first so their hints are available.
    for (HirNode child : node.getChildren()) {
      analyzeEngineClassification(child);
    }
    EngineHint hint = classifyEngine(node);
    node.setHint(hint);
  }

  private static EngineHint classifyEngine(HirNode node) {
    // Propagate the most-restrictive hint from all children.
    EngineHint childHint = node.getChildren().stream()
        .map(HirNode::getHint)
        .reduce(EngineHint.DFA_SAFE, AnalysisVisitor::mostRestrictiveHint);

    switch (node.getType()) {
      case PAIR:
        return EngineHint.PIKEVM_ONLY;

      case QUANTIFIER:
        // Greedy quantifiers with a large mandatory minimum emit a RepeatMin instruction
        // instead of unrolling; only BoundedBacktrackEngine handles RepeatMin.
        if (node.getQuantifierMin() > LARGE_QUANTIFIER_UNROLL_THRESHOLD) {
          return EngineHint.NEEDS_BACKTRACKER;
        }
        return childHint;

      case LAZY_QUANTIFIER:
        // Lazy quantifiers require NFA priority semantics; the LazyDfaEngine always
        // produces leftmost-longest (greedy) matches and cannot honour laziness.
        if (childHint == EngineHint.NEEDS_BACKTRACKER) {
          return EngineHint.NEEDS_BACKTRACKER;
        }
        if (node.getQuantifierMin() > LARGE_QUANTIFIER_UNROLL_THRESHOLD) {
          return EngineHint.NEEDS_BACKTRACKER;
        }
        return EngineHint.PIKEVM_ONLY;

      case BACKREF:
      case POSSESSIVE_QUANTIFIER:
      case ATOMIC_GROUP:
      case BALANCE_GROUP:
      case CONDITIONAL:
        return EngineHint.NEEDS_BACKTRACKER;

      case UNION:
        // The lazy DFA uses leftmost-longest semantics, but Java regex uses
        // leftmost-first (ordered-alternation) NFA semantics. These diverge whenever
        // an earlier alternative can match a shorter string than a later one
        // (e.g. "a*|b*" on "bb": NFA returns [0,0] via a*, DFA returns [0,2] via b*).
        // Conservative fix: always route alternation through PikeVmEngine.
        if (childHint == EngineHint.NEEDS_BACKTRACKER) {
          return EngineHint.NEEDS_BACKTRACKER;
        }
        return EngineHint.PIKEVM_ONLY;

      case LOOKAHEAD:
      case LOOKAHEAD_NEG:
      case LOOKBEHIND:
      case LOOKBEHIND_NEG:
        // Lookahead/lookbehind require NFA semantics; cannot be handled by LazyDfaEngine.
        return EngineHint.PIKEVM_ONLY;

      case KEEP_ASSERTION:
        // \K resets the reported match start; requires ordered NFA thread tracking.
        // LazyDfaEngine cannot track per-path keep-start state.
        return EngineHint.PIKEVM_ONLY;

      default:
        return childHint;
    }
  }

  /**
   * Returns the more restrictive of two engine hints (i.e., the engine that can handle
   * the broader set of patterns).
   *
   * <p>Ordering from least to most restrictive: {@code DFA_SAFE < ONE_PASS_SAFE <
   * PIKEVM_ONLY < NEEDS_BACKTRACKER < GRAMMAR_RULE}.
   *
   * @param a the first hint; must not be null
   * @param b the second hint; must not be null
   * @return the more restrictive of the two hints
   */
  private static EngineHint mostRestrictiveHint(EngineHint a, EngineHint b) {
    if (a == EngineHint.GRAMMAR_RULE || b == EngineHint.GRAMMAR_RULE) {
      return EngineHint.GRAMMAR_RULE;
    }
    if (a == EngineHint.NEEDS_BACKTRACKER || b == EngineHint.NEEDS_BACKTRACKER) {
      return EngineHint.NEEDS_BACKTRACKER;
    }
    if (a == EngineHint.PIKEVM_ONLY || b == EngineHint.PIKEVM_ONLY) {
      return EngineHint.PIKEVM_ONLY;
    }
    if (a == EngineHint.ONE_PASS_SAFE || b == EngineHint.ONE_PASS_SAFE) {
      return EngineHint.ONE_PASS_SAFE;
    }
    return EngineHint.DFA_SAFE;
  }

  // -------------------------------------------------------------------------
  // Pass 9: Prefilter construction
  // -------------------------------------------------------------------------

  private static void buildPrefilter(HirNode node) {
    List<String> literals = extractLiterals(node.getPrefix());
    if (!literals.isEmpty()) {
      // Build appropriate prefilter based on literal count
      int literalCount = literals.size();
      if (literalCount <= 10) {
        Prefilter pf;
        try {
          pf = new VectorLiteralPrefilter(literals);
        } catch (NoClassDefFoundError e) {
          // jdk.incubator.vector not on module path — fall back to Aho-Corasick
          pf = new AhoCorasickPrefilter(literals, false);
        }
        node.setPrefilter(pf);
      } else if (literalCount <= 500) {
        node.setPrefilter(new AhoCorasickPrefilter(literals, false));
      } else {
        node.setPrefilter(new AhoCorasickPrefilter(literals, true));
      }
    } else {
      node.setPrefilter(NoopPrefilter.INSTANCE);
    }

    for (HirNode child : node.getChildren()) {
      buildPrefilter(child);
    }
  }

  /**
   * Extracts a flat list of literal strings from a {@link LiteralSet}. The prefix is
   * included as a single entry when non-empty, followed by all inner literals.
   *
   * @param literalSet the literal set to extract from; must not be null
   * @return the extracted literal strings; never null, may be empty
   */
  private static List<String> extractLiterals(LiteralSet literalSet) {
    List<String> result = new ArrayList<>();
    if (!literalSet.prefix().isEmpty()) {
      result.add(literalSet.prefix());
    }
    result.addAll(literalSet.innerLiterals());
    return result;
  }
}
