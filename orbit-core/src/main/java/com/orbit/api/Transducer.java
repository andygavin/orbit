package com.orbit.api;

import com.orbit.hir.AnalysisVisitor;
import com.orbit.hir.HirNode;
import com.orbit.parse.Backref;
import com.orbit.parse.Concat;
import com.orbit.parse.Epsilon;
import com.orbit.parse.Expr;
import com.orbit.parse.Group;
import com.orbit.parse.Literal;
import com.orbit.parse.Pair;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.prog.AnyByte;
import com.orbit.prog.AnyChar;
import com.orbit.prog.AtomicCommit;
import com.orbit.prog.BackrefCheck;
import com.orbit.prog.BalanceCheckInstr;
import com.orbit.prog.BalancePopInstr;
import com.orbit.prog.BalancePushInstr;
import com.orbit.prog.BeginG;
import com.orbit.prog.BeginLine;
import com.orbit.prog.BeginText;
import com.orbit.prog.ByteMatch;
import com.orbit.prog.ByteRangeMatch;
import com.orbit.prog.CompileResult;
import com.orbit.prog.ConditionalBranchInstr;
import com.orbit.prog.EndLine;
import com.orbit.prog.EndText;
import com.orbit.prog.EndZ;
import com.orbit.prog.Instr;
import com.orbit.prog.Lookahead;
import com.orbit.prog.LookaheadNeg;
import com.orbit.prog.LookbehindNeg;
import com.orbit.prog.LookbehindPos;
import com.orbit.prog.MatchResult;
import com.orbit.prog.PossessiveSplit;
import com.orbit.prog.Prog;
import com.orbit.prog.RepeatMin;
import com.orbit.prog.RepeatReturn;
import com.orbit.prog.ResetMatchStart;
import com.orbit.prog.WordBoundary;
import com.orbit.transducer.TransducerGraph;
import com.orbit.util.PatternFlag;
import com.orbit.util.TransducerFlag;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Immutable compiled transducer that maps input strings to output strings.
 *
 * <p>A transducer is compiled from an expression of the form {@code input:output}, where
 * {@code input} is a regular expression and {@code output} is a deterministic expression that
 * may reference capturing groups via backreferences ({@code \1}, {@code \k<name>}).
 *
 * <p>A transducer compiled from a plain regex with no {@code :} separator is an
 * <em>identity transducer</em>: {@link #applyUp} returns the matched substring and
 * {@link #invert} returns a transducer equivalent to this one.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 *
 * @see #compile(String, TransducerFlag...)
 */
public final class Transducer implements Serializable {

  /**
   * The compiled NFA program. May be null only for composed transducers that do not use the
   * NFA engine (they delegate to component transducers). Never null for directly compiled
   * transducers.
   */
  private final Prog prog;

  /**
   * The original {@link Pair} AST node, retained for {@link #invert()}. Non-null if and only if
   * the transducer was compiled from an expression containing a {@code :} separator. Null for
   * identity transducers and composed transducers.
   */
  private final Pair originalPair;

  /**
   * Defensive copy of the compilation flags. Never null.
   */
  private final TransducerFlag[] flags;

  /**
   * When non-null, this transducer is a composed one-shot chain. {@link #applyUp} delegates to
   * {@code composedFirst.applyUp} then {@code composedSecond.applyUp}. All structural operations
   * ({@link #invert}, {@link #applyDown}) are forbidden on composed transducers.
   */
  private final Transducer composedFirst;

  /**
   * The second transducer in a composed chain. Non-null when {@link #composedFirst} is non-null.
   */
  private final Transducer composedSecond;

  /**
   * True if the output side is literal-only AND the Prog contains no anchor or lookaround
   * instructions. When true, structural compose and invert use the TransducerGraph path.
   */
  private final boolean graphEligible;

  /** Constructor for directly compiled transducers. */
  private Transducer(Prog prog, Pair originalPair, boolean graphEligible, TransducerFlag[] flags) {
    this.prog = Objects.requireNonNull(prog, "prog must not be null");
    this.originalPair = originalPair; // nullable
    this.graphEligible = graphEligible;
    this.flags = Arrays.copyOf(flags, flags.length);
    this.composedFirst = null;
    this.composedSecond = null;
  }

  /** Constructor for graph-derived transducers (compose/invert result). */
  private Transducer(Prog prog, boolean graphEligible, TransducerFlag[] flags) {
    this.prog = Objects.requireNonNull(prog, "prog must not be null");
    this.originalPair = null; // graph-derived; no AST
    this.graphEligible = graphEligible;
    this.flags = Arrays.copyOf(flags, flags.length);
    this.composedFirst = null;
    this.composedSecond = null;
  }

  /** Constructor for Tier 1 composed transducers (runtime chain). */
  private Transducer(Transducer first, Transducer second) {
    this.prog = null; // composed transducers delegate; no NFA execution
    this.originalPair = null;
    this.graphEligible = false; // Tier 1 runtime chains are not graph-eligible
    this.flags = new TransducerFlag[0];
    this.composedFirst = Objects.requireNonNull(first, "first must not be null");
    this.composedSecond = Objects.requireNonNull(second, "second must not be null");
  }

  // -----------------------------------------------------------------------
  // Factory method
  // -----------------------------------------------------------------------

  /**
   * Compiles a transducer expression.
   *
   * <p>Expressions of the form {@code input:output} produce a transducer that maps strings
   * matched by {@code input} to strings produced by {@code output}. The {@code output} side may
   * contain literal text and backreferences ({@code \1}, {@code \2}, …). Expressions without a
   * {@code :} separator produce an identity transducer.
   *
   * @param transducerExpr the transducer expression; must not be null
   * @param flags          optional compilation flags; must not be null
   * @return a non-null compiled transducer
   * @throws NullPointerException     if {@code transducerExpr} or {@code flags} is null
   * @throws RuntimeException         wrapping a {@link PatternSyntaxException} on parse error,
   *                                  cyclic output expression, or unsupported output constructs
   */
  public static Transducer compile(String transducerExpr, TransducerFlag... flags) {
    Objects.requireNonNull(transducerExpr, "transducerExpr must not be null");
    Objects.requireNonNull(flags, "flags must not be null");

    try {
      // Step 1: parse the full expression, treating ':' at nesting depth 0 as the
      // transducer separator between input and output sides.
      Expr expr = parseTransducerExpr(transducerExpr);

      // Step 2: run the full nine-pass analysis pipeline.
      HirNode hir = AnalysisVisitor.analyze(expr);

      // Step 3: build the compiled program through the correct pipeline, which sets
      // isTransducer = true in Metadata when the root expression is a Pair (Stage 6.2).
      CompileResult result = Pattern.buildTransducerCompileResult(
          expr, hir, EnumSet.noneOf(PatternFlag.class));

      // Step 4: retain the Pair AST if present, for invert() / applyDown().
      Pair originalPair = (expr instanceof Pair p) ? p : null;

      // Step 5: compute graph-eligibility for Tier 2 structural operations.
      boolean outputLiteral = (originalPair != null) && isLiteralOutput(originalPair.output());
      boolean progEligible = isProgEligible(result.prog());
      boolean graphEligible = outputLiteral && progEligible;

      return new Transducer(result.prog(), originalPair, graphEligible, flags);

    } catch (PatternSyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  // -----------------------------------------------------------------------
  // Core transformation methods
  // -----------------------------------------------------------------------

  /**
   * Applies the transducer to the full input string and returns the output.
   *
   * <p>The entire input must match the transducer's input pattern; a partial match is treated
   * as no match. For find-first semantics over a long string, use {@link #tokenize}.
   *
   * <p>If the transducer has no output side (identity transducer), returns the matched
   * substring itself.
   *
   * @param input the input string; must not be null
   * @return the transducer output string; never null
   * @throws NullPointerException if {@code input} is null
   * @throws TransducerException  if {@code input} does not fully match the input pattern
   */
  public String applyUp(String input) {
    Objects.requireNonNull(input, "input must not be null");
    // Composed transducer: delegate to the chain.
    if (composedFirst != null) {
      String intermediate = composedFirst.applyUp(input);
      return composedSecond.applyUp(intermediate);
    }
    MatchResult result = Pattern.executeFullMatch(prog, input);
    if (!result.matches()) {
      throw new TransducerException("Input does not match transducer pattern");
    }
    // C-8: if no TransOutput instructions ran (identity transducer), return the matched text.
    if (result.output() != null) {
      return result.output();
    }
    // Identity: return the matched substring.
    return input.substring(result.start(), result.end());
  }

  /**
   * Attempts to apply the transducer to the full input string without throwing on mismatch.
   *
   * <p>Returns {@link Optional#empty()} when the input does not fully match.
   *
   * @param input the input string; must not be null
   * @return the transducer output wrapped in {@code Optional}, or {@code Optional.empty()}
   * @throws NullPointerException if {@code input} is null
   */
  public Optional<String> tryApplyUp(String input) {
    Objects.requireNonNull(input, "input must not be null");
    // Composed transducer: delegate to the chain.
    if (composedFirst != null) {
      Optional<String> intermediate = composedFirst.tryApplyUp(input);
      if (intermediate.isEmpty()) {
        return Optional.empty();
      }
      return composedSecond.tryApplyUp(intermediate.get());
    }
    MatchResult result = Pattern.executeFullMatch(prog, input);
    if (!result.matches()) {
      return Optional.empty();
    }
    if (result.output() != null) {
      return Optional.of(result.output());
    }
    return Optional.of(input.substring(result.start(), result.end()));
  }

  /**
   * Applies the transducer in reverse: finds the input that produces the given output.
   *
   * <p>Semantically equivalent to {@code this.invert().applyUp(output)}.
   *
   * @param output the output string to invert; must not be null
   * @return the input string that maps to {@code output} under this transducer
   * @throws NullPointerException              if {@code output} is null
   * @throws NonInvertibleTransducerException  if this is a composed transducer (no original
   *                                           pair) or the output side is not a valid input
   * @throws TransducerException               if {@code output} does not match the inverted
   *                                           pattern
   */
  public String applyDown(String output) {
    Objects.requireNonNull(output, "output must not be null");
    // Tier 1 runtime chains (composedFirst != null, not graphEligible) are still non-invertible.
    if (composedFirst != null && !graphEligible) {
      throw new NonInvertibleTransducerException(
          "Composed transducers cannot be inverted for applyDown.");
    }
    return invert().applyUp(output);
  }

  // -----------------------------------------------------------------------
  // Inversion and composition
  // -----------------------------------------------------------------------

  /**
   * Returns a new transducer whose {@link #applyUp} applies the inverse mapping.
   *
   * <p>The returned transducer has its input and output sides swapped. Calling
   * {@link #invert()} on the result returns a transducer equivalent to this one.
   *
   * <p>This transducer must have been compiled from an expression containing a {@code :}
   * separator. Composed transducers (created via {@link #compose}) are not invertible.
   *
   * @return a non-null inverted transducer
   * @throws NonInvertibleTransducerException if this is a composed transducer or the swapped
   *                                          expression fails the acyclicity check
   */
  public Transducer invert() {
    if (graphEligible) {
      // Tier 2 structural inversion — O(V+E), no AST required.
      TransducerGraph g = TransducerGraph.fromProg(prog).rmEpsilon().invert();
      return new Transducer(g.toProg(), /*graphEligible=*/true, flags);
    }
    if (composedFirst != null) {
      // Tier 1 composed transducers: composedFirst != null, originalPair == null.
      // Must check this BEFORE the originalPair == null guard below.
      throw new NonInvertibleTransducerException(
          "Composed transducers cannot be inverted. "
              + "Invert each component before composing.");
    }
    if (originalPair == null) {
      // Identity transducer (no Pair, not graph-eligible — should not normally occur,
      // but guard defensively).
      return this;
    }
    // Tier 1: recompile from swapped originalPair AST (existing behaviour).
    Pair swapped = new Pair(originalPair.output(), originalPair.input(), originalPair.weight());
    try {
      HirNode swappedHir = AnalysisVisitor.analyze(swapped);
      CompileResult result = Pattern.buildTransducerCompileResult(
          swapped, swappedHir, EnumSet.noneOf(PatternFlag.class));
      return new Transducer(result.prog(), swapped, /*graphEligible=*/false, flags);
    } catch (PatternSyntaxException e) {
      throw new NonInvertibleTransducerException(
          "Transducer is not invertible: output side is not a valid input expression");
    } catch (RuntimeException e) {
      throw new NonInvertibleTransducerException(
          "Transducer is not invertible: " + e.getMessage());
    }
  }

  /**
   * Composes this transducer with {@code other}, returning a one-shot composed transducer.
   *
   * <p>The composed transducer's {@link #applyUp} is equivalent to
   * {@code other.applyUp(this.applyUp(s))}.
   *
   * <p>For literal-output transducers (graph-eligible), structural composition is performed via
   * {@link com.orbit.transducer.TransducerGraph}. For all other transducers the result is a
   * runtime chain. Calling {@link #invert()}, {@link #applyDown}, or {@link #compose} on a
   * runtime-chain result throws {@link NonInvertibleTransducerException} or
   * {@link TransducerCompositionException}.
   *
   * @param other the second transducer; must not be null
   * @return a composed transducer; never null
   * @throws NullPointerException              if {@code other} is null
   * @throws TransducerCompositionException    if the transducers are alphabet-incompatible
   */
  public Transducer compose(Transducer other) {
    Objects.requireNonNull(other, "other must not be null");

    if (this.graphEligible && other.graphEligible) {
      // Tier 2 structural composition via TransducerGraph.
      TransducerGraph g1 = TransducerGraph.fromProg(this.prog).rmEpsilon();
      TransducerGraph g2 = TransducerGraph.fromProg(other.prog).rmEpsilon();
      TransducerGraph composed = g1.compose(g2).rmEpsilon();
      Prog newProg = composed.toProg();
      return new Transducer(newProg, /*graphEligible=*/true, this.flags);
    }

    // Tier 1 fallback — runtime chain (existing behaviour).
    return new Transducer(this, other);
  }

  // -----------------------------------------------------------------------
  // Tokenization
  // -----------------------------------------------------------------------

  /**
   * Tokenizes {@code input} into a list of {@link Token} objects using find-first semantics.
   *
   * <p>Processes {@code input} left-to-right, finding non-overlapping matches. Each match
   * produces an {@link OutputToken}; gaps between matches produce {@link MatchToken} entries
   * with type {@code "gap"}. Zero-length gaps produce no token.
   *
   * <p>If no match is found anywhere in {@code input}, returns a single-element list
   * containing a gap token covering the entire input.
   *
   * @param input the input string; must not be null
   * @return an unmodifiable list of tokens partitioning {@code input}; never null
   * @throws NullPointerException if {@code input} is null
   */
  public List<Token> tokenize(String input) {
    Objects.requireNonNull(input, "input must not be null");
    if (composedFirst != null) {
      throw new TransducerCompositionException(
          "tokenize is not supported on composed transducers");
    }
    List<Token> tokens = new ArrayList<>();
    int pos = 0;
    int len = input.length();
    while (pos <= len) {
      MatchResult result = Pattern.executeFind(prog, input, pos, len);
      if (!result.matches()) {
        break;
      }
      int matchStart = result.start();
      int matchEnd = result.end();
      // Emit gap token before the match if there is unmatched text.
      if (matchStart > pos) {
        tokens.add(new MatchToken("gap", input.substring(pos, matchStart), pos, matchStart));
      }
      // Emit the match token with transducer output.
      String matchedValue = input.substring(matchStart, matchEnd);
      String outputValue = result.output() != null ? result.output() : matchedValue;
      tokens.add(new OutputToken("match", matchedValue, matchStart, matchEnd, outputValue));
      // Advance past the match. Guard against infinite loops on zero-length matches.
      pos = (matchEnd > matchStart) ? matchEnd : matchEnd + 1;
    }
    // Emit trailing gap if there is remaining unmatched text.
    if (pos < len) {
      tokens.add(new MatchToken("gap", input.substring(pos, len), pos, len));
    }
    // If no matches were found at all, return a single gap covering the entire input.
    if (tokens.isEmpty()) {
      return List.of(new MatchToken("gap", input, 0, len));
    }
    return List.copyOf(tokens);
  }

  /**
   * Returns a lazily-evaluated iterator over {@link Token} objects produced by tokenizing
   * the content read from {@code input}.
   *
   * <p>The iterator reads the full content of the {@link Reader} into a string on first
   * access, then delegates to the same logic as {@link #tokenize(String)}.
   *
   * @param input the reader to tokenize; must not be null
   * @return a non-null iterator over tokens
   * @throws NullPointerException   if {@code input} is null
   * @throws UncheckedIOException   if reading from {@code input} throws an {@link IOException}
   */
  public Iterator<Token> tokenizeIterator(Reader input) {
    Objects.requireNonNull(input, "input must not be null");
    return new TokenIterator(this, input);
  }

  /**
   * Returns a lazily-evaluated sequential {@link Stream} of {@link Token} objects produced by
   * tokenizing the content read from {@code input}.
   *
   * <p>The stream is backed by the same logic as {@link #tokenizeIterator(Reader)}. Closing the
   * stream does not close the underlying {@link Reader}.
   *
   * @param input the reader to tokenize; must not be null
   * @return a non-null sequential stream of tokens
   * @throws NullPointerException if {@code input} is null
   */
  public Stream<Token> tokenizeStream(Reader input) {
    Objects.requireNonNull(input, "input must not be null");
    Iterator<Token> it = tokenizeIterator(input);
    return StreamSupport.stream(
        java.util.Spliterators.spliteratorUnknownSize(it, java.util.Spliterator.ORDERED),
        false);
  }

  // -----------------------------------------------------------------------
  // Private parsing helpers
  // -----------------------------------------------------------------------

  /**
   * Parses a transducer expression, splitting on ':' at nesting depth 0 to produce a
   * {@link Pair} node. The ':' separator is ignored inside parentheses and character classes.
   * If no top-level ':' is found, delegates to {@link Parser#parse} and returns a plain expr.
   */
  private static Expr parseTransducerExpr(String source) throws PatternSyntaxException {
    int sep = findTopLevelColon(source);
    if (sep == -1) {
      return Parser.parse(source);
    }
    Expr inputExpr = Parser.parse(source.substring(0, sep));
    Expr outputExpr = Parser.parse(source.substring(sep + 1));
    return new Pair(inputExpr, outputExpr, OptionalDouble.empty());
  }

  /**
   * Returns the index of the first ':' at parenthesis/bracket nesting depth 0, or -1 if none.
   * Backslash escapes and character classes ({@code [...]}) are handled correctly.
   */
  private static int findTopLevelColon(String source) {
    int depth = 0;
    boolean inCharClass = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        i++; // skip the escaped character
        continue;
      }
      if (inCharClass) {
        if (c == ']') {
          inCharClass = false;
        }
        continue;
      }
      if (c == '[') {
        inCharClass = true;
        continue;
      }
      if (c == '(') {
        depth++;
        continue;
      }
      if (c == ')') {
        depth--;
        continue;
      }
      if (c == ':' && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  // -----------------------------------------------------------------------
  // Graph-eligibility helpers
  // -----------------------------------------------------------------------

  /**
   * Returns true if every node in the output expression tree is a literal or epsilon — i.e.,
   * contains no {@link Backref} nodes. Identity transducers (no {@link Pair} node) always pass.
   */
  private static boolean isLiteralOutput(Expr expr) {
    return switch (expr) {
      case Backref ignored -> false;
      case Literal ignored -> true;
      case Epsilon ignored -> true;
      case Concat c -> c.parts().stream().allMatch(Transducer::isLiteralOutput);
      case Group g -> isLiteralOutput(g.body());
      default -> false; // Union/Quantifier/etc. rejected
    };
  }

  /**
   * Returns true if every instruction in {@code prog} is one of the types that can be
   * represented in a {@link TransducerGraph}. Any disqualifying instruction type makes the
   * whole Prog ineligible.
   */
  private static boolean isProgEligible(Prog prog) {
    for (Instr instr : prog.instructions) {
      if (isDisqualifying(instr)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isDisqualifying(Instr instr) {
    return switch (instr) {
      case AnyChar ignored -> true;
      case AnyByte ignored -> true;
      case ByteMatch ignored -> true;
      case ByteRangeMatch ignored -> true;
      case BeginText ignored -> true;
      case EndText ignored -> true;
      case EndZ ignored -> true;
      case BeginLine ignored -> true;
      case EndLine ignored -> true;
      case BeginG ignored -> true;
      case WordBoundary ignored -> true;
      case BackrefCheck ignored -> true;
      case Lookahead ignored -> true;
      case LookaheadNeg ignored -> true;
      case LookbehindPos ignored -> true;
      case LookbehindNeg ignored -> true;
      case PossessiveSplit ignored -> true;
      case AtomicCommit ignored -> true;
      case BalancePushInstr ignored -> true;
      case BalancePopInstr ignored -> true;
      case BalanceCheckInstr ignored -> true;
      case ConditionalBranchInstr ignored -> true;
      case RepeatMin ignored -> true;
      case RepeatReturn ignored -> true;
      case ResetMatchStart ignored -> true;
      default -> false;
    };
  }

  // -----------------------------------------------------------------------
  // Public accessors
  // -----------------------------------------------------------------------

  /**
   * Returns the compiled NFA program.
   *
   * @return the compiled program; may be null for Tier 1 composed transducers
   */
  public Prog prog() {
    return prog;
  }

  // -----------------------------------------------------------------------
  // Nested class: TokenIterator
  // -----------------------------------------------------------------------

  /**
   * Lazily reads a {@link Reader} and iterates over its tokenized {@link Token} objects.
   */
  private static final class TokenIterator implements Iterator<Token> {

    private final Transducer transducer;
    private final Reader reader;
    private List<Token> tokens;
    private int index;

    TokenIterator(Transducer transducer, Reader reader) {
      this.transducer = transducer;
      this.reader = reader;
      this.tokens = null;
      this.index = 0;
    }

    private void ensureLoaded() {
      if (tokens != null) {
        return;
      }
      try {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
          sb.append(buf, 0, n);
        }
        tokens = transducer.tokenize(sb.toString());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public boolean hasNext() {
      ensureLoaded();
      return index < tokens.size();
    }

    @Override
    public Token next() {
      ensureLoaded();
      if (index >= tokens.size()) {
        throw new NoSuchElementException();
      }
      return tokens.get(index++);
    }
  }

  // -----------------------------------------------------------------------
  // Exception types
  // -----------------------------------------------------------------------

  /**
   * Thrown when an input string does not match the transducer's input pattern.
   *
   * <p>This is the checked failure mode of {@link #applyUp}: when the input does not fully
   * match the pattern, this exception is thrown rather than returning {@code null}.
   */
  public static class TransducerException extends RuntimeException {

    /**
     * Creates a {@code TransducerException} with the given message.
     *
     * @param message the detail message; must not be null
     */
    public TransducerException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when an operation requires an invertible transducer but the transducer is not
   * invertible.
   *
   * <p>Composed transducers (created via {@link #compose}) are not invertible. Transducers
   * whose output side is not a valid input expression are also not invertible.
   */
  public static class NonInvertibleTransducerException extends RuntimeException {

    /**
     * Creates a {@code NonInvertibleTransducerException} with the given message.
     *
     * @param message the detail message; must not be null
     */
    public NonInvertibleTransducerException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when two transducers are incompatible for composition, or when an operation that
   * requires a structural (non-composed) transducer is called on a runtime-chain transducer.
   */
  public static class TransducerCompositionException extends RuntimeException {

    /**
     * Creates a {@code TransducerCompositionException} with the given message.
     *
     * @param message the detail message; must not be null
     */
    public TransducerCompositionException(String message) {
      super(message);
    }
  }
}
