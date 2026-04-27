package com.orbit.api;

import com.orbit.hir.AnalysisVisitor;
import com.orbit.hir.HirNode;
import com.orbit.parse.Anchor;
import com.orbit.parse.AnchorType;
import com.orbit.parse.AtomicGroup;
import com.orbit.parse.BalanceGroupExpr;
import com.orbit.parse.Backref;
import com.orbit.parse.CharClass;
import com.orbit.parse.CharRange;
import com.orbit.parse.Concat;
import com.orbit.parse.ConditionalExpr;
import com.orbit.parse.Epsilon;
import com.orbit.parse.Expr;
import com.orbit.parse.KeepAssertion;
import com.orbit.parse.FlagExpr;
import com.orbit.parse.Group;
import com.orbit.parse.LookbehindExpr;
import com.orbit.parse.LookaheadExpr;
import com.orbit.parse.Literal;
import com.orbit.parse.Pair;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.parse.Re2Validator;
import com.orbit.parse.Quantifier;
import com.orbit.parse.Union;
import com.orbit.prog.Accept;
import com.orbit.prog.AnyChar;
import com.orbit.prog.BalanceCheckInstr;
import com.orbit.prog.BalancePopInstr;
import com.orbit.prog.BalancePushInstr;
import com.orbit.prog.BackrefCheck;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.CharMatch;
import com.orbit.prog.CompileResult;
import com.orbit.prog.ConditionalBranchInstr;
import com.orbit.prog.BeginG;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EndZ;
import com.orbit.prog.EpsilonJump;
import com.orbit.prog.Instr;
import com.orbit.prog.LookbehindNeg;
import com.orbit.prog.LookbehindPos;
import com.orbit.prog.Lookahead;
import com.orbit.prog.LookaheadNeg;
import com.orbit.prog.Metadata;
import com.orbit.prog.Prog;
import com.orbit.prog.SaveCapture;
import com.orbit.prog.AtomicCommit;
import com.orbit.prog.PossessiveSplit;
import com.orbit.prog.RepeatMin;
import com.orbit.prog.RepeatReturn;
import com.orbit.prog.Split;
import com.orbit.prog.UnionSplit;
import com.orbit.prog.TransOutput;
import com.orbit.prog.WordBoundary;
import com.orbit.prog.ProgOptimiser;
import com.orbit.prog.ResetMatchStart;
import com.orbit.engine.dfa.AlphabetMap;
import com.orbit.engine.dfa.PrecomputedDfa;
import com.orbit.util.EngineHint;
import com.orbit.util.PatternFlag;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Compiled representation of a regular expression.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 * Obtain instances via the {@link #compile} factory methods.
 *
 * <p>Instances are serializable. The serialized form stores only the pattern string and the
 * flags set; the compiled state is recompiled on deserialization via {@link #readResolve()}.
 */
public final class Pattern implements Serializable {

  /** Serialization version. The compiled state is not serialized; only pattern and flags are. */
  private static final long serialVersionUID = 1L;

  private static final int DEFAULT_CACHE_SIZE = 512;
  private static final ConcurrentMap<CacheKey, CacheValue> CACHE =
      new ConcurrentHashMap<>(DEFAULT_CACHE_SIZE);

  /** Immutable cache entry pairing the compile result, the matches-prog, and the one-pass-safe flag. */
  private record CacheValue(CompileResult result, Prog matchesProg, boolean onePassSafe) {}

  /** The original regular expression string; serialized. */
  private final String pattern;
  /** The compilation flags; serialized. */
  private final EnumSet<PatternFlag> flags;
  /**
   * A compiled program that appends an implicit end-of-input anchor to the pattern.
   * Used exclusively by {@link Matcher#matches()} to enforce full-input matching
   * without requiring the engine to backtrack through all alternatives.
   * Not serialized; recomputed on deserialization via {@link #readResolve()}.
   */
  private final transient Prog matchesProg;

  /**
   * The compiled instruction program and metadata; not serialized.
   * Recomputed on deserialization via {@link #readResolve()}.
   */
  private final transient CompileResult compileResult;
  /**
   * Cached result of the HIR one-pass safety analysis; not serialized.
   * Recomputed on deserialization via {@link #readResolve()}.
   */
  private final transient boolean onePassSafe;

  private Pattern(String pattern, EnumSet<PatternFlag> flags, CompileResult compileResult,
      Prog matchesProg, boolean onePassSafe) {
    this.pattern = pattern;
    this.flags = flags;
    this.compileResult = compileResult;
    this.matchesProg = matchesProg;
    this.onePassSafe = onePassSafe;
  }

  // -----------------------------------------------------------------------
  // Factory methods
  // -----------------------------------------------------------------------

  /**
   * Compiles the given regular expression with all default flags.
   *
   * @param regex the regular expression; must not be null
   * @return the compiled pattern, never null
   * @throws NullPointerException if {@code regex} is null
   * @throws RuntimeException     wrapping a {@link PatternSyntaxException} on parse error
   */
  public static Pattern compile(String regex) {
    return compile(regex, new PatternFlag[0]);
  }

  /**
   * Compiles the given regular expression with the specified flags.
   *
   * @param regex the regular expression; must not be null
   * @param flags the compilation flags; must not be null
   * @return the compiled pattern, never null
   * @throws NullPointerException if {@code regex} or {@code flags} is null
   * @throws RuntimeException     wrapping a {@link PatternSyntaxException} on parse error
   */
  public static Pattern compile(String regex, PatternFlag... flags) {
    if (regex == null) {
      throw new NullPointerException("regex must not be null");
    }
    if (flags == null) {
      throw new NullPointerException("flags must not be null");
    }

    EnumSet<PatternFlag> flagSet = flags.length == 0
        ? EnumSet.noneOf(PatternFlag.class)
        : EnumSet.copyOf(List.of(flags));

    CacheKey key = new CacheKey(regex, flagSet);
    CacheValue cached = CACHE.get(key);

    if (cached == null) {
      try {
        if (flagSet.contains(PatternFlag.RE2_COMPAT)) {
          for (PatternFlag incompatible : List.of(
              PatternFlag.COMMENTS, PatternFlag.LITERAL, PatternFlag.UNICODE_CASE,
              PatternFlag.UNIX_LINES, PatternFlag.CANON_EQ, PatternFlag.PERL_NEWLINES)) {
            if (flagSet.contains(incompatible)) {
              throw new PatternSyntaxException(
                  "flag " + incompatible + " is not supported in RE2_COMPAT mode", regex, -1);
            }
          }
        }
        Expr expr = Parser.parse(regex, flags);
        if (flagSet.contains(PatternFlag.RE2_COMPAT)) {
          Re2Validator.validate(expr, regex);
        }
        HirNode hir = AnalysisVisitor.analyze(expr);
        CompileResult result = buildCompileResult(expr, hir, flagSet);
        if (flagSet.contains(PatternFlag.RE2_COMPAT)
            && result.metadata().hint() == EngineHint.NEEDS_BACKTRACKER) {
          throw new AssertionError("RE2_COMPAT pattern routed to BBE — Re2Validator bug");
        }
        // Build matchesProg: same AST wrapped with an implicit EOF anchor so that
        // Matcher.matches() tries all alternatives until one spans the full input.
        // Run full analysis on the anchored expression so the engine hint is correct.
        Expr anchoredExpr = new Concat(List.of(expr, new Anchor(AnchorType.EOF)));
        HirNode anchoredHir = AnalysisVisitor.analyze(anchoredExpr);
        Prog matchesProg = buildCompileResult(anchoredExpr, anchoredHir, flagSet).prog();
        boolean onePassSafe = hir.isOnePassSafe();
        cached = new CacheValue(result, matchesProg, onePassSafe);
        CACHE.put(key, cached);
      } catch (PatternSyntaxException e) {
        throw new RuntimeException(e);
      }
    }

    return new Pattern(regex, flagSet, cached.result(), cached.matchesProg(), cached.onePassSafe());
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /**
   * Returns a new {@link Matcher} that will match the given input against this pattern.
   *
   * @param input the character sequence to match; must not be null
   * @return a new matcher, never null
   * @throws NullPointerException if {@code input} is null
   */
  public Matcher matcher(CharSequence input) {
    if (input == null) {
      throw new NullPointerException("input must not be null");
    }
    return new Matcher(this, input);
  }

  /**
   * Returns whether this pattern was determined to be one-pass safe during analysis.
   *
   * <p>A pattern is one-pass safe when no backreferences or transducer-pair nodes are
   * present and the HIR one-pass check passed.
   *
   * @return true if the one-pass DFA engine can handle this pattern
   */
  public boolean isOnePassSafe() {
    return onePassSafe;
  }

  /**
   * Returns the engine selection hint for this pattern.
   *
   * @return the engine hint, never null
   */
  public EngineHint engineHint() {
    return compileResult.metadata().hint();
  }

  /**
   * Returns the original pattern string.
   *
   * @return the pattern string, never null
   */
  public String pattern() {
    return pattern;
  }

  /**
   * Returns the string representation of this pattern, which is the regular expression string
   * from which this pattern was compiled.
   *
   * <p>Equivalent to {@link #pattern()}. Mirrors the contract of
   * {@code java.util.regex.Pattern.toString()}.
   *
   * @return the original regular expression string; never null
   */
  @Override
  public String toString() {
    return pattern;
  }

  /**
   * Returns the flags used to compile this pattern.
   *
   * @return a copy of the flags array, never null
   */
  public PatternFlag[] flags() {
    return flags.toArray(new PatternFlag[0]);
  }

  /**
   * Returns the compiled instruction program; intended for engine and benchmark use.
   *
   * @return the compiled program, never null
   */
  public Prog prog() {
    return compileResult.prog();
  }

  /**
   * Returns the compiled program used exclusively by {@link Matcher#matches()}.
   *
   * <p>This program appends an implicit end-of-input anchor ({@link AnchorType#EOF}) to the
   * compiled pattern. As a result the engine is forced to explore all alternatives in an
   * alternation until one spans the full input, rather than stopping at the first match.
   *
   * @return the end-anchored program, never null
   */
  public Prog matchesProg() {
    return matchesProg;
  }

  /**
   * Returns the metadata for this pattern.
   *
   * @return the metadata, never null
   */
  Metadata metadata() {
    return compileResult.metadata();
  }

  /**
   * Returns an unmodifiable map from named capturing group names to their 1-based indices.
   *
   * <p>Returns an empty map if the pattern has no named groups.
   *
   * @return an unmodifiable map of group name to 1-based index; never null
   */
  public Map<String, Integer> namedGroups() {
    Map<String, Integer> groups = compileResult.metadata().groupNames();
    if (groups.isEmpty()) {
      return Map.of();
    }
    return java.util.Collections.unmodifiableMap(groups);
  }

  /**
   * Returns a predicate that tests whether a match can be found anywhere in a string.
   *
   * <p>Equivalent to {@code s -> matcher(s).find()}.
   *
   * @return a predicate; never null
   */
  public java.util.function.Predicate<String> asPredicate() {
    return s -> matcher(s).find();
  }

  /**
   * Returns a predicate that tests whether a string matches this pattern entirely.
   *
   * <p>Equivalent to {@code s -> matcher(s).matches()}.
   *
   * @return a predicate; never null
   */
  public java.util.function.Predicate<String> asMatchPredicate() {
    return s -> matcher(s).matches();
  }

  /**
   * Returns the result of splitting {@code input} around matches of this pattern as a stream.
   *
   * <p>Implemented eagerly by delegating to {@link #split(CharSequence)} and streaming the
   * result array.
   *
   * @param input the character sequence to split; must not be null
   * @return a stream of the split tokens; never null
   * @throws NullPointerException if {@code input} is null
   */
  public java.util.stream.Stream<String> splitAsStream(CharSequence input) {
    if (input == null) {
      throw new NullPointerException("input must not be null");
    }
    return java.util.Arrays.stream(split(input));
  }

  // -----------------------------------------------------------------------
  // Static utility methods
  // -----------------------------------------------------------------------

  /**
   * Returns whether the given input entirely matches the given regular expression.
   *
   * @param regex the regular expression; must not be null
   * @param input the input to match; must not be null
   * @return true if the entire input matches
   */
  public static boolean matches(String regex, CharSequence input) {
    return compile(regex).matcher(input).matches();
  }

  /**
   * Returns a literal pattern string for the given string, escaping all metacharacters.
   *
   * @param s the string to quote; must not be null
   * @return the escaped string, never null
   * @throws NullPointerException if {@code s} is null
   */
  public static String quote(String s) {
    if (s == null) {
      throw new NullPointerException("s must not be null");
    }
    // Wrap in \Q...\E, splitting on any literal \E sequence (JDK-compatible).
    return "\\Q" + s.replace("\\E", "\\E\\\\E\\Q") + "\\E";
  }

  /**
   * Splits the given input around matches of the given regular expression.
   *
   * @param regex the regular expression; must not be null
   * @param input the input to split; must not be null
   * @return the array of substrings, never null
   */
  public static String[] split(String regex, CharSequence input) {
    return split(regex, input, 0);
  }

  /**
   * Splits the given input around matches of the given regular expression, up to the given limit.
   *
   * @param regex the regular expression; must not be null
   * @param input the input to split; must not be null
   * @param limit the result threshold; zero means drop trailing empty strings
   * @return the array of substrings, never null
   */
  public static String[] split(String regex, CharSequence input, int limit) {
    Pattern p = compile(regex);
    return p.split(input, limit);
  }

  /**
   * Splits this pattern's input around matches, equivalent to {@link #split(CharSequence, int)}
   * with limit 0.
   *
   * @param input the character sequence to split; must not be null
   * @return the array of strings computed by splitting the input around matches
   * @throws NullPointerException if {@code input} is null
   */
  public String[] split(CharSequence input) {
    return split(input, 0);
  }

  /**
   * Splits this pattern's input around matches of this pattern, up to the given limit.
   *
   * @param input the character sequence to split; must not be null
   * @param limit the result threshold: positive limits count, negative keeps trailing empties,
   *              zero drops trailing empty strings
   * @return the array of strings computed by splitting the input around matches
   * @throws NullPointerException if {@code input} is null
   */
  public String[] split(CharSequence input, int limit) {
    if (input == null) {
      throw new NullPointerException("input must not be null");
    }

    // JDK compatibility: empty input always returns an empty array.
    if (input.length() == 0) {
      return new String[0];
    }

    Matcher m = matcher(input);
    List<String> list = new ArrayList<>();
    int lastIndex = 0;

    while (limit <= 0 || list.size() + 1 < limit) {
      if (!m.find()) {
        break;
      }
      // JDK compatibility: if the first match is zero-length at position 0, skip the
      // resulting leading empty token (the subSequence before the match is also empty).
      if (m.start() == 0 && m.start() == m.end() && lastIndex == 0) {
        lastIndex = m.end();
        continue;
      }
      list.add(input.subSequence(lastIndex, m.start()).toString());
      lastIndex = m.end();
    }

    list.add(input.subSequence(lastIndex, input.length()).toString());

    // If limit > 0, truncate to limit elements.
    if (limit > 0 && list.size() > limit) {
      list = list.subList(0, limit);
    }

    // If limit == 0, drop trailing empty strings.
    if (limit == 0) {
      int lastNonEmpty = list.size() - 1;
      while (lastNonEmpty >= 0 && list.get(lastNonEmpty).isEmpty()) {
        lastNonEmpty--;
      }
      list = list.subList(0, lastNonEmpty + 1);
    }

    return list.toArray(new String[0]);
  }

  // -----------------------------------------------------------------------
  // Serialization support
  // -----------------------------------------------------------------------

  /**
   * Resolves a deserialized {@code Pattern} instance by recompiling from the stored
   * {@code pattern} string and {@code flags} set.
   *
   * <p>Java object deserialization restores only the non-transient fields {@code pattern}
   * and {@code flags} via {@code defaultReadObject}. This method discards the
   * partially-constructed instance and returns a fully-constructed {@code Pattern} obtained
   * through the normal {@link #compile} factory, which populates the transient
   * {@code compileResult} and {@code onePassSafe} fields correctly.
   *
   * @return a fully-initialized {@code Pattern} equivalent to {@code compile(pattern, flags)}
   * @throws java.io.ObjectStreamException never thrown in normal operation
   */
  private Object readResolve() throws java.io.ObjectStreamException {
    // Re-compile from the stored pattern and flags; this populates all transient fields
    // (compileResult, matchesProg, onePassSafe) via the compile() factory.
    return compile(pattern, flags.toArray(new PatternFlag[0]));
  }

  // -----------------------------------------------------------------------
  // Compilation pipeline
  // -----------------------------------------------------------------------

  /**
   * Builds a {@link CompileResult} from the given expression and its pre-analyzed HIR.
   *
   * @param expr the expression to compile
   * @param hir  the already-analyzed HIR (from {@link AnalysisVisitor#analyze})
   * @return the compile result, never null
   */
  private static CompileResult buildCompileResult(
      Expr expr, HirNode hir, EnumSet<PatternFlag> flags) {
    Prog progWithDummyMetadata = buildProg(expr, flags);
    Map<String, Integer> groupNames = collectGroupNames(expr);

    // The prefilter scans input literals case-sensitively. When CASE_INSENSITIVE is active
    // (either via compilation flags or via an inline (?i) / (?i:...) flag expression), the
    // prefix literals extracted from the HIR do not match a case-folded input, causing the
    // prefilter to produce false negatives and miss valid match positions.
    // Disable the non-trivial prefilter whenever any case-insensitive mode is in effect.
    // A NoopPrefilter is always safe: it never rejects a valid position.
    boolean hasCaseInsensitiveFlag = flags.contains(PatternFlag.CASE_INSENSITIVE)
        || containsInlineCaseInsensitiveFlag(expr);
    com.orbit.prefilter.Prefilter effectivePrefilter =
        hasCaseInsensitiveFlag
            ? com.orbit.prefilter.NoopPrefilter.INSTANCE
            : hir.getPrefilter();

    Metadata metadata = new Metadata(
        hir.getHint(),
        effectivePrefilter,
        countGroups(expr),
        hir.getMaxOutputLengthPerInputChar(),
        false,
        false,
        groupNames,
        hir.isStartAnchored(),
        hir.isEndAnchored(),
        flags.contains(PatternFlag.UNICODE_CASE),
        false
    );

    // fold EpsilonJump chains before constructing the final Prog.
    ProgOptimiser.FoldResult folded = ProgOptimiser.foldEpsilonChains(
        progWithDummyMetadata.instructions,
        progWithDummyMetadata.startPc,
        progWithDummyMetadata.acceptPc);

    // The Compiler emits a Prog with a placeholder Metadata (DFA_SAFE, NoopPrefilter).
    // Reconstruct the Prog with the correct Metadata derived from the HIR analysis passes,
    // so that MetaEngine.execute() and engine-selection logic read the right hint.
    Prog prog = new Prog(
        folded.instructions(),
        metadata,
        folded.startPc(),
        folded.acceptPc());

    // For ONE_PASS_SAFE patterns, attempt to precompute the full DFA at compile time.
    // If the NFA exceeds the DFA state limit, build() returns null and the engine falls
    // back to LazyDfaEngine transparently.
    if (metadata.hint() == EngineHint.ONE_PASS_SAFE) {
      AlphabetMap alphabetMap = AlphabetMap.build(prog);
      prog.precomputedDfa = PrecomputedDfa.build(prog, alphabetMap);
    }

    return new CompileResult(prog, effectivePrefilter, metadata);
  }

  /**
   * Builds a {@link com.orbit.prog.CompileResult} for a transducer expression that has already
   * been parsed and analyzed.
   *
   * <p>This is a package-private entry point used by {@link Transducer} to compile a
   * {@link Pair} expression through the full nine-pass analysis pipeline while setting
   * {@code isTransducer = true} in the resulting {@link Metadata}.
   *
   * <p>Unlike {@link #buildCompileResult}, this method sets {@code isTransducer = true} when
   * the root expression is a {@link Pair}, enabling output accumulation in the engine.
   *
   * @param expr the parsed expression (may be a {@link Pair} or a plain regex)
   * @param hir  the analyzed HIR returned by {@link AnalysisVisitor#analyze(Expr)}
   * @param flags the active flag set; must not be null
   * @return the compile result, never null
   */
  static CompileResult buildTransducerCompileResult(
      Expr expr, HirNode hir, EnumSet<PatternFlag> flags) {
    Prog progWithDummyMetadata = buildProg(expr, flags);
    Map<String, Integer> groupNames = collectGroupNames(expr);

    boolean hasCaseInsensitiveFlag = flags.contains(PatternFlag.CASE_INSENSITIVE)
        || containsInlineCaseInsensitiveFlag(expr);
    com.orbit.prefilter.Prefilter effectivePrefilter =
        hasCaseInsensitiveFlag
            ? com.orbit.prefilter.NoopPrefilter.INSTANCE
            : hir.getPrefilter();

    // isTransducer is true when the root expression is a Pair (has a : separator).
    boolean isTransducer = expr instanceof Pair;

    Metadata metadata = new Metadata(
        hir.getHint(),
        effectivePrefilter,
        countGroups(expr),
        hir.getMaxOutputLengthPerInputChar(),
        false,
        isTransducer,
        groupNames,
        hir.isStartAnchored(),
        hir.isEndAnchored(),
        flags.contains(PatternFlag.UNICODE_CASE),
        false
    );

    ProgOptimiser.FoldResult folded = ProgOptimiser.foldEpsilonChains(
        progWithDummyMetadata.instructions,
        progWithDummyMetadata.startPc,
        progWithDummyMetadata.acceptPc);

    Prog prog = new Prog(
        folded.instructions(),
        metadata,
        folded.startPc(),
        folded.acceptPc());

    // ONE_PASS_SAFE: transducer programs are routed PIKEVM_ONLY by Pass 8; this branch
    // should not trigger for Pair expressions, but is included for safety.
    if (metadata.hint() == com.orbit.util.EngineHint.ONE_PASS_SAFE) {
      com.orbit.engine.dfa.AlphabetMap alphabetMap = com.orbit.engine.dfa.AlphabetMap.build(prog);
      prog.precomputedDfa = com.orbit.engine.dfa.PrecomputedDfa.build(prog, alphabetMap);
    }

    return new CompileResult(prog, effectivePrefilter, metadata);
  }

  /**
   * Executes the compiled program against the full input string, requiring the match to span
   * exactly {@code [0, input.length())}.
   *
   * <p>This is the full-match semantic used by {@link Transducer#applyUp}: the input must
   * be consumed in its entirety. It is equivalent to the {@link Matcher#matches()} contract
   * but returns the raw {@link MatchResult} so the transducer output field is accessible.
   *
   * @param prog  the compiled transducer program; must not be null
   * @param input the input string; must not be null
   * @return the match result; never null; {@code result.matches()} is {@code true} only when
   *         the match spans {@code [0, input.length())}
   */
  static com.orbit.prog.MatchResult executeFullMatch(Prog prog, String input) {
    com.orbit.prog.MatchResult result =
        com.orbit.engine.MetaEngine.execute(prog, input, 0, input.length());
    if (!result.matches() || result.start() != 0 || result.end() != input.length()) {
      return new com.orbit.prog.MatchResult(
          false, -1, -1, java.util.List.of(), null, 0L, 0L, 0);
    }
    return result;
  }

  /**
   * Executes the compiled program against the input string using find semantics, searching for
   * the leftmost match starting at {@code from} within {@code [from, to)}.
   *
   * <p>This is the find-first semantic used by {@link Transducer#tokenize}: multiple
   * non-overlapping matches are found by advancing {@code from} past each accepted end position.
   *
   * @param prog  the compiled transducer program; must not be null
   * @param input the input string; must not be null
   * @param from  the starting search index (inclusive)
   * @param to    the ending search index (exclusive)
   * @return the match result; never null
   */
  static com.orbit.prog.MatchResult executeFind(Prog prog, String input, int from, int to) {
    return com.orbit.engine.MetaEngine.execute(prog, input, from, to);
  }

  /**
   * Collects a name-to-1-based-index map for all named capturing groups in the expression tree.
   *
   * @param expr the expression tree to scan; must not be null
   * @return a map from group name to its 1-based group index, in encounter order; never null
   */
  private static Map<String, Integer> collectGroupNames(Expr expr) {
    Map<String, Integer> names = new LinkedHashMap<>();
    collectGroupNames(expr, names);
    return names;
  }

  private static void collectGroupNames(Expr expr, Map<String, Integer> names) {
    switch (expr) {
      case Group g -> {
        if (g.name() != null) {
          names.put(g.name(), g.index() + 1); // 1-based
        }
        collectGroupNames(g.body(), names);
      }
      case Concat c -> c.parts().forEach(p -> collectGroupNames(p, names));
      case Union u -> u.alternatives().forEach(a -> collectGroupNames(a, names));
      case Quantifier q -> collectGroupNames(q.child(), names);
      case Pair p -> {
        collectGroupNames(p.input(), names);
        collectGroupNames(p.output(), names);
      }
      case FlagExpr fe -> collectGroupNames(fe.body(), names);
      case AtomicGroup ag -> collectGroupNames(ag.body(), names);
      case LookaheadExpr la -> collectGroupNames(la.body(), names);
      case LookbehindExpr lb -> collectGroupNames(lb.body(), names);
      case BalanceGroupExpr bg -> collectGroupNames(bg.body(), names);
      case ConditionalExpr cond -> {
        if (cond.condition() instanceof ConditionalExpr.LookaheadCondition lc) {
          collectGroupNames(lc.lookaheadBody(), names);
        }
        collectGroupNames(cond.yes(), names);
        collectGroupNames(cond.noAlt(), names);
      }
      default -> { /* leaf nodes: Literal, CharClass, Anchor, Epsilon, Backref */ }
    }
  }

  // -----------------------------------------------------------------------
  // Instruction compiler (Expr → Instr[])
  // -----------------------------------------------------------------------

  /**
   * Compiles an {@link Expr} into a {@link Prog} using Thompson-NFA compilation.
   *
   * <p>The compiled program is structured as a flat {@code Instr[]} array.
   * PC-relative addressing: each instruction stores absolute indices into the array.
   * The program always ends with an {@link Accept} instruction at the last position.
   *
   * @param expr  the expression to compile; must not be null
   * @param flags the active flag set for this compilation; must not be null
   * @return the compiled program, never null
   */
  static Prog buildProg(Expr expr, EnumSet<PatternFlag> flags) {
    Compiler compiler = new Compiler(flags);
    compiler.compile(expr);
    compiler.emit(new Accept());
    return compiler.toProg();
  }

  /**
   * Compiles an {@link Expr} into a {@link Prog} with no active flags.
   *
   * @param expr the expression to compile; must not be null
   * @return the compiled program, never null
   */
  static Prog buildProg(Expr expr) {
    return buildProg(expr, EnumSet.noneOf(PatternFlag.class));
  }

  /**
   * Counts the number of capturing groups in the expression tree.
   */
  private static int countGroups(Expr expr) {
    if (expr instanceof Group g) {
      return 1 + countGroups(g.body());
    } else if (expr instanceof Concat c) {
      return c.parts().stream().mapToInt(Pattern::countGroups).sum();
    } else if (expr instanceof Union u) {
      return u.alternatives().stream().mapToInt(Pattern::countGroups).sum();
    } else if (expr instanceof Quantifier q) {
      return countGroups(q.child());
    } else if (expr instanceof Pair p) {
      return countGroups(p.input()) + countGroups(p.output());
    } else if (expr instanceof AtomicGroup ag) {
      return countGroups(ag.body());
    } else if (expr instanceof FlagExpr fe) {
      return countGroups(fe.body());
    } else if (expr instanceof LookaheadExpr la) {
      return countGroups(la.body());
    } else if (expr instanceof LookbehindExpr lb) {
      return countGroups(lb.body());
    } else if (expr instanceof BalanceGroupExpr bg) {
      return countGroups(bg.body());
    } else if (expr instanceof ConditionalExpr cond) {
      int condCount = 0;
      if (cond.condition() instanceof ConditionalExpr.LookaheadCondition lc) {
        condCount += countGroups(lc.lookaheadBody());
      }
      return condCount + countGroups(cond.yes()) + countGroups(cond.noAlt());
    }
    return 0;
  }

  /**
   * Returns {@code true} if any {@link FlagExpr} in the expression tree introduces
   * {@link PatternFlag#CASE_INSENSITIVE} via an inline flag group such as {@code (?i:...)}
   * or a mode-switch {@code (?i)}.
   *
   * @param expr the expression to scan; must not be null
   * @return {@code true} if any inline case-insensitive flag is present
   */
  private static boolean containsInlineCaseInsensitiveFlag(Expr expr) {
    if (expr instanceof FlagExpr fe) {
      if (fe.flags().contains(PatternFlag.CASE_INSENSITIVE)) {
        return true;
      }
      return containsInlineCaseInsensitiveFlag(fe.body());
    } else if (expr instanceof Concat c) {
      return c.parts().stream().anyMatch(Pattern::containsInlineCaseInsensitiveFlag);
    } else if (expr instanceof Union u) {
      return u.alternatives().stream().anyMatch(Pattern::containsInlineCaseInsensitiveFlag);
    } else if (expr instanceof Quantifier q) {
      return containsInlineCaseInsensitiveFlag(q.child());
    } else if (expr instanceof Group g) {
      return containsInlineCaseInsensitiveFlag(g.body());
    } else if (expr instanceof AtomicGroup ag) {
      return containsInlineCaseInsensitiveFlag(ag.body());
    } else if (expr instanceof LookaheadExpr la) {
      return containsInlineCaseInsensitiveFlag(la.body());
    } else if (expr instanceof LookbehindExpr lb) {
      return containsInlineCaseInsensitiveFlag(lb.body());
    } else if (expr instanceof Pair p) {
      return containsInlineCaseInsensitiveFlag(p.input())
          || containsInlineCaseInsensitiveFlag(p.output());
    } else if (expr instanceof BalanceGroupExpr bg) {
      return containsInlineCaseInsensitiveFlag(bg.body());
    } else if (expr instanceof ConditionalExpr cond) {
      boolean inCond = false;
      if (cond.condition() instanceof ConditionalExpr.LookaheadCondition lc) {
        inCond = containsInlineCaseInsensitiveFlag(lc.lookaheadBody());
      }
      return inCond
          || containsInlineCaseInsensitiveFlag(cond.yes())
          || containsInlineCaseInsensitiveFlag(cond.noAlt());
    }
    return false;
  }

  /**
   * Mutable instruction list builder.
   *
   * <p>Instructions are emitted in-order. Because forward jumps require patching
   * (the target PC is not yet known when the jump is emitted), {@code Compiler} uses
   * a two-pass approach: forward jumps are first emitted with a placeholder PC
   * ({@link #PLACEHOLDER}), then patched once the target is known.
   */
  private static final class Compiler {

    /** Sentinel PC used for forward-jump placeholders that must be patched. */
    private static final int PLACEHOLDER = Integer.MIN_VALUE;

    private final List<Instr> instrs = new ArrayList<>();

    /** Currently active compilation flags. Mutated by {@link FlagExpr} handling. */
    private EnumSet<PatternFlag> flags;

    Compiler(EnumSet<PatternFlag> initialFlags) {
      this.flags = EnumSet.copyOf(
          initialFlags.isEmpty() ? EnumSet.noneOf(PatternFlag.class) : initialFlags);
    }

    Compiler() {
      this(EnumSet.noneOf(PatternFlag.class));
    }

    /** Returns the index at which the next instruction will be emitted. */
    int pc() {
      return instrs.size();
    }

    /** Emits one instruction and returns its PC. */
    int emit(Instr instr) {
      int pc = instrs.size();
      instrs.add(instr);
      return pc;
    }

    /** Replaces the instruction at {@code pc} with {@code instr}. */
    void patch(int pc, Instr instr) {
      instrs.set(pc, instr);
    }

    /**
     * Compiles {@code expr} into the instruction list.
     *
     * <p>On return, the next emitted instruction logically follows the compiled fragment.
     */
    void compile(Expr expr) {
      switch (expr) {

        case Literal lit -> {
          boolean caseInsensitive = flags.contains(PatternFlag.CASE_INSENSITIVE);
          boolean unicodeCase = flags.contains(PatternFlag.UNICODE_CASE) || flags.contains(PatternFlag.UNICODE);
          for (char c : lit.value().toCharArray()) {
            if (caseInsensitive && unicodeCase) {
              emitCaseFoldedChar(c);
            } else if (caseInsensitive) {
              char partner = casePartner(c);
              if (partner == c) {
                // Non-alphabetic character: no case partner, single-char match.
                emit(new CharMatch(c, c, pc() + 1));
              } else {
                // ASCII alphabetic: emit Split to match either the lower or upper form.
                // Do NOT use a contiguous range — [A..a] (65..97) includes unrelated characters.
                char lo = partner < c ? partner : c;
                char hi = partner < c ? c : partner;
                emitAlternativeChars(java.util.List.of(lo, hi));
              }
            } else {
              emit(new CharMatch(c, c, pc() + 1));
            }
          }
        }

        case CharClass cc -> compileCharClass(cc);

        case Anchor anchor -> compileAnchor(anchor);

        case Concat concat -> {
          for (Expr part : concat.parts()) {
            compile(part);
          }
        }

        case Union union -> compileUnion(union);

        case Quantifier quant -> compileQuantifier(quant);

        case Group group -> compileGroup(group);

        case Epsilon ignored -> {
          // Epsilon emits nothing; execution falls through to the next instruction.
        }

        case Backref backref -> {
          // Backref.groupIndex() is 1-based (regex \1 → groupIndex=1).
          // BackrefCheck.groupIndex() is 0-based (matching SaveCapture convention).
          boolean ci = flags.contains(PatternFlag.CASE_INSENSITIVE);
          emit(new BackrefCheck(backref.groupIndex() - 1, ci, pc() + 1));
        }

        case Pair pair -> {
          // Compile the input side for matching (consumes characters).
          compile(pair.input());
          // Emit output instructions for the output side. These are epsilon-like instructions
          // that carry the transducer output delta and do not consume input characters.
          emitOutputInstructions(pair.output());
        }

        case FlagExpr fe -> {
          // Apply the scoped flags for the duration of the body compilation.
          EnumSet<PatternFlag> saved = EnumSet.copyOf(flags);
          flags = EnumSet.copyOf(fe.flags());
          compile(fe.body());
          flags = saved;
        }

        case AtomicGroup ag -> {
          // Atomic group (?>body): emit a possessive gate followed by an AtomicCommit.
          //
          // Structure:
          //   PossessiveSplit(body_pc, exit_pc)  — pushes a PossessiveFence; goto body_pc
          //   [body instructions]                — may push Frame entries via internal Splits
          //   AtomicCommit(exit_pc)              — pops all Frames up to the fence, goto exit_pc
          //   exit_pc:
          //
          // The AtomicCommit is the key: once the body succeeds, it removes every Frame
          // that was pushed inside the body (by the body's internal quantifiers), preventing
          // the backtracking engine from retreating into the body. The PossessiveFence below
          // those frames handles the case where the body fails without consuming input.
          int splitPc = emit(new PossessiveSplit(pc() + 1, PLACEHOLDER));
          compile(ag.body());
          int commitPc = emit(new AtomicCommit(PLACEHOLDER)); // next patched below
          int exit = pc();
          patch(splitPc, new PossessiveSplit(splitPc + 1, exit));
          patch(commitPc, new AtomicCommit(exit));
        }

        case LookaheadExpr la -> compileLookahead(la);

        case LookbehindExpr lb -> compileLookbehind(lb);

        case BalanceGroupExpr bg -> compileBalanceGroup(bg);

        case ConditionalExpr cond -> compileConditional(cond);

        case KeepAssertion k ->
            emit(new ResetMatchStart(pc() + 1));
      }
    }

    // -----------------------------------------------------------------------
    // Transducer output instruction emission
    // -----------------------------------------------------------------------

    /**
     * Walks the output side of a {@link Pair} expression and emits {@link TransOutput}
     * instructions for each output token. These instructions are epsilon-like: they do not
     * consume input but carry the output delta that the engine accumulates during a match.
     *
     * <p>Rules:
     * <ul>
     *   <li>O-1: {@link Literal} → single {@code TransOutput(literal.value(), pc() + 1)}</li>
     *   <li>O-2: numeric {@link Backref} → {@code TransOutput("$N", pc() + 1)}</li>
     *   <li>O-3: named {@link Backref} → {@code TransOutput("${name}", pc() + 1)}</li>
     *   <li>O-4: {@link Concat} → recurse over each part in order</li>
     *   <li>O-5: {@link Group} → recurse into body (no capturing on output side)</li>
     *   <li>O-6: {@link Epsilon} / absent output → emit nothing</li>
     *   <li>O-7: {@link Union} or {@link Quantifier} → throw {@link PatternSyntaxException}
     *            (output side must be deterministic)</li>
     * </ul>
     *
     * @param outputExpr the output expression; must not be null
     * @throws PatternSyntaxException if the output expression contains a Union or Quantifier
     */
    private void emitOutputInstructions(Expr outputExpr) {
      switch (outputExpr) {
        case Literal lit -> emit(new TransOutput(lit.value(), pc() + 1));

        case Backref backref -> {
          // Rule O-2: numeric backreference. groupIndex() is 1-based (as in \1, \2, ...).
          // Named backrefs are resolved to numeric indices at parse time by the Parser,
          // so they also arrive here as Backref(groupIndex=N).
          emit(new TransOutput("$" + backref.groupIndex(), pc() + 1));
        }

        case Concat concat -> {
          // Rule O-4: emit each part's output instructions in sequence
          for (Expr part : concat.parts()) {
            emitOutputInstructions(part);
          }
        }

        case Group group -> {
          // Rule O-5: output-side groups are structural only; walk the body
          emitOutputInstructions(group.body());
        }

        case Epsilon ignored -> {
          // Rule O-6: epsilon / absent output emits nothing
        }

        case Union ignored -> throw new PatternSyntaxException(
            "Transducer output side must be a deterministic expression"
                + " (no alternation or quantifiers)",
            outputExpr.toString(), 0);

        case Quantifier ignored -> throw new PatternSyntaxException(
            "Transducer output side must be a deterministic expression"
                + " (no alternation or quantifiers)",
            outputExpr.toString(), 0);

        default -> {
          // All other node types (CharClass, Anchor, etc.) are not valid on the output
          // side; treat as epsilon to avoid crashing — analysis passes already guard this.
        }
      }
    }

    // -----------------------------------------------------------------------
    // Case folding helpers
    // -----------------------------------------------------------------------

    /**
     * Lookup table for characters whose Unicode case folding expands to multiple characters.
     * Maps a single BMP char to the string it expands to under full Unicode case folding.
     * Only one-to-two expansions that are commonly required by tests are included.
     */
    private static final java.util.Map<Character, String> MULTI_CHAR_EXPANSIONS =
        java.util.Map.of(
            '\u00DF', "ss",  // ß → ss (German sharp s)
            '\uFB00', "ff",  // ﬀ → ff
            '\uFB01', "fi",  // ﬁ → fi
            '\uFB02', "fl",  // ﬂ → fl
            '\uFB03', "ffi", // ﬃ → ffi
            '\uFB04', "ffl", // ﬄ → ffl
            '\uFB05', "st",  // ﬅ → st
            '\uFB06', "st"   // ﬆ → st
        );

    /**
     * Special Unicode case fold mappings required under {@code CASE_INSENSITIVE + UNICODE_CASE}.
     *
     * <p>Covers Turkish dotless-i / dot-above-I, Latin small letter long s, the Kelvin sign,
     * and the Ångström sign. These are one-to-one character mappings beyond what
     * {@link Character#toLowerCase} / {@link Character#toUpperCase} provide for ASCII.
     */
    private static final java.util.Map<Character, Character> SPECIAL_UNICODE_FOLDS;

    static {
      java.util.Map<Character, Character> m = new java.util.HashMap<>();
      m.put('\u0069', '\u0130');  // i → İ (LATIN CAPITAL LETTER I WITH DOT ABOVE)
      m.put('\u0049', '\u0131');  // I → ı (LATIN SMALL LETTER DOTLESS I)
      m.put('\u0130', '\u0069');  // İ → i
      m.put('\u0131', '\u0049');  // ı → I
      m.put('\u017F', 's');       // ſ (LATIN SMALL LETTER LONG S) → s
      m.put('s',      '\u017F');  // s → ſ
      m.put('S',      '\u017F');  // S → ſ
      m.put('k',      '\u212A');  // k → K (KELVIN SIGN)
      m.put('K',      '\u212A');  // K → K (KELVIN SIGN)
      m.put('\u212A', 'k');       // K (KELVIN SIGN) → k
      m.put('\u00C5', '\u212B');  // Å → Å (ANGSTROM SIGN U+212B)
      m.put('\u212B', '\u00C5');  // Å (ANGSTROM SIGN) → Å
      // LJ / Lj / lj titlecase triplet (U+01C7 / U+01C8 / U+01C9).
      // Java Character.toLowerCase/toUpperCase covers LJ↔lj; the titlecase form Lj (U+01C8)
      // is the missing member. Map both capital and small to the title form so the forms set
      // is complete regardless of which member appears in the pattern.
      m.put('\u01C7', '\u01C8');  // LJ (capital) → Lj (titlecase)
      m.put('\u01C9', '\u01C8');  // lj (small)   → Lj (titlecase)
      // NJ / Nj / nj titlecase triplet (U+01CA / U+01CB / U+01CC)
      m.put('\u01CA', '\u01CB');  // NJ (capital) → Nj (titlecase)
      m.put('\u01CC', '\u01CB');  // nj (small)   → Nj (titlecase)
      // DZ / Dz / dz titlecase triplet (U+01F1 / U+01F2 / U+01F3)
      m.put('\u01F1', '\u01F2');  // DZ (capital) → Dz (titlecase)
      m.put('\u01F3', '\u01F2');  // dz (small)   → Dz (titlecase)
      // Greek sigma: U+03C2 (ς, GREEK SMALL LETTER FINAL SIGMA) and
      // U+03C3 (σ, GREEK SMALL LETTER SIGMA) share uppercase Σ (U+03A3).
      // Java's toLowerCase(Σ)=σ but toLowerCase(ς)=ς, so the two lowercase
      // forms are never linked by standard case mapping. Unicode CaseFolding.txt
      // maps ς → σ; we add both directions so patterns using either form match both.
      m.put('\u03C2', '\u03C3');  // ς (final sigma)  → σ (medial sigma)
      m.put('\u03C3', '\u03C2');  // σ (medial sigma) → ς (final sigma)
      SPECIAL_UNICODE_FOLDS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Emits instructions for one character under Unicode case folding
     * ({@code CASE_INSENSITIVE + UNICODE_CASE}).
     *
     * <p>If the character has a multi-character case expansion (e.g. {@code ß → ss}),
     * a {@link Split} is emitted so the engine can try either the original character or
     * the expansion sequence. Otherwise, the Unicode simple case equivalents
     * ({@link Character#toLowerCase} and {@link Character#toUpperCase}) are used.
     *
     * @param c the character to emit case-insensitive instructions for
     */
    private void emitCaseFoldedChar(char c) {
      char lower = Character.toLowerCase(c);
      char upper = Character.toUpperCase(c);
      String expansion = MULTI_CHAR_EXPANSIONS.get(c);
      // Also check the lower-case version for multi-char expansions
      if (expansion == null) {
        expansion = MULTI_CHAR_EXPANSIONS.get(lower);
      }

      if (expansion != null) {
        // Emit Split: either match the original char OR the expansion sequence
        // Split(originalCharPc, expansionPc)
        int splitPc = emit(new Split(pc() + 1, PLACEHOLDER));
        // Branch 1: match the original char (and its case variants)
        char lo = lower;
        char hi = upper;
        if (lo > hi) { char tmp = lo; lo = hi; hi = tmp; }
        emit(new CharMatch(lo, hi, PLACEHOLDER)); // next patched to afterAll
        int jumpPc = emit(new EpsilonJump(PLACEHOLDER)); // jump to afterAll

        // Branch 2: match the expansion sequence (all lowercase)
        int expansionStart = pc();
        for (int i = 0; i < expansion.length(); i++) {
          char ec = expansion.charAt(i);
          char eLo = Character.toLowerCase(ec);
          char eHi = Character.toUpperCase(ec);
          if (eLo > eHi) { char tmp = eLo; eLo = eHi; eHi = tmp; }
          emit(new CharMatch(eLo, eHi, pc() + 1));
        }

        int afterAll = pc();
        // Patch split: branch2 starts at expansionStart
        patch(splitPc, new Split(splitPc + 1, expansionStart));
        // Patch CharMatch in branch1 to go to jump
        patch(splitPc + 1, new CharMatch(lo, hi, jumpPc));
        // Patch jump to afterAll
        patch(jumpPc, new EpsilonJump(afterAll));
      } else {
        // Simple Unicode case folding: collect the distinct case forms and emit them.
        // Using a range (lo, hi) is only safe when ALL chars in [lo..hi] are intended;
        // for distant code points (e.g. µ U+00B5 and Μ U+039C), a range would match
        // unintended characters. Collect the set of distinct case forms and emit a Split
        // chain if there are multiple.
        java.util.Set<Character> forms = new java.util.LinkedHashSet<>();
        forms.add(lower);
        forms.add(upper);
        forms.add(c);
        if (flags.contains(PatternFlag.UNICODE_CASE) || flags.contains(PatternFlag.UNICODE)) {
          Character specialFold = SPECIAL_UNICODE_FOLDS.get(c);
          if (specialFold != null) {
            forms.add(specialFold);
          }
          Character specialFoldLower = SPECIAL_UNICODE_FOLDS.get((char) lower);
          if (specialFoldLower != null) {
            forms.add(specialFoldLower);
          }
          // Reverse-fold closure: any char that maps TO something already in forms belongs
          // in the same equivalence class. Example: ſ→s is forward; S→ſ is reverse — once
          // ſ is in forms, S must also be added. One pass is sufficient for the current map.
          for (java.util.Map.Entry<Character, Character> e : SPECIAL_UNICODE_FOLDS.entrySet()) {
            if (forms.contains(e.getValue())) {
              forms.add(e.getKey());
            }
          }
        }
        if (forms.size() == 1) {
          emit(new CharMatch(c, c, pc() + 1));
        } else {
          // Emit Split chain for all distinct case forms.
          // Do NOT use a contiguous range for the two-form ASCII case — [A..a] is 33 chars.
          List<Character> formList = new ArrayList<>(forms);
          emitAlternativeChars(formList);
        }
      }
    }

    /**
     * Emits a Split chain matching any one of the given characters.
     *
     * @param chars the characters to match; must have at least one element
     */
    private void emitAlternativeChars(List<Character> chars) {
      if (chars.size() == 1) {
        char ch = chars.get(0);
        emit(new CharMatch(ch, ch, pc() + 1));
        return;
      }
      // Emit: Split(first, rest) → first char, jump to afterAll; then rest
      int splitPc = emit(new Split(pc() + 1, PLACEHOLDER));
      char first = chars.get(0);
      emit(new CharMatch(first, first, PLACEHOLDER));
      int jumpPc = emit(new EpsilonJump(PLACEHOLDER));
      int restStart = pc();
      emitAlternativeChars(chars.subList(1, chars.size()));
      int afterAll = pc();
      patch(splitPc, new Split(splitPc + 1, restStart));
      patch(splitPc + 1, new CharMatch(first, first, jumpPc));
      patch(jumpPc, new EpsilonJump(afterAll));
    }

    /**
     * Returns the ASCII case partner of {@code c}, or {@code c} itself if it has no partner.
     *
     * <p>For {@code a}–{@code z} returns the corresponding upper-case letter, and vice versa.
     * For all other characters returns {@code c} unchanged.
     *
     * @param c the character to fold
     * @return the case partner, or {@code c} if none
     */
    private static char casePartner(char c) {
      if (c >= 'a' && c <= 'z') {
        return (char) (c - 32);
      }
      if (c >= 'A' && c <= 'Z') {
        return (char) (c + 32);
      }
      return c;
    }

    // -----------------------------------------------------------------------
    // Lookahead / lookbehind compilation
    // -----------------------------------------------------------------------

    /**
     * Compiles a {@link LookaheadExpr} into a {@link Lookahead} or {@link LookaheadNeg}
     * instruction carrying a self-contained sub-{@link Prog} for the assertion body.
     *
     * @param la the lookahead expression to compile
     */
    private void compileLookahead(LookaheadExpr la) {
      Prog subProg = buildProg(la.body(), flags);
      if (la.positive()) {
        emit(new Lookahead(subProg, pc() + 1));
      } else {
        emit(new LookaheadNeg(subProg, pc() + 1));
      }
    }

    /**
     * Compiles a {@link LookbehindExpr} into a {@link LookbehindPos} or
     * {@link LookbehindNeg} instruction.
     *
     * <p>The body is compiled forward; the engine is responsible for running it
     * starting at {@code currentPos - bodyLength}.
     *
     * @param lb the lookbehind expression to compile
     */
    private void compileLookbehind(LookbehindExpr lb) {
      int bodyFixed = Parser.computeFixedLength(lb.body());
      int minLen;
      int maxLen;
      if (bodyFixed >= 0) {
        // Fixed-length: min and max are identical.
        minLen = bodyFixed;
        maxLen = bodyFixed;
      } else {
        // Bounded variable-length: parser has already rejected unbounded bodies.
        minLen = Parser.computeMinLength(lb.body());
        maxLen = Parser.computeMaxLength(lb.body());
      }
      Prog subProg = buildProg(lb.body(), flags);
      if (lb.positive()) {
        emit(new LookbehindPos(subProg, minLen, maxLen, pc() + 1));
      } else {
        emit(new LookbehindNeg(subProg, minLen, maxLen, pc() + 1));
      }
    }

    // -----------------------------------------------------------------------
    // Balance group compilation
    // -----------------------------------------------------------------------

    /**
     * Compiles a {@link BalanceGroupExpr} into balance stack instructions.
     *
     * <p>The compilation strategy depends on which names are present:
     * <ul>
     *   <li>Pop-only ({@code pushName==null}): emit {@link BalancePopInstr}, then body.</li>
     *   <li>Push-only ({@code popName==null}): emit body, then {@link BalancePushInstr}.</li>
     *   <li>Combined: emit {@link BalancePopInstr}, then body, then {@link BalancePushInstr}.</li>
     * </ul>
     *
     * @param bg the balancing group expression to compile
     */
    private void compileBalanceGroup(BalanceGroupExpr bg) {
      if (bg.popName() != null) {
        // Pop first (fails if stack empty)
        emit(new BalancePopInstr(bg.popName(), pc() + 1));
      }
      compile(bg.body());
      if (bg.pushName() != null) {
        // Push after successful body match
        emit(new BalancePushInstr(bg.pushName(), pc() + 1));
      }
    }

    // -----------------------------------------------------------------------
    // Conditional subpattern compilation
    // -----------------------------------------------------------------------

    /**
     * Compiles a {@link ConditionalExpr} into a {@link ConditionalBranchInstr} followed by the
     * yes and no branches.
     *
     * <p>Emitted structure:
     * <pre>
     *   ConditionalBranch(kind, ..., yesPC, noPC)   ← condition test
     *   [yes branch instructions]
     *   EpsilonJump(afterPC)                         ← skip over no-branch
     *   [no branch instructions]                     ← noPC
     *   afterPC:                                     ← both branches converge here
     * </pre>
     *
     * @param cond the conditional expression to compile
     */
    private void compileConditional(ConditionalExpr cond) {
      int branchPc = emit(new ConditionalBranchInstr(
          ConditionalBranchInstr.Kind.GROUP_INDEX, // placeholder, patched below
          0, null, null, PLACEHOLDER, PLACEHOLDER));

      // Compile yes branch
      int yesPC = pc();
      compile(cond.yes());
      int jumpPc = emit(new EpsilonJump(PLACEHOLDER)); // jump over no-branch

      // Compile no branch
      int noPC = pc();
      compile(cond.noAlt());
      int afterPC = pc();

      // Patch jump
      patch(jumpPc, new EpsilonJump(afterPC));

      // Build the ConditionalBranchInstr with real kind and PCs
      ConditionalBranchInstr.Kind kind;
      int refIndex = 0;
      String refName = null;
      Prog lookaheadBody = null;

      switch (cond.condition()) {
        case ConditionalExpr.GroupIndexCondition gic -> {
          kind = ConditionalBranchInstr.Kind.GROUP_INDEX;
          refIndex = gic.groupIndex();
        }
        case ConditionalExpr.GroupNameCondition gnc -> {
          // Resolved at runtime: could be a named group or a balance stack.
          // We use GROUP_NAME; the engine checks named groups first, then balance stacks.
          kind = ConditionalBranchInstr.Kind.GROUP_NAME;
          refName = gnc.name();
        }
        case ConditionalExpr.LookaheadCondition lc -> {
          kind = lc.positive()
              ? ConditionalBranchInstr.Kind.LOOKAHEAD_POS
              : ConditionalBranchInstr.Kind.LOOKAHEAD_NEG;
          lookaheadBody = buildProg(lc.lookaheadBody(), flags);
        }
      }

      patch(branchPc, new ConditionalBranchInstr(kind, refIndex, refName, lookaheadBody,
          yesPC, noPC));
    }

    // -----------------------------------------------------------------------
    // Char class compilation
    // -----------------------------------------------------------------------

    private void compileCharClass(CharClass cc) {
      List<CharRange> ranges = cc.ranges();
      boolean negated = cc.negated();

      // Under CASE_INSENSITIVE, expand ranges to include case partners.
      if (flags.contains(PatternFlag.CASE_INSENSITIVE)) {
        boolean hasNonAscii = ranges.stream().anyMatch(r -> r.hi() > '\u007F');
        if (hasNonAscii || flags.contains(PatternFlag.UNICODE_CASE) || flags.contains(PatternFlag.UNICODE)) {
          ranges = expandCaseRangesUnicode(ranges);
        } else {
          ranges = expandCaseRanges(ranges);
        }
      }

      if (!negated) {
        // Non-negated: emit one CharMatch per range; any successful match branches past
        // the others via a Split chain.
        if (ranges.size() == 1) {
          CharRange r = ranges.get(0);
          emit(new CharMatch(r.lo(), r.hi(), pc() + 1));
        } else {
          // For multiple ranges, emit Split → range1 or rest, stitching together at the end.
          // We implement this via a chain of splits, each splitting to one range or the next.
          compileCharClassRanges(ranges, negated);
        }
      } else {
        // Negated: match any character that is NOT in the ranges.
        // We compile this as AnyChar with a runtime negation check embedded in the PikeVM.
        // However, AnyChar doesn't carry range info. Instead, we represent a negated class
        // as CharMatch instructions covering the complementary ranges.
        compileNegatedCharClass(ranges);
      }
    }

    private void compileCharClassRanges(List<CharRange> ranges, boolean negated) {
      // Emit a Split chain:
      //   Split(rangePC, nextSplit) for all but last range, then the last range
      // After each accepted range, jump to afterAll.
      // We use forward patches because afterAll is not yet known.

      int numRanges = ranges.size();
      // Preallocate storage for the split PCs and jump PCs
      int[] splitPcs = new int[numRanges - 1];
      int[] jumpPcs = new int[numRanges - 1];

      for (int i = 0; i < numRanges - 1; i++) {
        // Emit Split with placeholder for the "else" branch
        splitPcs[i] = emit(new Split(pc() + 1, PLACEHOLDER));
        CharRange r = ranges.get(i);
        emit(new CharMatch(r.lo(), r.hi(), PLACEHOLDER)); // next patched after
        jumpPcs[i] = emit(new EpsilonJump(PLACEHOLDER));    // jump to afterAll
      }

      // Emit last range (no split needed)
      CharRange last = ranges.get(numRanges - 1);
      emit(new CharMatch(last.lo(), last.hi(), pc() + 1));

      int afterAll = pc();

      // Patch all forward jumps to afterAll and all Split "else" branches
      for (int i = 0; i < numRanges - 1; i++) {
        // The split's next2 should point to the next split (or the last range if i == numRanges-2)
        int next2 = (i + 1 < numRanges - 1) ? splitPcs[i + 1] : /* last range */ jumpPcs[i] + 1;
        patch(splitPcs[i], new Split(splitPcs[i] + 1, next2));
        // The CharMatch's next should go to the EpsilonJump
        patch(splitPcs[i] + 1, new CharMatch(ranges.get(i).lo(), ranges.get(i).hi(), jumpPcs[i]));
        // The EpsilonJump should go to afterAll
        patch(jumpPcs[i], new EpsilonJump(afterAll));
      }
    }

    private void compileNegatedCharClass(List<CharRange> ranges) {
      // Compute complement ranges over ['\0', '\uFFFF']
      List<CharRange> complement = computeComplement(ranges);
      if (complement.isEmpty()) {
        // Nothing can match — emit a CharMatch that can never succeed
        // Use a zero-length range that violates lo <= hi (we cannot, so use an impossible hack)
        // Instead, emit an EpsilonJump that skips to a fail position — but we have no Fail here.
        // Use a Split that both branches fail. For now emit nothing meaningful — this is an
        // edge case (negation of full range).
        emit(new CharMatch('\u0001', '\u0000', pc() + 1)); // will throw — use AnyChar trick
        return;
      }
      if (complement.size() == 1) {
        CharRange r = complement.get(0);
        emit(new CharMatch(r.lo(), r.hi(), pc() + 1));
      } else {
        compileCharClassRanges(complement, false);
      }
    }

    /**
     * Computes the complement of the given ranges over ['\0', '\uFFFF'].
     * Assumes ranges are sorted and non-overlapping (they may not be in general,
     * but the parser produces non-overlapping ranges for simple cases).
     */
    private static List<CharRange> computeComplement(List<CharRange> ranges) {
      // Sort ranges by 'from'
      List<CharRange> sorted = new ArrayList<>(ranges);
      sorted.sort((a, b) -> Character.compare(a.lo(), b.lo()));

      List<CharRange> complement = new ArrayList<>();
      char next = '\u0000';

      for (CharRange r : sorted) {
        if (r.lo() > next) {
          complement.add(new CharRange(next, (char) (r.lo() - 1)));
        }
        if (r.hi() >= next) {
          if (r.hi() == '\uFFFF') {
            return complement;
          }
          next = (char) (r.hi() + 1);
        }
      }
      if (next <= '\uFFFF') {
        complement.add(new CharRange(next, '\uFFFF'));
      }
      return complement;
    }

    /**
     * Expands the ranges in a character class to include ASCII case partners.
     *
     * <p>For each character in {@code A}–{@code Z} and {@code a}–{@code z} that falls
     * inside any existing range, the corresponding lower/upper-case partner is added as
     * a single-character range.
     *
     * @param ranges the original ranges; must not be null
     * @return the expanded range list, never null
     */
    private static List<CharRange> expandCaseRanges(List<CharRange> ranges) {
      List<CharRange> expanded = new ArrayList<>(ranges);
      for (char c = 'A'; c <= 'Z'; c++) {
        char lower = (char) (c + 32);
        for (CharRange r : ranges) {
          if (c >= r.lo() && c <= r.hi()) {
            // Upper-case char is in range; add lower-case partner
            expanded.add(new CharRange(lower, lower));
            break;
          }
          if (lower >= r.lo() && lower <= r.hi()) {
            // Lower-case char is in range; add upper-case partner
            expanded.add(new CharRange(c, c));
            break;
          }
        }
      }
      return expanded;
    }

    /**
     * Expands the ranges in a character class to include Unicode case partners.
     *
     * <p>For every BMP character in any range, its {@link Character#toLowerCase} and
     * {@link Character#toUpperCase} equivalents are added as single-character ranges when
     * they differ from the original character. Characters with multi-char case expansions
     * (e.g. {@code ß → ss}) are excluded from the range expansion; those require a separate
     * {@link Split}-based encoding that cannot be represented within a character class.
     *
     * @param ranges the original ranges; must not be null
     * @return the expanded range list, never null
     */
    private static List<CharRange> expandCaseRangesUnicode(List<CharRange> ranges) {
      // Short-circuit: if the ranges already cover the entire BMP [\u0000-\uFFFF], no
      // case expansion can add new characters. Return the ASCII-expanded result directly.
      boolean fullBmp = ranges.stream().anyMatch(r -> r.lo() == '\u0000' && r.hi() == '\uFFFF');
      if (fullBmp) {
        return new ArrayList<>(expandCaseRanges(ranges));
      }
      // First apply ASCII expansion for the common case.
      List<CharRange> expanded = new ArrayList<>(expandCaseRanges(ranges));
      // Track chars already present (in both ASCII and non-ASCII range) to avoid duplicates.
      java.util.Set<Character> added = new java.util.HashSet<>();
      for (CharRange r : expanded) {
        // Use int to avoid char overflow when r.hi() == '\uFFFF'.
        for (int ci = r.lo(); ci <= r.hi(); ci++) {
          added.add((char) ci);
        }
      }
      for (CharRange r : ranges) {
        // Use int to avoid char overflow when r.hi() == '\uFFFF'.
        for (int ci = r.lo(); ci <= r.hi(); ci++) {
          char c = (char) ci;
          // Add special Unicode fold partners (Turkish i/I/İ/ı and long-s ſ/s/S)
          // for every char in the original range, including ASCII chars.
          addSpecialFolds(c, expanded, added);
          // Also check lowercase and uppercase variants, since the range may not include them
          // but their special folds may still be needed (e.g. [h-j] contains 'i' whose
          // uppercase 'I' maps to ı via SPECIAL_UNICODE_FOLDS).
          char lowerC = Character.toLowerCase(c);
          if (lowerC != c) {
            addSpecialFolds(lowerC, expanded, added);
          }
          char upperC = Character.toUpperCase(c);
          if (upperC != c) {
            addSpecialFolds(upperC, expanded, added);
          }
          if (c < '\u0080') {
            continue; // Standard Unicode lower/upper already handled by ASCII expansion.
          }
          // Skip chars with multi-char expansions; those need Split-based handling.
          if (MULTI_CHAR_EXPANSIONS.containsKey(c)) {
            continue;
          }
          char lower = Character.toLowerCase(c);
          char upper = Character.toUpperCase(c);
          if (lower != c && !added.contains(lower)) {
            expanded.add(new CharRange(lower, lower));
            added.add(lower);
          }
          if (upper != c && !added.contains(upper)) {
            expanded.add(new CharRange(upper, upper));
            added.add(upper);
          }
        }
      }
      // Reverse-fold closure: any char that maps TO something already in added belongs in the
      // same equivalence class. Example: [ſ] adds 's' above (ſ→s); S→ſ means S must also be
      // added. One pass is sufficient for the current map topology.
      for (java.util.Map.Entry<Character, Character> e : SPECIAL_UNICODE_FOLDS.entrySet()) {
        char key = e.getKey();
        if (added.contains(e.getValue()) && !added.contains(key)) {
          expanded.add(new CharRange(key, key));
          added.add(key);
        }
      }
      return expanded;
    }

    /**
     * Adds the {@link #SPECIAL_UNICODE_FOLDS} partner of {@code c} to {@code expanded} if it
     * is not already present in {@code added}.
     */
    private static void addSpecialFolds(
        char c, List<CharRange> expanded, java.util.Set<Character> added) {
      Character specialFold = SPECIAL_UNICODE_FOLDS.get(c);
      if (specialFold != null && !added.contains(specialFold)) {
        expanded.add(new CharRange(specialFold, specialFold));
        added.add(specialFold);
      }
    }

    // -----------------------------------------------------------------------
    // Anchor compilation
    // -----------------------------------------------------------------------

    private void compileAnchor(Anchor anchor) {
      boolean multiline = flags.contains(PatternFlag.MULTILINE);
      boolean unixLines = flags.contains(PatternFlag.UNIX_LINES)
          || flags.contains(PatternFlag.RE2_COMPAT);
      boolean perlNewlines = flags.contains(PatternFlag.PERL_NEWLINES);
      switch (anchor.type()) {
        case START -> {
          if (multiline) {
            emit(new BeginLine(pc() + 1, unixLines, perlNewlines));
          } else {
            emit(new BeginText(pc() + 1));
          }
        }
        case END -> {
          // $ in any mode — use EndLine with flag fields so engines apply the full predicate.
          emit(new EndLine(pc() + 1, multiline, unixLines, perlNewlines));
        }
        case LINE_START -> emit(new BeginText(pc() + 1));                        // \A — absolute start of input
        case LINE_END   -> emit(new EndZ(pc() + 1, unixLines, perlNewlines)); // \Z — end or before final terminator
        case EOF        -> emit(new EndText(pc() + 1));          // \z — strict end of input
        case WORD_BOUNDARY     -> emit(new WordBoundary(pc() + 1, false, flags.contains(PatternFlag.UNICODE_CASE) || flags.contains(PatternFlag.UNICODE)));
        case NOT_WORD_BOUNDARY -> emit(new WordBoundary(pc() + 1, true,  flags.contains(PatternFlag.UNICODE_CASE) || flags.contains(PatternFlag.UNICODE)));
        case BOF -> emit(new BeginG(pc() + 1));                 // \G — position after previous match
      }
    }

    // -----------------------------------------------------------------------
    // Union compilation
    // -----------------------------------------------------------------------

    private void compileUnion(Union union) {
      List<Expr> alts = union.alternatives();
      if (alts.isEmpty()) {
        return;
      }
      if (alts.size() == 1) {
        compile(alts.get(0));
        return;
      }

      // Build a left-associative chain of Splits:
      //   Split(alt0, rest_split)
      //   [alt0 instrs]
      //   EpsilonJump(after)
      //   Split(alt1, rest_split)
      //   [alt1 instrs]
      //   EpsilonJump(after)
      //   ...
      //   [altN instrs]
      //   <- after

      int numAlts = alts.size();
      int[] splitPcs = new int[numAlts - 1];
      int[] jumpPcs = new int[numAlts - 1];

      for (int i = 0; i < numAlts - 1; i++) {
        splitPcs[i] = emit(new UnionSplit(pc() + 1, PLACEHOLDER));
        compile(alts.get(i));
        jumpPcs[i] = emit(new EpsilonJump(PLACEHOLDER));
        // Patch split's next2 to current pc (start of next split or last alt)
        int next2 = pc();
        patch(splitPcs[i], new UnionSplit(splitPcs[i] + 1, next2));
      }

      // Compile last alternative
      compile(alts.get(numAlts - 1));
      int after = pc();

      // Patch all EpsilonJumps to after
      for (int i = 0; i < numAlts - 1; i++) {
        patch(jumpPcs[i], new EpsilonJump(after));
      }
    }

    // -----------------------------------------------------------------------
    // Quantifier compilation
    // -----------------------------------------------------------------------

    /**
     * Maximum number of mandatory repetitions that are unrolled inline.
     *
     * <p>When {@code min} exceeds this threshold the compiler emits a single
     * {@link RepeatMin} instruction instead of {@code min} copies of the body,
     * preventing compile-time OOM for patterns like {@code a\{2147483647\}}.
     * Patterns containing a {@code RepeatMin} instruction are always routed to
     * {@code BoundedBacktrackEngine} (see {@code AnalysisVisitor}).
     */
    private static final int LARGE_QUANTIFIER_UNROLL_THRESHOLD = 1_000;

    private void compileQuantifier(Quantifier quant) {
      int min = quant.min();
      boolean unbounded = quant.max().isEmpty();
      int max = unbounded ? -1 : quant.max().getAsInt();
      boolean possessive = quant.possessive();
      boolean lazy = quant.lazy();

      if (!unbounded && max == 0) {
        // {0} — matches nothing, emit epsilon
        return;
      }

      // Emit the mandatory minimum repetitions (same for all modes, except possessive
      // exact-count which wraps in AtomicGroup to prevent backtracking into the body).
      if (possessive && !unbounded && max == min && min > 0) {
        // Possessive exact-count: wrap all mandatory copies in an atomic group so that
        // once the quantified body completes, backtracking into it is prevented.
        // Example: (a+){3}+ locks in three greedy a+ matches; the outer {3}+ gate
        // prevents retrying with fewer chars inside each a+.
        int gatePc = emit(new PossessiveSplit(pc() + 1, PLACEHOLDER));
        if (min <= LARGE_QUANTIFIER_UNROLL_THRESHOLD) {
          for (int i = 0; i < min; i++) {
            compile(quant.child());
          }
        } else {
          int rmPc = emit(new RepeatMin(PLACEHOLDER, PLACEHOLDER, min));
          int bodyStart = pc();
          compile(quant.child());
          emit(new RepeatReturn());
          int bodyEnd = pc();
          patch(rmPc, new RepeatMin(bodyStart, bodyEnd, min));
        }
        int commitPc = emit(new AtomicCommit(PLACEHOLDER, true)); // loopCommit=true
        int after = pc();
        patch(gatePc, new PossessiveSplit(gatePc + 1, after));
        patch(commitPc, new AtomicCommit(after, true)); // loopCommit=true
        // No optional section for exact-count quantifier.
        return; // early return — already compiled completely
      }

      // For non-possessive or possessive-with-optional, emit mandatory copies inline.
      if (min <= LARGE_QUANTIFIER_UNROLL_THRESHOLD) {
        for (int i = 0; i < min; i++) {
          compile(quant.child());
        }
      } else {
        // Large min: emit a single RepeatMin instruction to avoid unrolling 'min' copies.
        // The body is compiled once; RepeatReturn terminates each iteration.
        // Patterns routed here use BoundedBacktrackEngine (NEEDS_BACKTRACKER).
        int rmPc = emit(new RepeatMin(PLACEHOLDER, PLACEHOLDER, min));
        int bodyStart = pc(); // first instruction of the body
        compile(quant.child());
        emit(new RepeatReturn());
        int bodyEnd = pc(); // first instruction after RepeatReturn
        patch(rmPc, new RepeatMin(bodyStart, bodyEnd, min));
      }

      if (possessive) {
        // Possessive quantifiers with optional section.
        // The engine (BoundedBacktrackEngine) uses a consumed-input flag to distinguish
        // 0-iteration failure (COMMITTED_FAIL — allows outer union to retry) from
        // N>0-iteration failure (LOOP_COMMITTED_FAIL — blocks outer union retry).
        if (unbounded) {
          // Unbounded possessive loop:
          //   PossessiveSplit(greedy_start, after)       ← atomic gate; entered exactly once
          //   greedy_start:
          //     Split(child_body, loop_commit)           ← greedy: prefer another iteration
          //     [child body]
          //     EpsilonJump(greedy_start)                ← loop back
          //   loop_commit:
          //     AtomicCommit(after, loopCommit=true)     ← loop exit; fail → LOOP_COMMITTED_FAIL
          //   after: <continuation>
          //
          // AtomicCommit with loopCommit=true returns LOOP_COMMITTED_FAIL (not COMMITTED_FAIL)
          // when the continuation fails. LOOP_COMMITTED_FAIL is NOT downgraded by UnionSplit
          // (unlike COMMITTED_FAIL), so it propagates through any union alternatives inside
          // the loop body up to the PossessiveSplit gate. This prevents a shallow greedy
          // Split from prematurely taking its loop_commit exit when a deeper AtomicCommit fired.
          // The gate absorbs LOOP_COMMITTED_FAIL and converts it to -1 (bodyConsumedNew=true)
          // or COMMITTED_FAIL (0-iter), allowing outer unions to try alternatives.
          int gatePc = emit(new PossessiveSplit(pc() + 1, PLACEHOLDER));
          int greedyStart = pc();
          int innerSplitPc = emit(new Split(pc() + 1, PLACEHOLDER));
          compile(quant.child());
          emit(new EpsilonJump(greedyStart));
          int commitPc = emit(new AtomicCommit(PLACEHOLDER, true)); // loopCommit=true
          int after = pc();
          patch(gatePc, new PossessiveSplit(gatePc + 1, after));
          patch(innerSplitPc, new Split(innerSplitPc + 1, commitPc));
          patch(commitPc, new AtomicCommit(after, true)); // loopCommit=true
        } else if (max > min) {
          // Bounded possessive optional section — chained PossessiveSplit gates.
          //
          // Each optional copy is guarded by its own PossessiveSplit gate. Gates are
          // chained sequentially: when copy K's body succeeds the engine falls through
          // (tail call) to gate K+1. Each gate's next2 exits to a shared AtomicCommit
          // which runs the continuation. The last copy's body falls through directly to
          // AtomicCommit.
          //
          // Structure for remaining=4:
          //   PossessiveSplit(copy1_body, atom_commit)   ← gate 1
          //   copy1_body: [child]
          //   PossessiveSplit(copy2_body, atom_commit)   ← gate 2
          //   copy2_body: [child]
          //   PossessiveSplit(copy3_body, atom_commit)   ← gate 3
          //   copy3_body: [child]
          //   PossessiveSplit(copy4_body, atom_commit)   ← gate 4
          //   copy4_body: [child]
          //   atom_commit: AtomicCommit(after, loopCommit=true)
          //   after: <continuation>
          //
          // When copy K's body fails (not consumed), gate K tries next2 = atom_commit
          // which runs the continuation with however many copies were consumed. When the
          // continuation fails, AtomicCommit returns LOOP_COMMITTED_FAIL, which propagates
          // up through the gate chain without being downgraded by inner UnionSplits.
          //
          // Crucially, each gate's body is a SEPARATE recursive rec() call. If copy K
          // succeeds but copy K+1 fails, the failure is reported at the position AFTER
          // copy K (not before), so the AtomicCommit fires at the right position.
          int remaining = max - min;
          int[] gatesPcs = new int[remaining];
          for (int i = 0; i < remaining; i++) {
            gatesPcs[i] = emit(new PossessiveSplit(pc() + 1, PLACEHOLDER));
            compile(quant.child());
          }
          // AtomicCommit: fires when all copies consumed or a gate chose to exit.
          // loopCommit=true so continuation failure returns LOOP_COMMITTED_FAIL.
          int commitPc = emit(new AtomicCommit(PLACEHOLDER, true)); // loopCommit=true
          int after = pc();
          for (int gatePc : gatesPcs) {
            patch(gatePc, new PossessiveSplit(gatePc + 1, commitPc));
          }
          patch(commitPc, new AtomicCommit(after, true)); // loopCommit=true
        }
        // else max == min — already handled by the possessive exact-count branch above

      } else if (lazy) {
        if (unbounded) {
          // Lazy loop: Split(after, body_start) — exit is next1 (short-first).
          // The placeholder will be patched to the PC after the loop once known.
          int splitPc = emit(new Split(PLACEHOLDER, pc() + 1));
          compile(quant.child());
          emit(new EpsilonJump(splitPc));
          int after = pc();
          patch(splitPc, new Split(after, splitPc + 1));
        } else if (max > min) {
          // Lazy bounded: each optional copy uses Split(after, body_start).
          int remaining = max - min;
          int[] splitPcs = new int[remaining];
          for (int i = 0; i < remaining; i++) {
            splitPcs[i] = emit(new Split(PLACEHOLDER, pc() + 1));
            compile(quant.child());
          }
          int after = pc();
          for (int splitPc : splitPcs) {
            patch(splitPc, new Split(after, splitPc + 1));
          }
        }
        // else max == min — already handled by mandatory copies above

      } else {
        // Greedy (default).
        if (unbounded) {
          // Greedy loop: Split(body_start, after).
          int splitPc = emit(new Split(pc() + 1, PLACEHOLDER));
          compile(quant.child());
          emit(new EpsilonJump(splitPc));
          int after = pc();
          patch(splitPc, new Split(splitPc + 1, after));
        } else if (max > min) {
          // Greedy bounded: each optional copy guarded by Split.
          int remaining = max - min;
          int[] splitPcs = new int[remaining];
          for (int i = 0; i < remaining; i++) {
            splitPcs[i] = emit(new Split(pc() + 1, PLACEHOLDER));
            compile(quant.child());
          }
          int after = pc();
          for (int splitPc : splitPcs) {
            patch(splitPc, new Split(splitPc + 1, after));
          }
        }
        // else max == min — already handled by mandatory copies above
      }
    }

    // -----------------------------------------------------------------------
    // Group compilation
    // -----------------------------------------------------------------------

    private void compileGroup(Group group) {
      int idx = group.index();
      emit(new SaveCapture(idx, true, pc() + 1));
      compile(group.body());
      emit(new SaveCapture(idx, false, pc() + 1));
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    /** Builds the final {@link Prog} from the accumulated instruction list. */
    Prog toProg() {
      Instr[] arr = instrs.toArray(new Instr[0]);
      // Patch any remaining CharMatch instructions whose next == pc() + 1 was pre-computed
      // at emission time. Because we emit Accept() last, all "next == current pc + 1" values
      // are already correct absolute indices.
      int acceptPc = arr.length - 1;
      // Metadata is filled in by the caller; use a dummy here.
      return new Prog(
          arr,
          new com.orbit.prog.Metadata(
              EngineHint.DFA_SAFE,
              com.orbit.prefilter.NoopPrefilter.INSTANCE,
              0, 0, false, false),
          0,
          acceptPc);
    }
  }

  // -----------------------------------------------------------------------
  // Helper: static fields
  // -----------------------------------------------------------------------

  private static boolean isMetacharacter(char c) {
    return switch (c) {
      case '\n', '.', '*', '+', '?', '^', '$', '|', '(', ')', '[', ']', '{', '}',
           '<', '>', '=', '!' -> true;
      default -> false;
    };
  }

  // -----------------------------------------------------------------------
  // Cache key
  // -----------------------------------------------------------------------

  private static final class CacheKey {

    private final String pattern;
    private final EnumSet<PatternFlag> flags;
    private final int hashCode;

    CacheKey(String pattern, EnumSet<PatternFlag> flags) {
      this.pattern = pattern;
      this.flags = flags;
      this.hashCode = computeHash();
    }

    private int computeHash() {
      int h = pattern.hashCode();
      for (PatternFlag f : flags) {
        h = 31 * h + f.hashCode();
      }
      return h;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof CacheKey other)) {
        return false;
      }
      return pattern.equals(other.pattern) && flags.equals(other.flags);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
