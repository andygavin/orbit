package com.orbit.hir;

import com.orbit.parse.AnchorType;
import com.orbit.prefilter.NoopPrefilter;
import com.orbit.util.SourceSpan;
import com.orbit.util.EngineHint;
import com.orbit.util.NodeType;
import com.orbit.prefilter.Prefilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mutable analysis tree node produced by {@link AnalysisVisitor}.
 *
 * <p>A {@code HirNode} is created during Pass 1 (HIR construction) and then mutated by
 * subsequent analysis passes. Once {@link AnalysisVisitor#analyze} returns, the tree is
 * treated as effectively immutable by all callers.
 *
 * <p>Instances are <em>not</em> thread-safe. All analysis passes are single-threaded.
 */
public class HirNode {

  private final NodeType type;
  private List<HirNode> children;
  private final SourceSpan span;
  /** The literal string value; non-null only for {@code LITERAL} nodes. */
  private final String literalValue;

  // --- Analysis results — populated by analysis passes ---

  private LiteralSet prefix;
  private LiteralSet suffix;
  private boolean isOnePassSafe;
  private boolean outputIsAcyclic;
  private int maxOutputLengthPerInputChar;
  private EngineHint hint;
  private Prefilter prefilter;

  // --- New fields added for Phase 1 remaining items ---

  /**
   * The {@link AnchorType} for {@code ANCHOR} nodes; {@code null} for all other node types.
   * Populated in Pass 1 ({@code buildHir}).
   */
  private AnchorType anchorType;

  /**
   * Minimum repetition count for quantifier nodes; {@code 0} for non-quantifier nodes.
   * Populated in Pass 1 ({@code buildHir}).
   */
  private int quantifierMin;

  /**
   * Maximum repetition count for quantifier nodes; {@link Integer#MAX_VALUE} means unbounded.
   * {@code 0} for non-quantifier nodes.
   * Populated in Pass 1 ({@code buildHir}).
   */
  private int quantifierMax;

  /**
   * Minimum number of UTF-16 code units this node can consume in any successful match.
   * Always {@code >= 0}. Populated by {@code analyzeMatchLength}.
   */
  private int minMatchLength;

  /**
   * Maximum number of UTF-16 code units this node can consume in any successful match.
   * {@link Integer#MAX_VALUE} represents unbounded. Populated by {@code analyzeMatchLength}.
   */
  private int maxMatchLength;

  /**
   * {@code true} if every possible match of this node must begin at the start of the input
   * (or line start). Populated by {@code analyzeAnchors}.
   */
  private boolean startAnchored;

  /**
   * {@code true} if every possible match of this node must end at the end of the input.
   * Populated by {@code analyzeAnchors}.
   */
  private boolean endAnchored;

  /**
   * Creates a new {@code HirNode} without a literal value.
   *
   * @param type     the node type; must not be null
   * @param children the child nodes; must not be null
   * @param span     the source span of this node; must not be null
   */
  public HirNode(NodeType type, List<HirNode> children, SourceSpan span) {
    this(type, children, span, null);
  }

  /**
   * Creates a new {@code HirNode} with an optional literal value.
   *
   * @param type         the node type; must not be null
   * @param children     the child nodes; must not be null
   * @param span         the source span of this node; must not be null
   * @param literalValue the literal string value; non-null only for {@code LITERAL} nodes
   */
  public HirNode(NodeType type, List<HirNode> children, SourceSpan span, String literalValue) {
    this.type = type;
    // Store as a mutable list so that the optimization pass can rewrite children in place.
    this.children = new ArrayList<>(children);
    this.span = span;
    this.literalValue = literalValue;
    // Initialize analysis fields to defaults
    this.prefix = LiteralSet.EMPTY;
    this.suffix = LiteralSet.EMPTY;
    this.isOnePassSafe = true;
    this.outputIsAcyclic = true;
    this.maxOutputLengthPerInputChar = 0;
    this.hint = EngineHint.DFA_SAFE;
    this.prefilter = NoopPrefilter.INSTANCE;
    // New fields default to zero / false / null
    this.anchorType = null;
    this.quantifierMin = 0;
    this.quantifierMax = 0;
    this.minMatchLength = 0;
    this.maxMatchLength = 0;
    this.startAnchored = false;
    this.endAnchored = false;
  }

  // -------------------------------------------------------------------------
  // Core accessors
  // -------------------------------------------------------------------------

  /** Returns the node type; never null. */
  public NodeType getType() { return type; }

  /**
   * Returns the mutable list of child nodes. The optimization pass may add, remove, or
   * reorder children via this reference.
   *
   * @return the child list; never null
   */
  public List<HirNode> getChildren() { return children; }

  /**
   * Replaces the entire child list with the supplied list. Use this from the optimization
   * pass when the children of a node must be wholesale replaced.
   *
   * @param newChildren the replacement children; must not be null
   */
  void replaceChildren(List<HirNode> newChildren) {
    this.children = new ArrayList<>(newChildren);
  }

  /** Returns the source span of this node; never null. */
  public SourceSpan getSpan() { return span; }

  /**
   * Returns the literal string value for {@code LITERAL} nodes;
   * an empty {@link Optional} for all other node types.
   *
   * @return the literal value, never null but possibly empty
   */
  public Optional<String> getLiteralValue() { return Optional.ofNullable(literalValue); }

  // -------------------------------------------------------------------------
  // Existing analysis-result accessors
  // -------------------------------------------------------------------------

  /** Returns the extracted literal prefix set; never null. */
  public LiteralSet getPrefix() { return prefix; }

  /** Sets the extracted literal prefix set. */
  public void setPrefix(LiteralSet prefix) { this.prefix = prefix; }

  /** Returns the extracted literal suffix set; never null. */
  public LiteralSet getSuffix() { return suffix; }

  /** Sets the extracted literal suffix set. */
  public void setSuffix(LiteralSet suffix) { this.suffix = suffix; }

  /** Returns {@code true} if this sub-tree is safe for one-pass matching. */
  public boolean isOnePassSafe() { return isOnePassSafe; }

  /** Sets the one-pass safety flag. */
  public void setOnePassSafe(boolean onePassSafe) { isOnePassSafe = onePassSafe; }

  /** Returns {@code true} if the output of this node is acyclic. */
  public boolean isOutputAcyclic() { return outputIsAcyclic; }

  /** Sets the output acyclicity flag. */
  public void setOutputAcyclic(boolean outputAcyclic) { this.outputIsAcyclic = outputAcyclic; }

  /** Returns the maximum output length per input character. */
  public int getMaxOutputLengthPerInputChar() { return maxOutputLengthPerInputChar; }

  /** Sets the maximum output length per input character. */
  public void setMaxOutputLengthPerInputChar(int maxOutputLength) {
    this.maxOutputLengthPerInputChar = maxOutputLength;
  }

  /** Returns the engine-selection hint for this node; never null. */
  public EngineHint getHint() { return hint; }

  /** Sets the engine-selection hint. */
  public void setHint(EngineHint hint) { this.hint = hint; }

  /** Returns the prefilter attached to this node; never null. */
  public Prefilter getPrefilter() { return prefilter; }

  /** Sets the prefilter for this node. */
  public void setPrefilter(Prefilter prefilter) { this.prefilter = prefilter; }

  // -------------------------------------------------------------------------
  // New accessors — anchor type
  // -------------------------------------------------------------------------

  /**
   * Returns the {@link AnchorType} for {@code ANCHOR} nodes, or an empty {@link Optional}
   * for all other node types.
   *
   * @return the anchor type, never null but possibly empty
   */
  public Optional<AnchorType> getAnchorType() { return Optional.ofNullable(anchorType); }

  /**
   * Sets the anchor type. Should only be called for {@code ANCHOR} nodes from
   * {@code AnalysisVisitor.buildHir}.
   *
   * @param type the anchor type; must not be null
   */
  public void setAnchorType(AnchorType type) { this.anchorType = type; }

  // -------------------------------------------------------------------------
  // New accessors — quantifier bounds
  // -------------------------------------------------------------------------

  /**
   * Returns the minimum repetition count for quantifier nodes. Zero for non-quantifier nodes.
   *
   * @return the quantifier minimum, always {@code >= 0}
   */
  public int getQuantifierMin() { return quantifierMin; }

  /**
   * Sets the minimum repetition count. Meaningful only for quantifier node types.
   *
   * @param min the minimum; must be {@code >= 0}
   */
  public void setQuantifierMin(int min) { this.quantifierMin = min; }

  /**
   * Returns the maximum repetition count for quantifier nodes. {@link Integer#MAX_VALUE}
   * means unbounded. Zero for non-quantifier nodes.
   *
   * @return the quantifier maximum
   */
  public int getQuantifierMax() { return quantifierMax; }

  /**
   * Sets the maximum repetition count. Meaningful only for quantifier node types.
   * Use {@link Integer#MAX_VALUE} to represent an unbounded upper bound.
   *
   * @param max the maximum
   */
  public void setQuantifierMax(int max) { this.quantifierMax = max; }

  // -------------------------------------------------------------------------
  // New accessors — match-length bounds
  // -------------------------------------------------------------------------

  /**
   * Returns the minimum number of UTF-16 code units this node can match.
   * Populated by {@code AnalysisVisitor.analyzeMatchLength}; defaults to {@code 0}.
   *
   * @return the minimum match length, always {@code >= 0}
   */
  public int getMinMatchLength() { return minMatchLength; }

  /**
   * Sets the minimum match length.
   *
   * @param min the minimum; must be {@code >= 0}
   */
  public void setMinMatchLength(int min) { this.minMatchLength = min; }

  /**
   * Returns the maximum number of UTF-16 code units this node can match.
   * {@link Integer#MAX_VALUE} represents unbounded.
   * Populated by {@code AnalysisVisitor.analyzeMatchLength}; defaults to {@code 0}.
   *
   * @return the maximum match length
   */
  public int getMaxMatchLength() { return maxMatchLength; }

  /**
   * Sets the maximum match length. Use {@link Integer#MAX_VALUE} for unbounded.
   *
   * @param max the maximum
   */
  public void setMaxMatchLength(int max) { this.maxMatchLength = max; }

  // -------------------------------------------------------------------------
  // New accessors — anchor detection
  // -------------------------------------------------------------------------

  /**
   * Returns {@code true} if every possible match of this sub-pattern must begin at the
   * start of the input (or line start). Populated by {@code AnalysisVisitor.analyzeAnchors}.
   *
   * @return {@code true} when start-anchored
   */
  public boolean isStartAnchored() { return startAnchored; }

  /**
   * Sets the start-anchored flag.
   *
   * @param startAnchored {@code true} if the pattern is start-anchored
   */
  public void setStartAnchored(boolean startAnchored) { this.startAnchored = startAnchored; }

  /**
   * Returns {@code true} if every possible match of this sub-pattern must end at the end
   * of the input. Populated by {@code AnalysisVisitor.analyzeAnchors}.
   *
   * @return {@code true} when end-anchored
   */
  public boolean isEndAnchored() { return endAnchored; }

  /**
   * Sets the end-anchored flag.
   *
   * @param endAnchored {@code true} if the pattern is end-anchored
   */
  public void setEndAnchored(boolean endAnchored) { this.endAnchored = endAnchored; }
}
