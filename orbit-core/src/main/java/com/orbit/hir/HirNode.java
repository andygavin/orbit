package com.orbital.hir;

import com.orbital.parse.Expr;
import com.orbital.parse.SourceSpan;
import com.orbital.util.EngineHint;
import com.orbital.prefilter.Prefilter;

import java.util.List;

/**
 * Mutable analysis tree during the five analysis passes.
 * Fully immutable once analysis completes.
 */
public class HirNode {

    private final NodeType type;
    private final List<HirNode> children;
    private final SourceSpan span;

    // Analysis results - populated by analysis passes
    private LiteralSet prefix;
    private LiteralSet suffix;
    private boolean isOnePassSafe;
    private boolean outputIsAcyclic;
    private int maxOutputLengthPerInputChar;
    private EngineHint hint;
    private Prefilter prefilter;

    public HirNode(NodeType type, List<HirNode> children, SourceSpan span) {
        this.type = type;
        this.children = children;
        this.span = span;
        // Initialize analysis fields to null/default
        this.prefix = LiteralSet.EMPTY;
        this.suffix = LiteralSet.EMPTY;
        this.isOnePassSafe = true;
        this.outputIsAcyclic = true;
        this.maxOutputLengthPerInputChar = 0;
        this.hint = EngineHint.DFA_SAFE;
        this.prefilter = Prefilter.NOOP;
    }

    // Getters for analysis fields
    public NodeType getType() { return type; }
    public List<HirNode> getChildren() { return children; }
    public SourceSpan getSpan() { return span; }
    public LiteralSet getPrefix() { return prefix; }
    public void setPrefix(LiteralSet prefix) { this.prefix = prefix; }
    public LiteralSet getSuffix() { return suffix; }
    public void setSuffix(LiteralSet suffix) { this.suffix = suffix; }
    public boolean isOnePassSafe() { return isOnePassSafe; }
    public void setOnePassSafe(boolean onePassSafe) { isOnePassSafe = onePassSafe; }
    public boolean isOutputAcyclic() { return outputIsAcyclic; }
    public void setOutputAcyclic(boolean outputAcyclic) { this.outputIsAcyclic = outputAcyclic; }
    public int getMaxOutputLengthPerInputChar() { return maxOutputLengthPerInputChar; }
    public void setMaxOutputLengthPerInputChar(int maxOutputLength) { this.maxOutputLengthPerInputChar = maxOutputLength; }
    public EngineHint getHint() { return hint; }
    public void setHint(EngineHint hint) { this.hint = hint; }
    public Prefilter getPrefilter() { return prefilter; }
    public void setPrefilter(Prefilter prefilter) { this.prefilter = prefilter; }
}