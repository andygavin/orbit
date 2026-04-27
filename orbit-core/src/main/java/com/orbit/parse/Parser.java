package com.orbit.parse;

import com.orbit.util.PatternFlag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * LL(1) recursive-descent parser for regular expressions.
 *
 * <p>Handles the following constructs:
 * <ul>
 *   <li>Literals and backslash escapes ({@code \d}, {@code \w}, {@code \s}, {@code \\}, etc.)</li>
 *   <li>Character classes ({@code [...]}, {@code [^...]})</li>
 *   <li>Anchors ({@code ^}, {@code $})</li>
 *   <li>Dot ({@code .})</li>
 *   <li>Quantifiers ({@code ?}, {@code +}, {@code *}, {@code {n}}, {@code {n,}}, {@code {n,m}})</li>
 *   <li>Capturing groups ({@code (...)})</li>
 *   <li>Named groups ({@code (?P<name>...)}, {@code (?<name>...)})</li>
 *   <li>Non-capturing groups ({@code (?:...)})</li>
 *   <li>Lookaheads ({@code (?=...)}, {@code (?!...)})</li>
 *   <li>Alternation ({@code |})</li>
 * </ul>
 *
 * <p>Instances are <em>not</em> thread-safe. Each parse call constructs a fresh {@code Parser}.
 */
public class Parser {

  /**
   * Parses the given regular expression source into an {@link Expr} tree.
   *
   * @param source the regular expression string; must not be null
   * @param flags  optional compilation flags applied as initial flag state
   * @return the parsed expression tree, never null
   * @throws NullPointerException   if {@code source} is null
   * @throws PatternSyntaxException if {@code source} is not a valid regular expression
   */
  public static Expr parse(String source, PatternFlag... flags) throws PatternSyntaxException {
    if (source == null) {
      throw new NullPointerException("source must not be null");
    }
    if (source.isEmpty()) {
      return new Epsilon();
    }

    EnumSet<PatternFlag> flagSet = flags.length == 0
        ? EnumSet.noneOf(PatternFlag.class)
        : EnumSet.copyOf(List.of(flags));

    if (flagSet.contains(PatternFlag.LITERAL)) {
      return new Literal(source);
    }

    Parser parser = new Parser(source, flagSet);
    Expr result = parser.parseExpression();

    if (!parser.isAtEnd()) {
      char unexpected = parser.peek();
      if (unexpected == ')') {
        throw new PatternSyntaxException(
            "Unmatched closing parenthesis", source, parser.cursor);
      }
      throw new PatternSyntaxException(
          "Unexpected character at end of input", source, parser.cursor);
    }

    return result;
  }

  // -----------------------------------------------------------------------
  // Parser state
  // -----------------------------------------------------------------------

  private final String source;
  private int cursor;
  /** Counter for auto-assigning group indices. */
  private int groupCounter;
  /**
   * Current active flag set. Mutated when entering/leaving scoped flag groups.
   * External flags supplied to {@link #parse} are used as the initial state.
   */
  private EnumSet<PatternFlag> activeFlags;
  /** Set of named group names seen so far; used to detect duplicates. */
  private final Set<String> namedGroups = new HashSet<>();
  /**
   * When {@code true}, duplicate named-group names are allowed (Perl semantics for branch
   * reset groups where different alternatives may bind the same slot under different names).
   */
  private boolean inBranchReset = false;
  /**
   * Map from named group name to its 1-based index; populated as named groups are parsed
   * so that {@code \k<name>} can resolve the numeric index.
   */
  private final Map<String, Integer> namedGroupIndices = new HashMap<>();
  /**
   * Set of names that appear as pop targets in {@code (?<-Name>...)} constructs.
   * A named group whose name is in this set should emit a balance-stack push when compiled.
   * Populated by a pre-scan before the main parse.
   */
  private final Set<String> balancePopTargets;

  private Parser(String source, EnumSet<PatternFlag> initialFlags) {
    this.source = source;
    this.balancePopTargets = scanBalancePopTargets(source);
    this.cursor = 0;
    this.groupCounter = 0;
    this.activeFlags = EnumSet.copyOf(
        initialFlags.isEmpty() ? EnumSet.noneOf(PatternFlag.class) : initialFlags);
  }

  // -----------------------------------------------------------------------
  // Primitive helpers
  // -----------------------------------------------------------------------

  private boolean isAtEnd() {
    return cursor >= source.length();
  }

  private char peek() {
    return isAtEnd() ? '\0' : source.charAt(cursor);
  }

  private char peekAt(int offset) {
    int idx = cursor + offset;
    return idx < source.length() ? source.charAt(idx) : '\0';
  }

  private char advance() {
    return isAtEnd() ? '\0' : source.charAt(cursor++);
  }

  private boolean match(char expected) {
    if (isAtEnd() || source.charAt(cursor) != expected) {
      return false;
    }
    cursor++;
    return true;
  }

  private void expect(char expected) throws PatternSyntaxException {
    if (!match(expected)) {
      throw new PatternSyntaxException("Expected '" + expected + "'", source, cursor);
    }
  }

  private int getCursor() {
    return cursor;
  }

  // -----------------------------------------------------------------------
  // Grammar: expression → alternation
  // -----------------------------------------------------------------------

  private Expr parseExpression() throws PatternSyntaxException {
    return parseAlternation();
  }

  /** alternation → concatenation ( '|' concatenation )* */
  private Expr parseAlternation() throws PatternSyntaxException {
    Expr left = parseConcatenation();

    while (!isAtEnd() && peek() == '|') {
      advance(); // consume '|'
      Expr right = parseConcatenation();
      left = new Union(List.of(left, right));
    }

    return left;
  }

  /**
   * concatenation → quantifiedAtom+
   *
   * <p>Stops before '|', ')' or end-of-input so that alternation and group
   * parsing can consume those delimiters.
   *
   * <p>When {@code COMMENTS} mode is active, unescaped ASCII whitespace and
   * {@code #}-to-end-of-line sequences are skipped before each atom.
   *
   * <p>When a standalone inline flag group ({@code (?flags)}) is encountered, the
   * remaining atoms in this concatenation are wrapped in a {@link FlagExpr} carrying
   * the new flag set — modelling the JDK's behaviour where inline flags apply from their
   * position to the end of the enclosing group.
   */
  private Expr parseConcatenation() throws PatternSyntaxException {
    List<Expr> parts = new ArrayList<>();

    while (true) {
      skipCommentsWhitespace();
      // Skip any (?#...) comment groups; they may appear between atoms.
      while (trySkipCommentGroup()) {
        skipCommentsWhitespace();
      }
      if (isAtEnd() || isConcatTerminator()) {
        break;
      }

      // Check for a standalone inline-flag group (?flags) before consuming the atom.
      // Peek-ahead: is this '(' '?' followed by flag letters and ')'?
      EnumSet<PatternFlag> newFlags = tryParseStandaloneFlags();
      if (newFlags != null) {
        // The flags apply from here to end of this concatenation.
        // Save the current flag state, apply new flags, parse remaining atoms.
        EnumSet<PatternFlag> savedFlags = EnumSet.copyOf(activeFlags);
        activeFlags = newFlags;
        Expr remaining = parseConcatenation();
        activeFlags = savedFlags;
        parts.add(remaining.equals(new Epsilon()) ? remaining
            : wrapWithFlags(newFlags, remaining));
        break;
      }

      Expr atom = parseQuantifiedAtom();
      parts.add(atom);
    }

    if (parts.isEmpty()) {
      return new Epsilon();
    }
    return parts.size() == 1 ? parts.get(0) : new Concat(parts);
  }

  /**
   * Wraps {@code body} in a {@link FlagExpr} when the flag set is non-empty.
   * If the flag set is empty, returns {@code body} unchanged.
   *
   * @param flags the flags to apply
   * @param body  the body to wrap
   * @return the (possibly wrapped) expression
   */
  private static Expr wrapWithFlags(EnumSet<PatternFlag> flags, Expr body) {
    return new FlagExpr(flags, body);
  }

  /**
   * Attempts to parse a standalone inline-flag group {@code (?flags)} at the current
   * cursor position without consuming any input on failure.
   *
   * <p>Returns the new merged flag set on success (with the parsed flags added to the
   * current {@code activeFlags}), or {@code null} if the current position does not start
   * a standalone flag group.  On success the cursor is advanced past the closing {@code )}.
   *
   * @return the new flag set, or {@code null} if no standalone flag group is here
   */
  private EnumSet<PatternFlag> tryParseStandaloneFlags() throws PatternSyntaxException {
    // Must see '(', '?', then flag letters, then ')' — NOT '(':
    if (peek() != '(' || peekAt(1) != '?') {
      return null;
    }
    // Scan forward to find the extent
    int i = 2;
    while (true) {
      char c = peekAt(i);
      if (c == ')') {
        // All characters between '?' and ')' must be flag letters or '-'
        if (i == 2) {
          return null; // empty flag group like (?): not a flag group
        }
        // Consume '(', '?', flag letters, ')'
        advance(); // '('
        advance(); // '?'
        EnumSet<PatternFlag> merged = EnumSet.copyOf(activeFlags);
        boolean removing = false;
        while (peek() != ')') {
          char f = advance();
          if (f == '-') {
            removing = true;
          } else {
            applyFlagChar(f, merged, removing);
          }
        }
        advance(); // ')'
        return merged;
      } else if (c == '\0') {
        return null; // end of input
      } else if (Character.isLetter(c) || c == '-') {
        i++;
      } else {
        return null; // not a pure flag group
      }
    }
  }

  /**
   * Applies the effect of a single inline flag character to the given flag set.
   *
   * @param flagChar the inline flag character ({@code i}, {@code m}, {@code s}, {@code x})
   * @param flags    the set to mutate
   * @param remove   when {@code true}, the flag is removed rather than added
   */
  private static void applyFlagChar(char flagChar, EnumSet<PatternFlag> flags, boolean remove) {
    switch (flagChar) {
      case 'i' -> { if (remove) flags.remove(PatternFlag.CASE_INSENSITIVE);
                    else flags.add(PatternFlag.CASE_INSENSITIVE); }
      case 'm' -> { if (remove) flags.remove(PatternFlag.MULTILINE);
                    else flags.add(PatternFlag.MULTILINE); }
      case 's' -> { if (remove) flags.remove(PatternFlag.DOTALL);
                    else flags.add(PatternFlag.DOTALL); }
      case 'x' -> { if (remove) flags.remove(PatternFlag.COMMENTS);
                    else flags.add(PatternFlag.COMMENTS); }
      case 'u' -> { if (remove) flags.remove(PatternFlag.UNICODE_CASE);
                    else flags.add(PatternFlag.UNICODE_CASE); }
      default -> { /* unknown flag letter; ignore */ }
    }
  }

  /**
   * Skips unescaped ASCII whitespace and {@code #}-to-end-of-line when
   * {@code COMMENTS} mode is active.  Does nothing when COMMENTS is not set.
   */
  private void skipCommentsWhitespace() {
    if (!activeFlags.contains(PatternFlag.COMMENTS)) {
      return;
    }
    while (!isAtEnd()) {
      char c = peek();
      if (c == '#') {
        // Skip to end of line (stop before any line terminator; let next iteration skip it).
        while (!isAtEnd() && !isLineTerminator(peek())) {
          advance();
        }
      } else if (c <= 0x7F && Character.isWhitespace(c)) {
        advance();
      } else {
        break;
      }
    }
  }

  private static boolean isLineTerminator(char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
  }

  /** Returns true when concatenation parsing should stop. */
  private boolean isConcatTerminator() {
    char c = peek();
    return c == '|' || c == ')';
  }

  // -----------------------------------------------------------------------
  // Grammar: quantifiedAtom → atom quantifier?
  // -----------------------------------------------------------------------

  /**
   * Parses one atom (possibly a single-char literal), then checks for and
   * applies any quantifier suffix.
   *
   * <p>Comment groups {@code (?#...)} between the atom and its quantifier are skipped.
   */
  private Expr parseQuantifiedAtom() throws PatternSyntaxException {
    Expr atom = parseAtom();
    // Skip any whitespace/comments (in COMMENTS mode) and comment groups that appear
    // between the atom and a potential quantifier.
    skipCommentsWhitespace();
    while (trySkipCommentGroup()) {
      skipCommentsWhitespace();
    }
    return parseQuantifierSuffix(atom);
  }

  /**
   * Wraps {@code atom} in a {@link Quantifier} if a quantifier token follows.
   * Returns {@code atom} unchanged if no quantifier is present.
   */
  private Expr parseQuantifierSuffix(Expr atom) throws PatternSyntaxException {
    // In COMMENTS mode, whitespace and #-comments are ignored between an atom and its quantifier.
    skipCommentsWhitespace();

    if (isAtEnd()) {
      return atom;
    }

    char c = peek();
    int min;
    OptionalInt max;
    boolean possessive = false;

    switch (c) {
      case '?' -> {
        advance();
        min = 0;
        max = OptionalInt.of(1);
      }
      case '*' -> {
        advance();
        min = 0;
        max = OptionalInt.empty();
      }
      case '+' -> {
        advance();
        min = 1;
        max = OptionalInt.empty();
      }
      case '{' -> {
        int saved = cursor;
        // JDK compatibility: '{' not followed by a digit or comma is a PatternSyntaxException.
        // peekAt(1) is the character after '{' (which has not been consumed yet).
        char afterBrace = peekAt(1);
        if (afterBrace != '\0' && !Character.isDigit(afterBrace) && afterBrace != ',') {
          throw new PatternSyntaxException(
              "Illegal repetition", source, cursor);
        }
        BoundedQuantifier bq = tryParseBoundedQuantifier();
        if (bq == null) {
          // Not a valid {n,m} — treat '{' as a literal (return atom unchanged)
          cursor = saved;
          return atom;
        }
        min = bq.min;
        max = bq.max;
      }
      default -> {
        return atom;
      }
    }

    // Consume optional trailing '?' for lazy, '+' for possessive.
    boolean lazy = false;
    if (!isAtEnd() && peek() == '?') {
      advance();
      lazy = true;
    } else if (!isAtEnd() && peek() == '+') {
      advance();
      possessive = true;
    }

    return new Quantifier(atom, min, max, possessive, lazy);
  }

  /** Holder for a parsed {min,max} tuple. */
  private record BoundedQuantifier(int min, OptionalInt max) {}

  /**
   * Tries to parse a {@code {n}}, {@code {n,}}, {@code {n,m}}, or {@code {,m}} quantifier.
   * Returns null and leaves the cursor unchanged on failure.
   *
   * <p>The {@code {,m}} form (leading-comma) is a Perl extension meaning {@code {0,m}}.
   * Whitespace (ASCII spaces and tabs) around the numbers and comma is ignored.
   */
  private BoundedQuantifier tryParseBoundedQuantifier() throws PatternSyntaxException {
    if (!match('{')) {
      return null;
    }
    skipBraceWhitespace();
    int min = tryParseDecimal();
    if (min < 0) {
      // No leading digits — only valid if next char is ',' (the {,N} form).
      if (!match(',')) {
        return null; // bare '{' with no digits or comma — not a quantifier
      }
      skipBraceWhitespace();
      if (match('}')) {
        // {,} is invalid: missing max
        throw new PatternSyntaxException(
            "Illegal repetition: missing max in {,}", source, cursor);
      }
      int max = tryParseDecimal();
      if (max < 0) {
        return null;
      }
      skipBraceWhitespace();
      if (!match('}')) {
        return null;
      }
      return new BoundedQuantifier(0, OptionalInt.of(max));
    }
    skipBraceWhitespace();
    if (match('}')) {
      return new BoundedQuantifier(min, OptionalInt.of(min));
    }
    if (!match(',')) {
      return null;
    }
    skipBraceWhitespace();
    if (match('}')) {
      return new BoundedQuantifier(min, OptionalInt.empty());
    }
    int max = tryParseDecimal();
    if (max < 0) {
      return null;
    }
    skipBraceWhitespace();
    if (!match('}')) {
      return null;
    }
    if (max < min) {
      throw new PatternSyntaxException(
          "Illegal repetition range: max < min", source, cursor);
    }
    return new BoundedQuantifier(min, OptionalInt.of(max));
  }

  /**
   * Skips ASCII spaces and tabs within a brace quantifier (e.g. {@code { 3 , 5 }}).
   */
  private void skipBraceWhitespace() {
    while (!isAtEnd() && (peek() == ' ' || peek() == '\t')) {
      advance();
    }
  }

  /** Parses a non-negative decimal integer; returns -1 if no digits are present. */
  private int tryParseDecimal() {
    if (isAtEnd() || !Character.isDigit(peek())) {
      return -1;
    }
    int value = 0;
    while (!isAtEnd() && Character.isDigit(peek())) {
      int digit = peek() - '0';
      if (value > (Integer.MAX_VALUE - digit) / 10) {
        throw new PatternSyntaxException("Illegal repetition", source, cursor);
      }
      value = value * 10 + (advance() - '0');
    }
    return value;
  }

  // -----------------------------------------------------------------------
  // Grammar: atom
  // -----------------------------------------------------------------------

  /**
   * Parses a single atom: one literal character (or escape), a char class,
   * an anchor, a dot, or a group.  Does NOT parse quantifiers — the caller
   * (parseQuantifiedAtom) does that.
   */
  private Expr parseAtom() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Unexpected end of pattern", source, cursor);
    }

    char ch = advance();

    return switch (ch) {
      case '(' -> parseGroup();
      case '[' -> parseCharClass();
      case '^' -> new Anchor(AnchorType.START);
      case '$' -> new Anchor(AnchorType.END);
      case '.' -> // dot: excludes all line terminators unless DOTALL; only \n excluded when UNIX_LINES, PERL_NEWLINES, or RE2_COMPAT
          activeFlags.contains(PatternFlag.DOTALL)
              ? new CharClass(false, List.of(new CharRange('\u0000', '\uffff')))
              : (activeFlags.contains(PatternFlag.UNIX_LINES) || activeFlags.contains(PatternFlag.PERL_NEWLINES)
                  || activeFlags.contains(PatternFlag.RE2_COMPAT))
                  ? new CharClass(false, List.of(
                      new CharRange('\u0000', '\u0009'),   // excludes \n = \u000A
                      new CharRange('\u000B', '\uffff')))
                  : new CharClass(false, List.of(
                      new CharRange('\u0000', '\u0009'),   // excludes \n = \u000A
                      new CharRange('\u000B', '\u000C'),   // excludes \r = \u000D
                      new CharRange('\u000E', '\u0084'),   // excludes \u0085 (NEL)
                      new CharRange('\u0086', '\u2027'),   // excludes \u2028 (LS)
                      new CharRange('\u202A', '\uffff'))); // excludes \u2029 (PS)
      case '\\' -> parseEscape();
      case ')' -> throw new PatternSyntaxException(
          "Unmatched closing parenthesis", source, cursor - 1);
      case ']' -> new Literal("]");
      case '*', '+', '?' -> throw new PatternSyntaxException(
          "Quantifier without preceding atom", source, cursor - 1);
      default -> parseLiteralRun(ch);
    };
  }

  // -----------------------------------------------------------------------
  // Literal run accumulation
  // -----------------------------------------------------------------------

  /**
   * Collects a run of plain literal characters starting with {@code first} into a single
   * {@link Literal}, stopping early so that the last character in the run is left for
   * the next call when the character after it is a quantifier.
   *
   * <p>In {@code COMMENTS} mode, stops at unescaped whitespace or {@code #} so that
   * {@link #skipCommentsWhitespace()} can handle them.
   *
   * <p>Example: for input {@code abc+}, this method accumulates {@code "ab"} and leaves
   * {@code c+} to be parsed by the next {@link #parseQuantifiedAtom} call.
   */
  private Literal parseLiteralRun(char first) {
    StringBuilder sb = new StringBuilder();
    sb.append(first);

    while (!isAtEnd() && isPlainLiteralChar(peek())) {
      // In COMMENTS mode, stop at whitespace or '#' so the outer loop can skip them.
      if (activeFlags.contains(PatternFlag.COMMENTS)) {
        char next = peek();
        if (next == ' ' || next == '\t' || next == '\f' || next == '\r' || next == '#') {
          break;
        }
      }
      // Peek one more char: if it's a quantifier (possibly after comment groups),
      // the current peek char needs to remain as a separate atom so the quantifier applies
      // to it alone.
      {
        int checkPos = cursor + 1;
        char effectiveNext = peekThroughComments(checkPos);
        if (isQuantifierChar(effectiveNext)) {
          break;
        }
      }
      sb.append(advance());
    }

    return new Literal(sb.toString());
  }

  /** Returns true if {@code ch} is a plain literal character (not a special regex token). */
  private static boolean isPlainLiteralChar(char ch) {
    return switch (ch) {
      case '(', ')', '[', ']', '{', '}', '*', '+', '?', '|', '^', '$', '.', '\\', '\0' -> false;
      default -> true;
    };
  }

  /** Returns true if {@code ch} could begin a quantifier token. */
  private static boolean isQuantifierChar(char ch) {
    return ch == '*' || ch == '+' || ch == '?' || ch == '{';
  }

  // -----------------------------------------------------------------------
  // Comment groups (?#...)
  // -----------------------------------------------------------------------

  /**
   * Attempts to skip a {@code (?#...)} comment group at the current cursor position.
   *
   * <p>If the current position starts {@code (?#}, the cursor is advanced past the matching
   * {@code )}.  If the comment is unterminated, the cursor is restored and a
   * {@link PatternSyntaxException} is thrown.
   *
   * @return {@code true} if a comment group was consumed, {@code false} otherwise
   * @throws PatternSyntaxException if a {@code (?#} is found but is not closed
   */
  private boolean trySkipCommentGroup() throws PatternSyntaxException {
    if (peek() != '(' || peekAt(1) != '?' || peekAt(2) != '#') {
      return false;
    }
    int saved = cursor;
    advance(); // consume '('
    advance(); // consume '?'
    advance(); // consume '#'
    while (!isAtEnd() && peek() != ')') {
      advance();
    }
    if (isAtEnd()) {
      cursor = saved;
      throw new PatternSyntaxException("Sequence (?#... not terminated", source, cursor);
    }
    advance(); // consume ')'
    return true;
  }

  /**
   * Returns the first non-comment-group character at or after position {@code fromPos} in the
   * source string, skipping over any {@code (?#...)} comment groups.
   *
   * @param fromPos the index in the source to start scanning from
   * @return the first meaningful character, or {@code '\0'} if none remains
   */
  private char peekThroughComments(int fromPos) {
    int i = fromPos;
    while (i + 2 < source.length()
        && source.charAt(i) == '('
        && source.charAt(i + 1) == '?'
        && source.charAt(i + 2) == '#') {
      i += 3;
      while (i < source.length() && source.charAt(i) != ')') {
        i++;
      }
      if (i < source.length()) {
        i++; // consume ')'
      }
    }
    return i < source.length() ? source.charAt(i) : '\0';
  }

  // -----------------------------------------------------------------------
  // Groups
  // -----------------------------------------------------------------------

  /**
   * Parses a group after the opening {@code (} has been consumed.
   *
   * <p>Handles capturing, named, non-capturing, lookahead, lookbehind,
   * negative-lookahead, negative-lookbehind, and inline-flag groups.
   *
   * @return the parsed expression for this group
   * @throws PatternSyntaxException if the group syntax is invalid
   */
  private Expr parseGroup() throws PatternSyntaxException {
    // Non-capturing and special groups start with '?'
    if (!match('?')) {
      // Plain capturing group
      int groupIndex = groupCounter++;
      Expr body = parseExpression();
      expect(')');
      return new Group(body, groupIndex, null);
    }

    // We consumed '?'. Look at the next character to decide the group type.
    if (isAtEnd()) {
      throw new PatternSyntaxException("Unexpected end after '(?'", source, cursor);
    }

    char next = peek();

    if (next == '#') {
      // Comment group (?#...): skip all content until ')'.
      advance(); // consume '#'
      while (!isAtEnd() && peek() != ')') {
        advance();
      }
      if (isAtEnd()) {
        throw new PatternSyntaxException("Sequence (?#... not terminated", source, cursor);
      }
      advance(); // consume ')'
      // A comment group matches nothing — return epsilon.
      return new Epsilon();
    }

    if (next == '|') {
      advance(); // consume '|'
      // Branch reset group (?|...) — §6.7.3
      return parseBranchResetGroup();
    }

    if (next == ':') {
      advance(); // consume ':'
      // Non-capturing group (?:...)
      Expr body = parseExpression();
      expect(')');
      return body;
    }

    if (next == '>') {
      advance(); // consume '>'
      // Atomic group (?>...)
      Expr body = parseExpression();
      expect(')');
      return new AtomicGroup(body);
    }

    if (next == '=') {
      advance(); // consume '='
      // Positive lookahead (?=...)
      Expr body = parseExpression();
      expect(')');
      return new LookaheadExpr(body, true);
    }

    if (next == '!') {
      advance(); // consume '!'
      // Negative lookahead (?!...)
      Expr body = parseExpression();
      expect(')');
      return new LookaheadExpr(body, false);
    }

    if (next == '(') {
      advance(); // consume '('
      // Conditional subpattern: (?(condition)yes|no) or (?(condition)yes)
      return parseConditional();
    }

    if (next == '<') {
      advance(); // consume '<'
      // Disambiguate: (?<=...) positive lookbehind, (?<!...) negative lookbehind,
      // (?<name>...) named capture group, (?<-name>...) balance pop, (?<new-old>...) combined.
      char afterLt = peek();
      if (afterLt == '=') {
        advance(); // consume '='
        Expr body = parseExpression();
        expect(')');
        validateVariableLengthBody(body);
        return new LookbehindExpr(body, true);
      }
      if (afterLt == '!') {
        advance(); // consume '!'
        Expr body = parseExpression();
        expect(')');
        validateVariableLengthBody(body);
        return new LookbehindExpr(body, false);
      }
      // .NET balancing group: (?<-Name>pat) or (?<NewName-OldName>pat)
      // Peek ahead to detect a '-' before the closing '>'.
      if (afterLt == '-') {
        // Pop-only form: (?<-OldName>pat)
        advance(); // consume '-'
        String popName = parseBalanceNameUntilClose();
        Expr body = parseExpression();
        expect(')');
        return new BalanceGroupExpr(null, popName, body);
      }
      // Scan to determine if this is (?<NewName-OldName>...) or plain (?<Name>...)
      String firstName = parseBalanceName('\0'); // reads until '-' or '>'; terminator NOT consumed
      if (!isAtEnd() && peek() == '-') {
        advance(); // consume '-'
        if (!isAtEnd() && peek() == '>') {
          // (?<Name->...) — combined push/pop with empty pop name is invalid
          throw new PatternSyntaxException("Empty pop name in balancing group", source, cursor);
        }
        // Combined form: (?<NewName-OldName>pat)
        String oldName = parseBalanceNameUntilClose();
        Expr body = parseExpression();
        expect(')');
        return new BalanceGroupExpr(firstName, oldName, body);
      }
      // Plain named group: stopped at '>'; consume it now.
      expect('>');
      String name = firstName;
      // Validate JDK naming rules: must start with an ASCII letter; body must be
      // ASCII letters or digits only (no underscore).  parseBalanceName() accepted
      // underscores for .NET balance-group disambiguation, so we re-validate here.
      validateJdkGroupName(name);
      // Validate: only plain named groups need the duplicate check and index allocation.
      // Inside branch reset groups, duplicate names across alternatives are allowed (Perl).
      if (!inBranchReset && !namedGroups.add(name)) {
        throw new PatternSyntaxException(
            "Duplicate capturing group name: " + name, source, cursor);
      }
      namedGroups.add(name); // ensure name is always registered
      int groupIndex = groupCounter++;
      namedGroupIndices.put(name, groupIndex + 1); // 1-based for Backref
      Expr groupBody = parseExpression();
      expect(')');
      // If this name is used as a pop target elsewhere in the pattern (i.e., there is a
      // (?<-name>...) construct), the group must also push onto the balance stack.
      if (balancePopTargets.contains(name)) {
        return new BalanceGroupExpr(name, null, new Group(groupBody, groupIndex, name));
      }
      return new Group(groupBody, groupIndex, name);
    }

    if (next == 'P' && peekAt(1) == '<') {
      advance(); // consume 'P'
      advance(); // consume '<'
      // Python-style named group (?P<name>...)
      String name = parseName('>');
      int groupIndex = groupCounter++;
      namedGroupIndices.put(name, groupIndex + 1); // 1-based for Backref
      Expr body = parseExpression();
      expect(')');
      return new Group(body, groupIndex, name);
    }

    if (next == '\'') {
      advance(); // consume '\''
      // Single-quote named group (?'name'...)
      String name = parseSingleQuoteName();
      if (!namedGroups.add(name)) {
        throw new PatternSyntaxException(
            "Duplicate capturing group name: " + name, source, cursor);
      }
      int groupIndex = groupCounter++;
      namedGroupIndices.put(name, groupIndex + 1); // 1-based for Backref
      Expr body = parseExpression();
      expect(')');
      if (balancePopTargets.contains(name)) {
        return new BalanceGroupExpr(name, null, new Group(body, groupIndex, name));
      }
      return new Group(body, groupIndex, name);
    }

    // Inline flag group: (?flags:...) or (?flags-negflags:...) — flags scoped to the body.
    // The standalone (?flags) case is handled by tryParseStandaloneFlags() in parseConcatenation
    // before parseAtom is called. We still need to handle (?flags:body) here.
    if (Character.isLetter(next) || next == '-') {
      // Collect flag letters, tracking whether we have crossed the '-' separator.
      EnumSet<PatternFlag> groupFlags = EnumSet.copyOf(activeFlags);
      boolean removing = false;
      while (!isAtEnd() && (Character.isLetter(peek()) || peek() == '-')) {
        char f = advance();
        if (f == '-') {
          removing = true;
        } else {
          applyFlagChar(f, groupFlags, removing);
        }
      }
      if (match(')')) {
        // (?flags) reached here — should have been handled by tryParseStandaloneFlags.
        // Return epsilon as a fallback (the flags were already applied by the caller).
        return new Epsilon();
      }
      if (match(':')) {
        // (?flags:...) or (?flags-negflags:...) — flags scoped to this group only.
        // Always emit a FlagExpr even when the computed flag set is empty, so that the
        // compiler explicitly switches to the new flag set for the body duration. Without
        // this, removing a flag (e.g. (?-i:...)) would produce an empty flag set that
        // wrapWithFlags would silently drop, leaving the outer flag active inside the body.
        EnumSet<PatternFlag> savedFlags = EnumSet.copyOf(activeFlags);
        activeFlags = groupFlags;
        Expr body = parseExpression();
        activeFlags = savedFlags;
        expect(')');
        return new FlagExpr(groupFlags, body);
      }
      throw new PatternSyntaxException(
          "Expected ')' or ':' after inline flags", source, cursor);
    }

    throw new PatternSyntaxException(
        "Unknown group type after '(?'", source, cursor);
  }

  /**
   * Parses a branch reset group {@code (?|A|B|C)}.
   *
   * <p>Precondition: {@code (?|} has already been consumed by the caller. The cursor is
   * positioned at the first character of the first alternative body (or at {@code )} for an
   * empty group).
   *
   * <p>The group counter is reset to its entry value before each alternative, so capturing
   * groups across alternatives share the same slot indices. After the group closes, the
   * counter advances by the maximum number of groups used in any single alternative.
   *
   * <p>Named groups inside a branch reset group are allowed to reuse the same slot with
   * different names across alternatives (Perl semantics). The duplicate-name check is
   * suppressed for the duration of this group.
   *
   * @return the parsed expression as a {@link Union} of the alternatives (or the single
   *         alternative body directly if there is only one alternative); never null
   * @throws PatternSyntaxException if the group is not terminated by {@code )}
   */
  private Expr parseBranchResetGroup() throws PatternSyntaxException {
    int branchBase = groupCounter;
    int maxGroupsUsed = 0;
    List<Expr> alternatives = new ArrayList<>();

    while (true) {
      // Parse one alternative body. parseConcatenation() stops at '|' or ')'.
      groupCounter = branchBase;
      // Allow duplicate named-group registrations inside branch-reset alternatives.
      boolean savedInBranchReset = inBranchReset;
      inBranchReset = true;
      Expr arm = parseConcatenation();
      inBranchReset = savedInBranchReset;
      int usedThisBranch = groupCounter - branchBase;
      maxGroupsUsed = Math.max(maxGroupsUsed, usedThisBranch);
      alternatives.add(arm);

      if (isAtEnd()) {
        throw new PatternSyntaxException(
            "Branch reset group (?|... not terminated", source, cursor);
      }
      if (peek() == ')') {
        advance(); // consume ')'
        break;
      }
      if (peek() == '|') {
        advance(); // consume '|'
        // Continue to next alternative
      }
    }

    // Restore groupCounter to base + maximum used across all alternatives.
    groupCounter = branchBase + maxGroupsUsed;

    // Build the result: a Union of all alternatives (or the single arm directly).
    if (alternatives.size() == 1) {
      return alternatives.get(0);
    }
    // Build left-associative Union tree matching the parser's existing style.
    Expr result = alternatives.get(0);
    for (int i = 1; i < alternatives.size(); i++) {
      result = new Union(List.of(result, alternatives.get(i)));
    }
    return result;
  }

  /**
   * Validates that the body expression has a fixed, computable length.
   *
   * <p>Throws {@link PatternSyntaxException} if the body contains unbounded quantifiers
   * or alternation with unequal-length arms, since variable-length lookbehind is not
   * supported in Phase 1.
   *
   * @param body the lookbehind body to validate
   * @throws PatternSyntaxException if the body is variable-length
   */
  private void validateFixedLengthBody(Expr body) throws PatternSyntaxException {
    if (computeFixedLength(body) < 0) {
      throw new PatternSyntaxException(
          "Variable-length lookbehind not supported", source, cursor);
    }
  }

  /**
   * Validates that a lookbehind body has a bounded (finite) maximum length.
   *
   * <p>Bounded variable-length lookbehind (e.g. {@code (?<=a{1,3})}) is supported.
   * Unbounded lookbehind (e.g. {@code (?<=.*)}) is rejected.
   *
   * @param body the lookbehind body to validate
   * @throws PatternSyntaxException if the body has unbounded maximum length
   */
  private void validateVariableLengthBody(Expr body) throws PatternSyntaxException {
    if (computeMaxLength(body) == Integer.MAX_VALUE) {
      throw new PatternSyntaxException(
          "Lookbehind body has unbounded length", source, cursor);
    }
  }

  /**
   * Computes the maximum number of characters that {@code expr} can match.
   *
   * <p>Returns {@link Integer#MAX_VALUE} if the expression can match an unbounded number of
   * characters (e.g. due to {@code *} or {@code +} quantifiers, or unbounded alternation).
   * Returns {@code 0} for zero-width assertions.
   *
   * @param expr the expression to measure; must not be null
   * @return the maximum character length, or {@link Integer#MAX_VALUE} if unbounded
   */
  public static int computeMaxLength(Expr expr) {
    return switch (expr) {
      case Literal lit -> lit.value().length();
      case CharClass ignored -> 1;
      case Anchor ignored -> 0;
      case Epsilon ignored -> 0;
      case Concat concat -> {
        int total = 0;
        for (Expr part : concat.parts()) {
          int len = computeMaxLength(part);
          if (len == Integer.MAX_VALUE) {
            yield Integer.MAX_VALUE;
          }
          total += len;
          if (total < 0) {
            yield Integer.MAX_VALUE; // overflow guard
          }
        }
        yield total;
      }
      case Union union -> {
        int max = 0;
        for (Expr alt : union.alternatives()) {
          int len = computeMaxLength(alt);
          if (len == Integer.MAX_VALUE) {
            yield Integer.MAX_VALUE;
          }
          if (len > max) {
            max = len;
          }
        }
        yield max;
      }
      case Quantifier quant -> {
        if (quant.max().isEmpty()) {
          yield Integer.MAX_VALUE; // unbounded
        }
        int childMax = computeMaxLength(quant.child());
        if (childMax == Integer.MAX_VALUE) {
          yield Integer.MAX_VALUE;
        }
        long product = (long) quant.max().getAsInt() * childMax;
        yield product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
      }
      case Group group -> computeMaxLength(group.body());
      case FlagExpr fe -> computeMaxLength(fe.body());
      case AtomicGroup ag -> computeMaxLength(ag.body());
      case LookbehindExpr ignored -> 0; // zero-width assertion
      case LookaheadExpr ignored -> 0;  // zero-width assertion
      case Backref ignored -> Integer.MAX_VALUE; // variable-length
      case Pair pair -> computeMaxLength(pair.input());
      case BalanceGroupExpr bg -> computeMaxLength(bg.body());
      case ConditionalExpr ignored -> Integer.MAX_VALUE; // conservative
      case KeepAssertion ignored -> 0; // zero-width assertion
    };
  }

  /**
   * Computes the minimum number of characters that {@code expr} must match.
   *
   * <p>Returns {@code 0} for zero-width assertions and optional constructs.
   *
   * @param expr the expression to measure; must not be null
   * @return the minimum character length, never negative
   */
  public static int computeMinLength(Expr expr) {
    return switch (expr) {
      case Literal lit -> lit.value().length();
      case CharClass ignored -> 1;
      case Anchor ignored -> 0;
      case Epsilon ignored -> 0;
      case Concat concat -> {
        int total = 0;
        for (Expr part : concat.parts()) {
          total += computeMinLength(part);
        }
        yield total;
      }
      case Union union -> {
        int min = Integer.MAX_VALUE;
        for (Expr alt : union.alternatives()) {
          int len = computeMinLength(alt);
          if (len < min) {
            min = len;
          }
        }
        yield min == Integer.MAX_VALUE ? 0 : min;
      }
      case Quantifier quant -> quant.min() * computeMinLength(quant.child());
      case Group group -> computeMinLength(group.body());
      case FlagExpr fe -> computeMinLength(fe.body());
      case AtomicGroup ag -> computeMinLength(ag.body());
      case LookbehindExpr ignored -> 0;
      case LookaheadExpr ignored -> 0;
      case Backref ignored -> 0; // conservative: captured text may be empty
      case Pair pair -> computeMinLength(pair.input());
      case BalanceGroupExpr bg -> computeMinLength(bg.body());
      case ConditionalExpr ignored -> 0; // conservative
      case KeepAssertion ignored -> 0; // zero-width assertion
    };
  }

  /**
   * Computes the fixed match length of {@code expr}, or {@code -1} if the length is
   * variable.
   *
   * @param expr the expression to measure
   * @return the fixed character length, or {@code -1} if variable
   */
  public static int computeFixedLength(Expr expr) {
    return switch (expr) {
      case Literal lit -> lit.value().length();
      case CharClass ignored -> 1;
      case Anchor ignored -> 0;
      case Epsilon ignored -> 0;
      case Concat concat -> {
        int total = 0;
        for (Expr part : concat.parts()) {
          int len = computeFixedLength(part);
          if (len < 0) {
            yield -1;
          }
          total += len;
        }
        yield total;
      }
      case Union union -> {
        int commonLen = -2; // sentinel: not yet set
        for (Expr alt : union.alternatives()) {
          int len = computeFixedLength(alt);
          if (len < 0) {
            yield -1;
          }
          if (commonLen == -2) {
            commonLen = len;
          } else if (commonLen != len) {
            yield -1;
          }
        }
        yield commonLen == -2 ? 0 : commonLen;
      }
      case Quantifier quant -> {
        // Fixed only when min == max
        int min = quant.min();
        if (quant.max().isEmpty()) {
          yield -1; // unbounded
        }
        int max = quant.max().getAsInt();
        if (min != max) {
          yield -1;
        }
        int childLen = computeFixedLength(quant.child());
        if (childLen < 0) {
          yield -1;
        }
        yield min * childLen;
      }
      case Group group -> computeFixedLength(group.body());
      case FlagExpr fe -> computeFixedLength(fe.body());
      case AtomicGroup ag -> computeFixedLength(ag.body());
      case LookaheadExpr ignored -> 0;  // zero-width assertion
      case LookbehindExpr ignored -> 0; // zero-width assertion
      case Backref ignored -> -1; // variable-length
      case Pair pair -> computeFixedLength(pair.input());
      case BalanceGroupExpr bg -> computeFixedLength(bg.body());
      case ConditionalExpr cond -> -1; // variable-length: yes/no branches may differ
      case KeepAssertion ignored -> 0; // zero-width assertion
    };
  }

  /**
   * Parses a capturing group name until the given terminator character (exclusive).
   *
   * <p>Names must start with a letter and contain only letters, digits, or underscores.
   * Duplicate names within the same pattern are rejected. The terminator is consumed.
   *
   * @param terminator the character that ends the name (e.g. {@code '>'})
   * @return the validated name, never null or empty
   * @throws PatternSyntaxException if the name is empty, invalid, or a duplicate
   */
  private String parseName(char terminator) throws PatternSyntaxException {
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd() && peek() != terminator) {
      sb.append(advance());
    }
    expect(terminator);
    if (sb.isEmpty()) {
      throw new PatternSyntaxException("Empty group name", source, cursor);
    }
    String name = sb.toString();
    // Validate: must start with an ASCII letter; body must be ASCII letters or ASCII digits.
    // Underscores are not permitted — JDK rejects them. Unicode letters are not permitted
    // (JDK compatibility).
    char first = name.charAt(0);
    if (!isAsciiLetter(first)) {
      throw new PatternSyntaxException(
          "Named group must start with a letter: " + name, source, cursor);
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!isAsciiLetter(c) && (c < '0' || c > '9')) {
        throw new PatternSyntaxException(
            "Invalid character in group name: " + name, source, cursor);
      }
    }
    // Detect duplicate names.
    if (!namedGroups.add(name)) {
      throw new PatternSyntaxException(
          "Duplicate capturing group name: " + name, source, cursor);
    }
    return name;
  }

  /**
   * Parses a single-quote-delimited group name for {@code (?'name'...)} groups.
   *
   * <p>Reads characters until the closing {@code '}, validates the name starts with an ASCII
   * letter or underscore and all subsequent characters are ASCII letters, digits, or underscores.
   *
   * @return the validated name, never null or empty
   * @throws PatternSyntaxException if the name is empty, unterminated, or contains invalid chars
   */
  private String parseSingleQuoteName() throws PatternSyntaxException {
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd() && peek() != '\'') {
      sb.append(advance());
    }
    if (isAtEnd()) {
      throw new PatternSyntaxException(
          "Unterminated single-quote named group", source, cursor);
    }
    advance(); // consume closing '\''
    String name = sb.toString().trim();
    if (name.isEmpty()) {
      throw new PatternSyntaxException("Empty group name in (?'...')", source, cursor);
    }
    char first = name.charAt(0);
    if (!isAsciiLetter(first) && first != '_') {
      throw new PatternSyntaxException(
          "Named group must start with a letter or underscore: " + name, source, cursor);
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '_') {
        throw new PatternSyntaxException(
            "Invalid character in group name: " + name, source, cursor);
      }
    }
    return name;
  }

  /**
   * Validates that {@code name} conforms to JDK group-name rules: must start with an ASCII
   * letter; subsequent characters must be ASCII letters or ASCII digits only.
   *
   * <p>This is called for plain {@code (?<Name>...)} groups whose name was read by
   * {@link #parseBalanceName} (which also accepts underscores for .NET disambiguation).
   * {@link #parseName} applies the same rules and is used for {@code (?P<name>...)} groups.
   *
   * @throws PatternSyntaxException if the name violates JDK naming rules
   */
  private void validateJdkGroupName(String name) {
    if (name.isEmpty()) {
      throw new PatternSyntaxException("Empty group name", source, cursor);
    }
    char first = name.charAt(0);
    if (!isAsciiLetter(first)) {
      throw new PatternSyntaxException(
          "Named group must start with a letter: " + name, source, cursor);
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!isAsciiLetter(c) && (c < '0' || c > '9')) {
        throw new PatternSyntaxException(
            "Invalid character in group name: " + name, source, cursor);
      }
    }
  }

  /**
   * Performs a quick linear scan of {@code source} to collect all names used as pop targets in
   * {@code (?<-Name>...)} or {@code (?<NewName-OldName>...)} constructs.
   *
   * <p>This pre-scan allows the main parse to identify which {@code (?<Name>...)} groups should
   * emit balance-stack pushes (those whose names appear as pop targets).
   *
   * @param source the pattern source; must not be null
   * @return the set of pop-target names; never null, possibly empty
   */
  private static Set<String> scanBalancePopTargets(String source) {
    Set<String> targets = new HashSet<>();
    int i = 0;
    int len = source.length();
    while (i < len - 3) { // need at least (?<-X>
      // Look for the sequence '(' '?' '<'
      if (source.charAt(i) == '(' && source.charAt(i + 1) == '?' && source.charAt(i + 2) == '<') {
        int j = i + 3;
        // Skip to the '-' or end of name
        // Scan name chars until '-', '>', or end
        while (j < len && source.charAt(j) != '-' && source.charAt(j) != '>'
            && source.charAt(j) != ')' && source.charAt(j) != '(') {
          j++;
        }
        if (j < len && source.charAt(j) == '-') {
          // Found '-': everything after it (until '>') is the pop-target name
          j++; // skip '-'
          StringBuilder sb = new StringBuilder();
          while (j < len && source.charAt(j) != '>' && source.charAt(j) != ')'
              && source.charAt(j) != '(') {
            sb.append(source.charAt(j));
            j++;
          }
          String popName = sb.toString();
          if (!popName.isEmpty()) {
            targets.add(popName);
          }
        }
      }
      i++;
    }
    return targets;
  }

  /**
   * Parses a balance-group name component — reads letter/digit/underscore characters until
   * either {@code '-'} or {@code '>'} is encountered (without consuming the terminator).
   *
   * <p>Unlike {@link #parseName}, this method does not consume the terminator, does not
   * deduplicate, and allows the name to appear on either side of a {@code '-'} separator.
   *
   * @return the parsed name segment, never null or empty
   * @throws PatternSyntaxException if no valid name characters are found
   */
  private String parseBalanceName(char unused) throws PatternSyntaxException {
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd() && peek() != '>' && peek() != '-') {
      char c = peek();
      if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '_') {
        break;
      }
      sb.append(advance());
    }
    if (sb.isEmpty()) {
      throw new PatternSyntaxException("Empty name in balancing group", source, cursor);
    }
    String name = sb.toString();
    char first = name.charAt(0);
    if (!isAsciiLetter(first) && first != '_') {
      throw new PatternSyntaxException(
          "Balance group name must start with a letter: " + name, source, cursor);
    }
    return name;
  }

  /**
   * Parses a balance-group name until {@code '>'}, consuming the {@code '>'} terminator.
   *
   * @return the parsed name, never null or empty
   * @throws PatternSyntaxException if the name is empty or invalid
   */
  private String parseBalanceNameUntilClose() throws PatternSyntaxException {
    String name = parseBalanceName('\0');
    expect('>');
    return name;
  }

  /**
   * Parses a conditional subpattern after the opening {@code (?(} has been consumed.
   *
   * <p>Handles:
   * <ul>
   *   <li>{@code (?(1)yes|no)} — group-index condition</li>
   *   <li>{@code (?(Name)yes|no)} — group-name or balance-stack condition</li>
   *   <li>{@code (?(?=pat)yes|no)} — positive lookahead condition</li>
   *   <li>{@code (?(?!pat)yes|no)} — negative lookahead condition</li>
   * </ul>
   *
   * @return the parsed {@link ConditionalExpr}
   * @throws PatternSyntaxException if the conditional syntax is invalid
   */
  private Expr parseConditional() throws PatternSyntaxException {
    // Cursor is positioned after '?(('.
    // Parse the condition expression inside the inner '(...)'.
    ConditionalExpr.Condition condition;

    if (!isAtEnd() && peek() == '?') {
      // Lookahead condition: (?(?=...) or (?(?!...)
      advance(); // consume '?'
      if (isAtEnd()) {
        throw new PatternSyntaxException("Unexpected end in conditional lookahead", source, cursor);
      }
      char la = peek();
      if (la == '=') {
        advance(); // consume '='
        Expr laBody = parseExpression();
        expect(')');
        condition = new ConditionalExpr.LookaheadCondition(laBody, true);
      } else if (la == '!') {
        advance(); // consume '!'
        Expr laBody = parseExpression();
        expect(')');
        condition = new ConditionalExpr.LookaheadCondition(laBody, false);
      } else {
        throw new PatternSyntaxException(
            "Expected '=' or '!' in conditional lookahead", source, cursor);
      }
    } else if (!isAtEnd() && Character.isDigit(peek())) {
      // Group-index condition: (?(1)...)
      int index = tryParseDecimal();
      expect(')');
      condition = new ConditionalExpr.GroupIndexCondition(index);
    } else {
      // Group-name or balance-stack condition.
      // Three forms are accepted:
      //   (?(Name)...)      — undelimited (already existed)
      //   (?(<Name>)...)    — angle-bracket delimited (Perl)
      //   (?('Name')...)    — single-quote delimited (Perl)
      String name;
      if (!isAtEnd() && peek() == '<') {
        advance(); // consume '<'
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != '>') {
          char c = peek();
          if (!Character.isLetterOrDigit(c) && c != '_') {
            throw new PatternSyntaxException(
                "Invalid character in conditional name: '" + c + "'", source, cursor);
          }
          sb.append(advance());
        }
        if (sb.isEmpty()) {
          throw new PatternSyntaxException(
              "Empty condition name in (?(<>)...)", source, cursor);
        }
        expect('>');
        expect(')');
        name = sb.toString();
      } else if (!isAtEnd() && peek() == '\'') {
        advance(); // consume '\''
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != '\'') {
          char c = peek();
          if (!Character.isLetterOrDigit(c) && c != '_') {
            throw new PatternSyntaxException(
                "Invalid character in conditional name: '" + c + "'", source, cursor);
          }
          sb.append(advance());
        }
        if (sb.isEmpty()) {
          throw new PatternSyntaxException(
              "Empty condition name in (?('...')...)", source, cursor);
        }
        expect('\'');
        expect(')');
        name = sb.toString();
      } else {
        // Original undelimited form: (?(Name)...)
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek() != ')') {
          char c = peek();
          if (!Character.isLetterOrDigit(c) && c != '_') {
            throw new PatternSyntaxException(
                "Invalid character in conditional name: '" + c + "'", source, cursor);
          }
          sb.append(advance());
        }
        if (sb.isEmpty()) {
          throw new PatternSyntaxException("Empty condition name in conditional", source, cursor);
        }
        expect(')');
        name = sb.toString();
      }
      condition = new ConditionalExpr.GroupNameCondition(name);
    }

    // Parse yes branch (up to '|' or ')')
    Expr yes = parseConcatenation();

    Expr noAlt;
    if (match('|')) {
      noAlt = parseConcatenation();
    } else {
      noAlt = new Epsilon();
    }

    expect(')');
    return new ConditionalExpr(condition, yes, noAlt);
  }

  // -----------------------------------------------------------------------
  // Character classes
  // -----------------------------------------------------------------------

  /**
   * Parses a {@code [...]} character class after the opening {@code [} has been consumed.
   *
   * <p>Supports:
   * <ul>
   *   <li>Ordinary character ranges and escape sequences</li>
   *   <li>Shorthand classes ({@code \d}, {@code \w}, {@code \s}, and their negated forms)</li>
   *   <li>Nested classes for union: {@code [a[bc]]} → same as {@code [abc]}</li>
   *   <li>Intersection via {@code &&}: {@code [a-z&&[aeiou]]} → vowels only</li>
   *   <li>Multiple chained {@code &&} operators for repeated intersection</li>
   * </ul>
   *
   * @return the parsed {@link CharClass} node; never null
   * @throws PatternSyntaxException if the character class is syntactically invalid
   */
  private Expr parseCharClass() throws PatternSyntaxException {
    boolean negated = match('^');

    // ']' immediately after '[' or '[^' is a literal ']'.
    // Handle this before entering the segment parser so it is not confused with class-end.
    List<CharRange> initialLiteral = new ArrayList<>();
    if (!isAtEnd() && peek() == ']') {
      initialLiteral.add(new CharRange(']', ']'));
      advance();
    }

    // Parse the first segment (everything up to the first '&&' or the closing ']').
    List<CharRange> result = parseCharClassSegment();
    result = unionRanges(initialLiteral, result);

    // Handle zero or more '&&' operators: each one intersects the accumulated result
    // with the next segment.
    if (peek() == '&' && peekAt(1) == '&' && activeFlags.contains(PatternFlag.RE2_COMPAT)) {
      throw new PatternSyntaxException(
          "character class intersection is not supported in RE2_COMPAT mode", source, cursor);
    }
    while (!isAtEnd() && peek() == '&' && peekAt(1) == '&') {
      advance(); // consume first '&'
      advance(); // consume second '&'
      List<CharRange> right = parseCharClassSegment();
      if (right.isEmpty()) {
        throw new PatternSyntaxException("Bad intersection syntax", source, cursor);
      }
      result = intersectRanges(result, right);
    }

    if (isAtEnd()) {
      throw new PatternSyntaxException("Unclosed character class", source, cursor);
    }
    advance(); // consume ']'

    if (result.isEmpty()) {
      // Empty class (e.g. disjoint intersection) — treat as a never-matching sentinel range.
      result = List.of(new CharRange('\u0000', '\u0000'));
    }

    return new CharClass(negated, result);
  }

  /**
   * Parses a contiguous sequence of character-class elements into a range list, stopping
   * before a {@code &&} operator or the closing {@code ]}.
   *
   * <p>Handles ordinary characters, backslash escapes, shorthand classes, range expressions
   * ({@code lo-hi}), and nested bracket expressions ({@code [...]}) by recursively calling
   * {@link #parseCharClass()} and unioning the inner ranges into the accumulator.
   *
   * @return the accumulated ranges; may be empty if the segment contains no elements
   * @throws PatternSyntaxException if the segment is syntactically invalid
   */
  private List<CharRange> parseCharClassSegment() throws PatternSyntaxException {
    List<CharRange> ranges = new ArrayList<>();

    while (!isAtEnd() && peek() != ']' && !(peek() == '&' && peekAt(1) == '&')) {
      if (peek() == '[') {
        // Try POSIX bracket class [[:name:]] or [[:^name:]] before falling back to nested class.
        if (peekAt(1) == ':') {
          List<CharRange> posixRanges = tryParsePosixBracketClass();
          if (posixRanges != null) {
            ranges = unionRanges(ranges, posixRanges);
            continue; // consumed; do not attempt range-operator
          }
        }
        // Nested bracket expression — recursively parse the inner class and union its
        // effective ranges (applying complement if the inner class is negated) into ours.
        advance(); // consume '['
        CharClass inner = (CharClass) parseCharClass();
        List<CharRange> innerRanges = inner.negated()
            ? complementRanges(inner.ranges())
            : inner.ranges();
        ranges = unionRanges(ranges, innerRanges);
      } else {
        boolean wasShorthand = parseCharClassElement(ranges);

        if (!wasShorthand && !isAtEnd() && peek() == '-' && peekAt(1) != ']') {
          // The previous element was a single character; check if it starts a lo-hi range.
          // Peek ahead: if next is '-' and the char after is not ']', form a range.
          char start = ranges.remove(ranges.size() - 1).lo();
          advance(); // consume '-'
          char end = parseCharClassChar();
          if (end < start) {
            throw new PatternSyntaxException(
                "Invalid character class range: end < start", source, cursor);
          }
          ranges.add(new CharRange(start, end));
        }
      }
    }

    return ranges;
  }

  /**
   * Attempts to parse a POSIX bracket class expression of the form {@code [[:name:]]}
   * or {@code [[:^name:]]}.
   *
   * <p>The cursor must be positioned at the opening {@code [} when this method is called, and
   * {@code peekAt(1)} must be {@code ':'} (checked by the caller before invoking). On success,
   * the cursor is advanced past the closing {@code ]}. On failure (not a valid POSIX bracket
   * class), the cursor is restored to its original position.
   *
   * @return the resolved ranges for the POSIX class, or {@code null} if the sequence is not a
   *     valid POSIX bracket class (caller should fall back to nested-class parsing)
   * @throws PatternSyntaxException if the delimiters {@code [:} and {@code :]} are found but the
   *     enclosed name is unrecognised
   */
  private List<CharRange> tryParsePosixBracketClass() {
    // Cursor is at '['; peekAt(1) is ':' (verified by caller).
    int savedCursor = cursor;
    advance(); // consume '['
    advance(); // consume ':'
    boolean negated = match('^');
    StringBuilder name = new StringBuilder();
    while (!isAtEnd() && peek() != ':' && peek() != ']') {
      char c = peek();
      if (!Character.isLetter(c)) {
        // Not a valid POSIX name character — fall back to nested-class parsing.
        cursor = savedCursor;
        return null;
      }
      name.append(advance());
    }
    if (name.isEmpty() || isAtEnd() || peek() != ':' || peekAt(1) != ']') {
      // Did not find closing :] — not a valid POSIX bracket class.
      cursor = savedCursor;
      return null;
    }
    advance(); // consume ':'
    advance(); // consume ']'
    // Resolve via UnicodeProperties; throws PatternSyntaxException for unknown names.
    List<CharRange> resolved = UnicodeProperties.resolve(name.toString(), negated);
    return new ArrayList<>(resolved);
  }

  // -----------------------------------------------------------------------
  // Range set operations (union, intersect, complement)
  // -----------------------------------------------------------------------

  /**
   * Returns the union of two range lists: a sorted, merged list containing every character
   * that appears in either {@code a} or {@code b}.
   *
   * <p>Overlapping and adjacent ranges are merged so that the result contains the minimum
   * number of disjoint intervals.
   *
   * @param a the first range list; must not be null
   * @param b the second range list; must not be null
   * @return the merged union, sorted by low endpoint; never null, may be empty
   */
  private static List<CharRange> unionRanges(List<CharRange> a, List<CharRange> b) {
    if (a.isEmpty()) {
      return new ArrayList<>(b);
    }
    if (b.isEmpty()) {
      return new ArrayList<>(a);
    }
    List<CharRange> combined = new ArrayList<>(a.size() + b.size());
    combined.addAll(a);
    combined.addAll(b);
    combined.sort((x, y) -> Character.compare(x.lo(), y.lo()));

    List<CharRange> merged = new ArrayList<>();
    CharRange current = combined.get(0);
    for (int i = 1; i < combined.size(); i++) {
      CharRange next = combined.get(i);
      // Merge if next starts within or immediately after current.
      if (next.lo() <= (char) (current.hi() + 1)) {
        char newHi = current.hi() > next.hi() ? current.hi() : next.hi();
        current = new CharRange(current.lo(), newHi);
      } else {
        merged.add(current);
        current = next;
      }
    }
    merged.add(current);
    return merged;
  }

  /**
   * Returns the intersection of two range lists: a sorted list containing only characters
   * that appear in both {@code a} and {@code b}.
   *
   * <p>Both input lists are assumed sorted by low endpoint; the result is also sorted.
   *
   * @param a the first range list; must not be null
   * @param b the second range list; must not be null
   * @return the intersection, sorted by low endpoint; never null, may be empty
   */
  private static List<CharRange> intersectRanges(List<CharRange> a, List<CharRange> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return new ArrayList<>();
    }
    // Normalise both sides first so the two-pointer sweep works correctly.
    List<CharRange> sortedA = new ArrayList<>(a);
    List<CharRange> sortedB = new ArrayList<>(b);
    sortedA.sort((x, y) -> Character.compare(x.lo(), y.lo()));
    sortedB.sort((x, y) -> Character.compare(x.lo(), y.lo()));

    List<CharRange> result = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < sortedA.size() && j < sortedB.size()) {
      CharRange ra = sortedA.get(i);
      CharRange rb = sortedB.get(j);
      char lo = ra.lo() > rb.lo() ? ra.lo() : rb.lo();
      char hi = ra.hi() < rb.hi() ? ra.hi() : rb.hi();
      if (lo <= hi) {
        result.add(new CharRange(lo, hi));
      }
      // Advance the range that ends first.
      if (ra.hi() < rb.hi()) {
        i++;
      } else if (rb.hi() < ra.hi()) {
        j++;
      } else {
        i++;
        j++;
      }
    }
    return result;
  }

  /**
   * Returns the complement of the given range list within {@code ['\u0000', '\uFFFF']}.
   *
   * <p>Every character code point in {@code [0, 0xFFFF]} that does not appear in
   * {@code ranges} is included in the result.
   *
   * @param ranges the ranges to complement; must not be null
   * @return the complement, sorted by low endpoint; never null, may be empty
   */
  private static List<CharRange> complementRanges(List<CharRange> ranges) {
    return complementOf(ranges);
  }

  /**
   * Parses one element inside a character class and appends the resulting {@link CharRange}
   * objects to {@code out}.
   *
   * <p>If the element is a shorthand class ({@code \w}, {@code \s}, {@code \d}, or their
   * negated forms), the corresponding ranges are appended (complement ranges for upper-case
   * shorthands) and {@code true} is returned — the caller must not attempt range-operator
   * parsing because a shorthand cannot be the endpoint of a {@code lo-hi} range.
   *
   * <p>If the element is an ordinary character (or a non-shorthand escape), exactly one
   * single-character range is appended and {@code false} is returned — the caller may check
   * for a following {@code -} to build a range.
   *
   * @param out the accumulator to append ranges to; must not be null
   * @return {@code true} if a shorthand was consumed, {@code false} otherwise
   * @throws PatternSyntaxException if the input is ill-formed
   */
  private boolean parseCharClassElement(List<CharRange> out) throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Unclosed character class", source, cursor);
    }

    if (peek() == '\\' && !isAtEnd()) {
      // Peek at escape letter before consuming
      char escLetter = peekAt(1);
      if (escLetter == 'd' || escLetter == 'D'
          || escLetter == 'w' || escLetter == 'W'
          || escLetter == 's' || escLetter == 'S'
          || escLetter == 'h' || escLetter == 'H'
          || escLetter == 'v' || escLetter == 'V') {
        advance(); // consume '\\'
        advance(); // consume escape letter
        appendShorthandRanges(escLetter, out);
        return true;
      }
      // Unicode property class \p{...} or \P{...} inside a char class.
      if (escLetter == 'p' || escLetter == 'P') {
        advance(); // consume '\\'
        advance(); // consume 'p' or 'P'
        CharClass propClass = (CharClass) parseUnicodePropertyClassOrBare(escLetter == 'P');
        out.addAll(propClass.ranges());
        return true;
      }
      // \N{NAME} inside a character class — single-character range.
      if (escLetter == 'N' && peekAt(2) == '{') {
        advance(); // consume '\\'
        advance(); // consume 'N'
        advance(); // consume '{'
        StringBuilder nameBuf = new StringBuilder();
        while (!isAtEnd() && peek() != '}') {
          nameBuf.append(advance());
        }
        expect('}');
        String name = nameBuf.toString();
        int cp;
        try {
          cp = Character.codePointOf(name);
        } catch (IllegalArgumentException e) {
          throw new PatternSyntaxException(
              "Unknown Unicode character name: " + name, source, cursor);
        }
        if (cp > 0xFFFF) {
          throw new PatternSyntaxException(
              "\\N{NAME} does not support supplementary code points (> U+FFFF): " + name,
              source, cursor);
        }
        char ch = (char) cp;
        out.add(new CharRange(ch, ch));
        return true;
      }
      // \Q...\E inside a char class: each character is a literal range element.
      if (escLetter == 'Q') {
        advance(); // consume '\\'
        advance(); // consume 'Q'
        while (!isAtEnd()) {
          if (peek() == '\\' && peekAt(1) == 'E') {
            advance(); // consume '\\'
            advance(); // consume 'E'
            break;
          }
          char qc = advance();
          out.add(new CharRange(qc, qc));
        }
        return true; // treated as shorthand (don't allow range-operator)
      }
    }

    // Ordinary character or non-shorthand escape: parse a single char
    char c = parseCharClassChar();
    out.add(new CharRange(c, c));
    return false;
  }

  /**
   * Appends the ranges for the given shorthand escape letter to {@code out}.
   *
   * <p>For upper-case shorthands ({@code \D}, {@code \W}, {@code \S}), the complement of
   * the corresponding positive shorthand is computed and appended. The enclosing class's
   * {@code negated} flag is not touched.
   *
   * @param escLetter one of {@code d}, {@code D}, {@code w}, {@code W}, {@code s}, {@code S}
   * @param out       the accumulator to append to; must not be null
   */
  private void appendShorthandRanges(char escLetter, List<CharRange> out) {
    switch (escLetter) {
      case 'd' -> {
        if (activeFlags.contains(PatternFlag.UNICODE)) {
          out.addAll(UnicodeProperties.resolve("Nd", false));
        } else {
          out.add(new CharRange('0', '9'));
        }
      }
      case 'D' -> {
        if (activeFlags.contains(PatternFlag.UNICODE)) {
          out.addAll(complementOf(UnicodeProperties.resolve("Nd", false)));
        } else {
          out.addAll(complementOf(List.of(new CharRange('0', '9'))));
        }
      }
      case 'w' -> {
        if (activeFlags.contains(PatternFlag.UNICODE_CASE) || activeFlags.contains(PatternFlag.UNICODE)) {
          out.addAll(((CharClass) unicodeWordClass(false)).ranges());
        } else {
          out.add(new CharRange('a', 'z'));
          out.add(new CharRange('A', 'Z'));
          out.add(new CharRange('0', '9'));
          out.add(new CharRange('_', '_'));
        }
      }
      case 'W' -> {
        if (activeFlags.contains(PatternFlag.UNICODE_CASE) || activeFlags.contains(PatternFlag.UNICODE)) {
          out.addAll(((CharClass) unicodeWordClass(true)).ranges());
        } else {
          out.addAll(complementOf(List.of(
              new CharRange('a', 'z'),
              new CharRange('A', 'Z'),
              new CharRange('0', '9'),
              new CharRange('_', '_'))));
        }
      }
      case 's' -> {
        if (activeFlags.contains(PatternFlag.UNICODE)) {
          List<CharRange> zRanges = new ArrayList<>(UnicodeProperties.resolve("Z", false));
          zRanges.add(new CharRange('\t', '\t'));
          zRanges.add(new CharRange('\n', '\n'));
          zRanges.add(new CharRange('\u000B', '\u000B'));
          zRanges.add(new CharRange('\r', '\r'));
          zRanges.add(new CharRange('\f', '\f'));
          zRanges.add(new CharRange(' ', ' '));
          out.addAll(zRanges);
        } else {
          out.add(new CharRange(' ', ' '));
          out.add(new CharRange('\t', '\t'));
          out.add(new CharRange('\n', '\n'));
          out.add(new CharRange('\r', '\r'));
          out.add(new CharRange('\f', '\f'));
        }
      }
      case 'S' -> {
        if (activeFlags.contains(PatternFlag.UNICODE)) {
          List<CharRange> zRanges = new ArrayList<>(UnicodeProperties.resolve("Z", false));
          zRanges.add(new CharRange('\t', '\t'));
          zRanges.add(new CharRange('\n', '\n'));
          zRanges.add(new CharRange('\u000B', '\u000B'));
          zRanges.add(new CharRange('\r', '\r'));
          zRanges.add(new CharRange('\f', '\f'));
          zRanges.add(new CharRange(' ', ' '));
          out.addAll(complementOf(zRanges));
        } else {
          out.addAll(complementOf(List.of(
              new CharRange(' ', ' '),
              new CharRange('\t', '\t'),
              new CharRange('\n', '\n'),
              new CharRange('\r', '\r'),
              new CharRange('\f', '\f'))));
        }
      }
      case 'h' -> {
        out.add(new CharRange('\t', '\t'));
        out.add(new CharRange(' ', ' '));
        out.add(new CharRange('\u00A0', '\u00A0'));
        out.add(new CharRange('\u1680', '\u1680'));
        out.add(new CharRange('\u2000', '\u200A'));
        out.add(new CharRange('\u202F', '\u202F'));
        out.add(new CharRange('\u205F', '\u205F'));
        out.add(new CharRange('\u3000', '\u3000'));
      }
      case 'H' -> out.addAll(complementOf(List.of(
          new CharRange('\t', '\t'),
          new CharRange(' ', ' '),
          new CharRange('\u00A0', '\u00A0'),
          new CharRange('\u1680', '\u1680'),
          new CharRange('\u2000', '\u200A'),
          new CharRange('\u202F', '\u202F'),
          new CharRange('\u205F', '\u205F'),
          new CharRange('\u3000', '\u3000'))));
      case 'v' -> {
        out.add(new CharRange('\n', '\r'));       // LF, VT, FF, CR
        out.add(new CharRange('\u0085', '\u0085'));
        out.add(new CharRange('\u2028', '\u2029'));
      }
      case 'V' -> out.addAll(complementOf(List.of(
          new CharRange('\n', '\r'),
          new CharRange('\u0085', '\u0085'),
          new CharRange('\u2028', '\u2029'))));
    }
  }

  /**
   * Computes the complement of the given ranges over {@code ['\0', '\uFFFF']}.
   *
   * @param ranges the ranges to complement; assumed non-empty and non-overlapping
   * @return the complement range list, never null
   */
  private static List<CharRange> complementOf(List<CharRange> ranges) {
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
   * Parses a single character inside a character class, handling backslash escapes.
   *
   * <p>This method is used only when a single {@code char} is required — e.g. as the
   * endpoint of a {@code lo-hi} range. Shorthand classes ({@code \w} etc.) are not
   * permitted in that position and will cause {@code resolveEscapeChar()} to throw.
   *
   * @return the resolved character value
   * @throws PatternSyntaxException if the input is ill-formed or a shorthand is used as a
   *     range endpoint
   */
  private char parseCharClassChar() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Unclosed character class", source, cursor);
    }
    if (peek() == '\\') {
      advance(); // consume '\\'
      return resolveEscapeChar();
    }
    return advance();
  }

  // -----------------------------------------------------------------------
  // Backslash escapes
  // -----------------------------------------------------------------------

  /**
   * Parses a backslash-escaped atom after the '\' has been consumed.
   * Returns the corresponding {@link Expr}.
   */
  private Expr parseEscape() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Trailing backslash", source, cursor);
    }

    char esc = advance();

    return switch (esc) {
      // Predefined character classes
      case 'd' -> digitClass(false);
      case 'D' -> digitClass(true);
      case 'w' -> wordClass(false);
      case 'W' -> wordClass(true);
      case 's' -> spaceClass(false);
      case 'S' -> spaceClass(true);
      case 'h' -> horizontalSpaceClass(false);
      case 'H' -> horizontalSpaceClass(true);
      case 'v' -> verticalSpaceClass(false);
      case 'V' -> verticalSpaceClass(true);

      // Unicode property classes: \p{name} or \pX (bare single-letter shorthand)
      case 'p' -> parseUnicodePropertyClassOrBare(false);
      case 'P' -> parseUnicodePropertyClassOrBare(true);

      // Control characters
      case 'n' -> new Literal("\n");
      case 't' -> new Literal("\t");
      case 'r' -> new Literal("\r");
      case 'f' -> new Literal("\f");
      case 'a' -> new Literal("\u0007"); // bell
      case 'e' -> new Literal("\u001B"); // escape

      // Word boundary anchors
      case 'b' -> new Anchor(AnchorType.WORD_BOUNDARY);
      case 'B' -> new Anchor(AnchorType.NOT_WORD_BOUNDARY);

      // Position anchors
      case 'A' -> new Anchor(AnchorType.LINE_START);  // \A — absolute start of input
      case 'Z' -> new Anchor(AnchorType.LINE_END);    // \Z — end or before final terminator
      case 'z' -> new Anchor(AnchorType.EOF);         // \z — strict end of input
      case 'G' -> new Anchor(AnchorType.BOF);         // \G — position after previous match

      // Backreference \1..\9 — greedily consume additional digits, then back off if needed.
      case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
        int refNum = esc - '0';
        while (Character.isDigit(peek())) {
          int next = refNum * 10 + (peek() - '0');
          if (next > 999) {
            break;
          }
          refNum = next;
          advance();
        }
        while (refNum > groupCounter && refNum >= 10) {
          cursor--;
          refNum = refNum / 10;
        }
        yield new Backref(refNum);
      }

      // Named backreference \k<name>
      case 'k' -> parseNamedBackref();

      // \g backreference: \g{N}, \g{-N}, \g{name}, \gN
      case 'g' -> parseGBackref();

      // Octal escape \0NN or \0NNN
      case '0' -> parseOctalEscape();

      // Braced octal escape \o{NNN}
      case 'o' -> parseBracedOctalEscape();

      // Control-character escape \cX
      case 'c' -> parseControlEscape();

      // \N — if followed by '{', parse \N{NAME} Unicode character name; otherwise non-newline atom.
      case 'N' -> (!isAtEnd() && peek() == '{') ? parseNamedCharEscape() : parseNonNewlineAtom();

      // Quotemeta \Q...\E
      case 'Q' -> parseQuotemeta();

      // Hex escape \xNN
      case 'x' -> parseHexEscape();

      // Unicode escape (backslash-u-NNNN)
      case 'u' -> parseUnicodeEscape();

      // Keep assertion \K — resets the reported match start to the current position.
      // Illegal inside a character class; this call site is only reached outside char classes.
      case 'K' -> new KeepAssertion();

      // Line-break escape \R — matches any Unicode line break sequence
      case 'R' -> buildLineBreakExpr();

      // Escaped metacharacters — treat as literal
      case '.', '(', ')', '[', ']', '{', '}', '*', '+', '?', '|', '^', '$', '\\', '/', '#' ->
          new Literal(String.valueOf(esc));

      default -> throw new PatternSyntaxException(
          "Unknown escape sequence: \\" + esc, source, cursor - 1);
    };
  }

  /**
   * Parses a Unicode property class after the escape letter ({@code p} or {@code P}) has
   * already been consumed.
   *
   * <p>Two forms are accepted:
   * <ul>
   *   <li>{@code \p{name}} — braced form; reads until the matching {@code }}</li>
   *   <li>{@code \pX} — bare single-letter shorthand; treats the next character as the
   *       property name (e.g. {@code \pL} = {@code \p{L}})</li>
   * </ul>
   *
   * @param negated {@code true} if the surrounding escape was {@code \P}
   * @return the character-class expression for the property; never null
   * @throws PatternSyntaxException if the braces are missing (for the brace form), or the
   *     property name is unrecognised
   */
  private Expr parseUnicodePropertyClassOrBare(boolean negated) throws PatternSyntaxException {
    String name;
    if (!isAtEnd() && peek() == '{') {
      advance(); // consume '{'
      StringBuilder nameBuf = new StringBuilder();
      while (!isAtEnd() && peek() != '}') {
        nameBuf.append(advance());
      }
      expect('}');
      name = nameBuf.toString();
    } else {
      // Bare single-letter form: \pL, \pN, etc.
      if (isAtEnd() || !Character.isLetter(peek())) {
        throw new PatternSyntaxException("Expected '{' or letter after \\p/\\P", source, cursor);
      }
      name = String.valueOf(advance());
    }
    List<CharRange> ranges = UnicodeProperties.resolve(name, negated);
    return new CharClass(false, ranges);
  }

  /**
   * Builds the {@link Union} expression for the {@code \R} line-break escape.
   *
   * <p>The returned expression is equivalent to
   * {@code (?:\r\n|\r|\n|\u000B|\u000C|\u0085|\u2028|\u2029)}.
   * {@code \r\n} is tried first so that CRLF is consumed as a single unit.
   *
   * @return the alternation expression for any Unicode line break; never null
   */
  private Expr buildLineBreakExpr() {
    // Structure: (?:\r\n | \r(?!\n) | \n | \x0B | \x0C | \x85 | \u2028 | \u2029)
    //
    // The \r(?!\n) alternative matches a lone CR that is NOT followed by LF.
    // This prevents backtracking from matching just \r when \r\n (CRLF) is present:
    //   - \r\n is tried first; if it matches, the CRLF is consumed as a unit.
    //   - If backtracking re-enters, \r(?!\n) rejects \r when followed by \n,
    //     so \R cannot be made to consume just \r from a CRLF pair.
    Expr crLf = new Concat(List.of(new Literal("\r"), new Literal("\n")));
    Expr loneCarriageReturn = new Concat(List.of(
        new Literal("\r"),
        new LookaheadExpr(new Literal("\n"), false)));
    List<Expr> alternatives = List.of(
        crLf,
        loneCarriageReturn,
        new Literal("\n"),
        new Literal("\u000B"),
        new Literal("\u000C"),
        new Literal("\u0085"),
        new Literal("\u2028"),
        new Literal("\u2029")
    );
    return new Union(alternatives);
  }

  /**
   * Builds the expression for the {@code \N} non-newline atom.
   *
   * <p>Matches any single character except {@code \n} (U+000A). This is equivalent to
   * {@code [^\n]} regardless of the DOTALL flag.
   *
   * @return a {@link CharClass} matching all characters except newline; never null
   */
  private Expr parseNonNewlineAtom() {
    return new CharClass(false, List.of(
        new CharRange('\u0000', '\u0009'),
        new CharRange('\u000B', '\uffff')));
  }

  /**
   * Parses a {@code \N{NAME}} Unicode character name escape outside a character class.
   *
   * <p>The leading {@code \N} has already been consumed by the caller. This method expects
   * the cursor to be positioned at {@code {}.
   *
   * @return a {@link Literal} node for the named Unicode character; never null
   * @throws PatternSyntaxException if the name is unknown, the code point is supplementary
   *     ({@code > U+FFFF}), or the closing {@code \}} is missing
   */
  private Expr parseNamedCharEscape() throws PatternSyntaxException {
    advance(); // consume '{'
    StringBuilder nameBuf = new StringBuilder();
    while (!isAtEnd() && peek() != '}') {
      nameBuf.append(advance());
    }
    expect('}'); // consume '}', throws if missing
    String name = nameBuf.toString();
    int cp;
    try {
      cp = Character.codePointOf(name);
    } catch (IllegalArgumentException e) {
      throw new PatternSyntaxException(
          "Unknown Unicode character name: " + name, source, cursor);
    }
    if (cp > 0xFFFF) {
      throw new PatternSyntaxException(
          "\\N{NAME} does not support supplementary code points (> U+FFFF): " + name,
          source, cursor);
    }
    return new Literal(String.valueOf((char) cp));
  }

  /**
   * Resolves a backslash escape to a raw char (for use inside character classes).
   */
  private char resolveEscapeChar() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Trailing backslash", source, cursor);
    }
    char esc = advance();
    return switch (esc) {
      case 'n' -> '\n';
      case 't' -> '\t';
      case 'r' -> '\r';
      case 'f' -> '\f';
      case 'a' -> '\u0007';
      case 'e' -> '\u001B';
      case '0' -> parseOctalChar();
      case 'o' -> parseBracedOctalChar();
      case 'x' -> parseHexChar();
      case 'c' -> {
        if (isAtEnd()) {
          throw new PatternSyntaxException("Incomplete \\c escape", source, cursor);
        }
        char x = advance();
        yield (char) (x ^ 0x40);
      }
      case 'd', 'w', 's', 'D', 'W', 'S' -> throw new PatternSyntaxException(
          "Shorthand class inside character class not supported here", source, cursor - 1);
      case 'K' -> throw new PatternSyntaxException(
          "\\K is not valid inside a character class", source, cursor - 1);
      case 'R' -> throw new PatternSyntaxException(
          "\\R is not allowed inside a character class", source, cursor - 1);
      case 'N' -> throw new PatternSyntaxException(
          "\\N is not allowed inside a character class", source, cursor - 1);
      default -> esc; // treat as literal
    };
  }

  /**
   * Parses a {@code \0}-prefixed octal escape (the leading {@code 0} has already been consumed).
   *
   * <p>Reads one to three additional octal digits, capping the value at 0377 (255).
   * {@code \07} = char 7, {@code \077} = char 63, {@code \0377} = char 255.
   *
   * @return the Literal node for the octal character code
   * @throws PatternSyntaxException if no octal digit follows
   */
  private Expr parseOctalEscape() throws PatternSyntaxException {
    char c = parseOctalChar();
    return new Literal(String.valueOf(c));
  }

  /**
   * Parses one to three octal digits following a {@code \0} (the zero has already been consumed),
   * and returns the resolved character value.
   *
   * @return the character whose code point is the parsed octal value
   * @throws PatternSyntaxException if no octal digit follows
   */
  private char parseOctalChar() throws PatternSyntaxException {
    if (isAtEnd() || !isOctalDigit(peek())) {
      // \0 with no following octal digit is the NUL character.
      return '\0';
    }
    int value = 0;
    int digits = 0;
    while (!isAtEnd() && isOctalDigit(peek()) && digits < 3) {
      value = value * 8 + (advance() - '0');
      digits++;
      // Cap at 0377 (255)
      if (value > 0377) {
        // We went one digit too far; back up.
        cursor--;
        value /= 8;
        break;
      }
    }
    return (char) value;
  }

  /**
   * Parses a control-character escape {@code \cX} (the {@code c} has already been consumed).
   *
   * <p>The control character is computed as {@code X ^ 0x40}, matching Perl semantics.
   * For example, {@code \cA} = 0x01, {@code \cZ} = 0x1A.
   *
   * @return the Literal node for the control character
   * @throws PatternSyntaxException if the escape is incomplete (end of pattern)
   */
  private Expr parseControlEscape() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException("Incomplete \\c escape", source, cursor);
    }
    char x = advance();
    return new Literal(String.valueOf((char) (x ^ 0x40)));
  }

  /**
   * Parses a braced octal escape {@code \o{NNN}} (the {@code o} has already been consumed).
   *
   * <p>Reads {@code {}, one or more octal digits (underscore separators are stripped),
   * then {@code }}.  Values above {@code 0xFFFF} are rejected.
   *
   * @return the Literal node for the resolved character
   * @throws PatternSyntaxException if the brace is missing, the value is invalid, or the
   *     sequence is unterminated
   */
  private Expr parseBracedOctalEscape() throws PatternSyntaxException {
    return new Literal(String.valueOf(parseBracedOctalChar()));
  }

  /**
   * Parses the character value of a braced octal escape {@code \o{NNN}} (the {@code o} has
   * already been consumed).
   *
   * @return the resolved character
   * @throws PatternSyntaxException if syntax is invalid or value is out of range
   */
  private char parseBracedOctalChar() throws PatternSyntaxException {
    if (isAtEnd() || peek() != '{') {
      throw new PatternSyntaxException(
          "Expected '{' after \\o", source, cursor);
    }
    advance(); // consume '{'
    if (isAtEnd()) {
      throw new PatternSyntaxException(
          "Unterminated \\o{} escape", source, cursor);
    }
    if (peek() == '}') {
      throw new PatternSyntaxException(
          "Empty \\o{} escape", source, cursor);
    }
    int value = 0;
    boolean sawDigit = false;
    while (!isAtEnd() && peek() != '}') {
      char c = advance();
      if (c == '_') {
        continue; // separator, skip
      }
      if (!isOctalDigit(c)) {
        throw new PatternSyntaxException(
            "Non-octal digit in \\o{}: '" + c + "'", source, cursor - 1);
      }
      sawDigit = true;
      value = value * 8 + (c - '0');
      if (value > 0xFFFF) {
        throw new PatternSyntaxException(
            "Octal value in \\o{} exceeds 0xFFFF", source, cursor);
      }
    }
    if (isAtEnd()) {
      throw new PatternSyntaxException(
          "Unterminated \\o{} escape", source, cursor);
    }
    if (!sawDigit) {
      throw new PatternSyntaxException(
          "Empty \\o{} escape", source, cursor);
    }
    advance(); // consume '}'
    return (char) value;
  }

  /** Returns true if {@code c} is an ASCII letter (A–Z or a–z). */
  private static boolean isAsciiLetter(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  /** Returns true if {@code c} is an octal digit (0–7). */
  private static boolean isOctalDigit(char c) {
    return c >= '0' && c <= '7';
  }

  /**
   * Parses a {@code \Q...\E} quotemeta literal (the {@code Q} has already been consumed).
   *
   * <p>Everything between {@code \Q} and the next {@code \E} (or end of pattern) is treated as
   * literal text and emitted as a single {@link Literal} node.
   *
   * @return the Literal node containing the quoted text, never null
   */
  private Expr parseQuotemeta() {
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd()) {
      // Look for \E terminator
      if (peek() == '\\' && peekAt(1) == 'E') {
        advance(); // consume '\'
        advance(); // consume 'E'
        break;
      }
      sb.append(advance());
    }
    if (sb.isEmpty()) {
      return new Epsilon();
    }
    return new Literal(sb.toString());
  }

  /**
   * Parses a named backreference {@code \k<name>}, {@code \k'name'}, or {@code \k{name}}
   * (the {@code k} has already been consumed).
   *
   * <p>All three delimiter forms are accepted: angle-bracket ({@code <>}), single-quote
   * ({@code ''}), and curly-brace ({@code {}}).  The name is trimmed of leading and trailing
   * ASCII whitespace.  The name must start with an ASCII letter or underscore; subsequent
   * characters must be ASCII letters, digits, or underscores.
   *
   * @return the Backref node for the named group
   * @throws PatternSyntaxException if the syntax is invalid or the name is unknown
   */
  private Expr parseNamedBackref() throws PatternSyntaxException {
    if (isAtEnd()) {
      throw new PatternSyntaxException(
          "Expected '<', '{'  or '\\'' after \\k for named backreference", source, cursor);
    }
    char open = peek();
    char close;
    switch (open) {
      case '<' -> close = '>';
      case '\'' -> close = '\'';
      case '{' -> close = '}';
      default -> throw new PatternSyntaxException(
          "Expected '<', '{' or '\\'' after \\k for named backreference", source, cursor);
    }
    advance(); // consume opening delimiter
    StringBuilder sb = new StringBuilder();
    while (!isAtEnd() && peek() != close) {
      sb.append(advance());
    }
    if (isAtEnd()) {
      throw new PatternSyntaxException(
          "Unterminated named backreference \\k" + open, source, cursor);
    }
    advance(); // consume closing delimiter
    String name = sb.toString().trim();
    if (name.isEmpty()) {
      throw new PatternSyntaxException("Empty name in \\k backreference", source, cursor);
    }
    char nameFirst = name.charAt(0);
    if (!isAsciiLetter(nameFirst) && nameFirst != '_') {
      throw new PatternSyntaxException(
          "Named capturing group must start with an ASCII letter or underscore: " + name,
          source, cursor);
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '_') {
        throw new PatternSyntaxException(
            "Invalid character in backreference name: " + name, source, cursor);
      }
    }
    Integer groupIndex = namedGroupIndices.get(name);
    if (groupIndex == null) {
      throw new PatternSyntaxException(
          "Unknown named group in \\k" + open + name + close, source, cursor);
    }
    return new Backref(groupIndex);
  }

  /**
   * Parses a {@code \g} backreference (the {@code g} has already been consumed).
   *
   * <p>Four forms are accepted:
   * <ul>
   *   <li>{@code \g{N}} — absolute backreference to 1-based group index N</li>
   *   <li>{@code \g{-N}} — relative backreference: N groups before the current group count</li>
   *   <li>{@code \g{name}} — named backreference (equivalent to {@code \k<name>})</li>
   *   <li>{@code \gN} — unbraced absolute (single or multi-digit) backreference</li>
   * </ul>
   *
   * <p>For relative references {@code \g{-N}}: the resolved group index is
   * {@code groupCounter - N}. If the resolved index is less than 1, a
   * {@link PatternSyntaxException} is thrown. Group indices are 1-based.
   *
   * @return the {@link Backref} node for the resolved group index; never null
   * @throws PatternSyntaxException if the form is invalid, the index is out of range,
   *     or the referenced name is not defined
   */
  private Expr parseGBackref() throws PatternSyntaxException {
    // Braced form: \g{...}
    if (!isAtEnd() && peek() == '{') {
      advance(); // consume '{'
      if (isAtEnd()) {
        throw new PatternSyntaxException("Unterminated \\g{", source, cursor);
      }

      // Relative: \g{-N}
      if (peek() == '-') {
        advance(); // consume '-'
        int n = tryParseDecimal();
        if (n <= 0) {
          throw new PatternSyntaxException(
              "Expected positive integer after \\g{-", source, cursor);
        }
        expect('}');
        int resolved = groupCounter - n;
        if (resolved < 1) {
          throw new PatternSyntaxException(
              "Relative backreference \\g{-" + n + "} resolves to group "
                  + resolved + ", which is out of range", source, cursor);
        }
        return new Backref(resolved);
      }

      // Absolute numeric: \g{N}
      if (Character.isDigit(peek())) {
        int n = tryParseDecimal();
        if (n < 1) {
          throw new PatternSyntaxException(
              "Group index in \\g{N} must be >= 1", source, cursor);
        }
        expect('}');
        return new Backref(n);
      }

      // Named: \g{name}
      StringBuilder sb = new StringBuilder();
      while (!isAtEnd() && peek() != '}') {
        char c = peek();
        if (!Character.isLetterOrDigit(c) && c != '_') {
          throw new PatternSyntaxException(
              "Invalid character in \\g{name}: '" + c + "'", source, cursor);
        }
        sb.append(advance());
      }
      if (sb.isEmpty()) {
        throw new PatternSyntaxException("Empty \\g{} reference", source, cursor);
      }
      expect('}');
      String name = sb.toString();
      Integer groupIndex = namedGroupIndices.get(name);
      if (groupIndex == null) {
        throw new PatternSyntaxException(
            "Unknown named group in \\g{" + name + "}", source, cursor);
      }
      return new Backref(groupIndex);
    }

    // Unbraced: \gN (one or more digits, same greedy-then-back-off logic as \1..\9)
    if (!isAtEnd() && Character.isDigit(peek())) {
      int refNum = advance() - '0';
      while (!isAtEnd() && Character.isDigit(peek())) {
        int next = refNum * 10 + (peek() - '0');
        if (next > 999) {
          break;
        }
        refNum = next;
        advance();
      }
      // Back off extra digits if the composed number exceeds current group count
      while (refNum > groupCounter && refNum >= 10) {
        cursor--;
        refNum = refNum / 10;
      }
      if (refNum < 1) {
        throw new PatternSyntaxException(
            "Backreference \\g0 is invalid; group indices start at 1", source, cursor);
      }
      return new Backref(refNum);
    }

    throw new PatternSyntaxException(
        "Expected '{' or digit after \\g", source, cursor);
  }

  /**
   * Parses a hex escape {@code \xNN} or {@code \x{NNN}} (the {@code x} has already been consumed).
   *
   * @return the Literal node for the resolved character
   * @throws PatternSyntaxException if the escape is invalid
   */
  private Expr parseHexEscape() throws PatternSyntaxException {
    return new Literal(String.valueOf(parseHexChar()));
  }

  /**
   * Resolves a hex escape {@code \xNN} or {@code \x{NNN}} to a raw character.
   *
   * <p>The braced form strips {@code _} separators and rejects values above {@code 0xFFFF}.
   *
   * @return the resolved character value
   * @throws PatternSyntaxException if the escape is incomplete, contains non-hex digits, has
   *     empty braces, or the value exceeds {@code 0xFFFF}
   */
  private char parseHexChar() throws PatternSyntaxException {
    if (!isAtEnd() && peek() == '{') {
      advance(); // consume '{'
      if (isAtEnd() || peek() == '}') {
        throw new PatternSyntaxException("Empty \\x{} escape", source, cursor);
      }
      int value = 0;
      boolean sawDigit = false;
      while (!isAtEnd() && peek() != '}') {
        char c = advance();
        if (c == '_') {
          continue; // separator, skip
        }
        if (!isHexDigit(c)) {
          throw new PatternSyntaxException(
              "Non-hex digit in \\x{}: '" + c + "'", source, cursor - 1);
        }
        sawDigit = true;
        value = value * 16 + Character.digit(c, 16);
        if (value > 0xFFFF) {
          throw new PatternSyntaxException(
              "Hex value in \\x{} exceeds 0xFFFF", source, cursor);
        }
      }
      if (isAtEnd()) {
        throw new PatternSyntaxException("Unterminated \\x{} escape", source, cursor);
      }
      if (!sawDigit) {
        throw new PatternSyntaxException("Empty \\x{} escape", source, cursor);
      }
      advance(); // consume '}'
      return (char) value;
    }
    // Plain \xNN form: exactly two hex digits
    if (cursor + 2 > source.length()) {
      throw new PatternSyntaxException("Incomplete hex escape", source, cursor);
    }
    String hex = source.substring(cursor, cursor + 2);
    cursor += 2;
    try {
      return (char) Integer.parseInt(hex, 16);
    } catch (NumberFormatException e) {
      throw new PatternSyntaxException("Invalid hex escape: \\x" + hex, source, cursor - 2);
    }
  }

  /** Returns true if {@code c} is a hexadecimal digit (0–9, A–F, a–f). */
  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
  }

  private Expr parseUnicodeEscape() throws PatternSyntaxException {
    if (cursor + 4 > source.length()) {
      throw new PatternSyntaxException("Incomplete unicode escape", source, cursor);
    }
    String hex = source.substring(cursor, cursor + 4);
    cursor += 4;
    try {
      char c = (char) Integer.parseInt(hex, 16);
      return new Literal(String.valueOf(c));
    } catch (NumberFormatException e) {
      throw new PatternSyntaxException("Invalid unicode escape: \\u" + hex, source, cursor - 4);
    }
  }

  // -----------------------------------------------------------------------
  // Predefined character classes
  // -----------------------------------------------------------------------

  /**
   * Returns {@code \d} or {@code \D}: decimal digits.
   *
   * <p>Under {@link PatternFlag#UNICODE}, matches {@code \p{Nd}} (Unicode decimal digits).
   * Otherwise matches ASCII {@code [0-9]}.
   *
   * @param negated {@code true} for {@code \D} (complement)
   * @return the character-class expression; never null
   */
  private Expr digitClass(boolean negated) {
    if (activeFlags.contains(PatternFlag.UNICODE)) {
      List<CharRange> ndRanges = UnicodeProperties.resolve("Nd", false);
      return new CharClass(false, negated ? complementOf(ndRanges) : ndRanges);
    }
    return new CharClass(negated, List.of(new CharRange('0', '9')));
  }

  /**
   * Returns {@code \w} or {@code \W}: word chars, Unicode-aware when {@code UNICODE_CASE} is set.
   *
   * <p>In default mode, the word character set is ASCII: {@code [a-zA-Z0-9_]}.
   * When {@link PatternFlag#UNICODE_CASE} is active, the set is Unicode-aware:
   * all characters matching {@code \p{L}}, {@code \p{N}}, or underscore.
   *
   * @param negated {@code true} for {@code \W} (complement)
   * @return the character-class expression; never null
   */
  private Expr wordClass(boolean negated) {
    if (activeFlags.contains(PatternFlag.UNICODE_CASE) || activeFlags.contains(PatternFlag.UNICODE)) {
      return unicodeWordClass(negated);
    }
    return new CharClass(negated, List.of(
        new CharRange('a', 'z'),
        new CharRange('A', 'Z'),
        new CharRange('0', '9'),
        new CharRange('_', '_')));
  }

  /**
   * Builds a Unicode-aware word character class ({@code \p{L}}, {@code \p{N}}, and underscore).
   *
   * @param negated {@code true} to complement the set (i.e. {@code \W} in Unicode mode)
   * @return the character-class expression; never null
   */
  private Expr unicodeWordClass(boolean negated) {
    List<CharRange> positive = new ArrayList<>();
    positive.addAll(UnicodeProperties.resolve("L", false)); // letters
    positive.addAll(UnicodeProperties.resolve("N", false)); // numbers/digits
    positive.add(new CharRange('_', '_'));
    // complementOf sorts and merges; double-complement to get a sorted, merged positive list.
    List<CharRange> ranges = negated ? complementOf(positive) : complementOf(complementOf(positive));
    return new CharClass(false, ranges);
  }

  /**
   * Returns {@code \s} or {@code \S}: whitespace.
   *
   * <p>Under {@link PatternFlag#UNICODE}, matches {@code \p{Z}} (Unicode separators) unioned
   * with the ASCII whitespace set {@code [\t\n\x0B\r\f ]}.
   * Otherwise matches ASCII {@code [ \t\n\r\f]}.
   *
   * @param negated {@code true} for {@code \S} (complement)
   * @return the character-class expression; never null
   */
  private Expr spaceClass(boolean negated) {
    if (activeFlags.contains(PatternFlag.UNICODE)) {
      List<CharRange> zRanges = new ArrayList<>(UnicodeProperties.resolve("Z", false));
      zRanges.add(new CharRange('\t', '\t'));
      zRanges.add(new CharRange('\n', '\n'));
      zRanges.add(new CharRange('\u000B', '\u000B'));
      zRanges.add(new CharRange('\r', '\r'));
      zRanges.add(new CharRange('\f', '\f'));
      zRanges.add(new CharRange(' ', ' '));
      List<CharRange> positive = complementOf(complementOf(zRanges)); // sort + merge
      return new CharClass(false, negated ? complementOf(positive) : positive);
    }
    return new CharClass(negated, List.of(
        new CharRange(' ', ' '),
        new CharRange('\t', '\t'),
        new CharRange('\n', '\n'),
        new CharRange('\r', '\r'),
        new CharRange('\f', '\f')));
  }

  /**
   * Builds the character class for {@code \h} (horizontal whitespace) or {@code \H}.
   *
   * <p>Horizontal whitespace: U+0009 (tab), U+0020 (space), U+00A0 (NBSP), U+1680,
   * U+2000–U+200A (11 chars), U+202F, U+205F, U+3000.
   *
   * @param negated {@code true} for {@code \H} (complement)
   * @return the character-class expression; never null
   */
  private static Expr horizontalSpaceClass(boolean negated) {
    List<CharRange> positive = List.of(
        new CharRange('\t', '\t'),
        new CharRange(' ', ' '),
        new CharRange('\u00A0', '\u00A0'),
        new CharRange('\u1680', '\u1680'),
        new CharRange('\u2000', '\u200A'),
        new CharRange('\u202F', '\u202F'),
        new CharRange('\u205F', '\u205F'),
        new CharRange('\u3000', '\u3000'));
    if (!negated) {
      return new CharClass(false, positive);
    }
    return new CharClass(false, complementOf(positive));
  }

  /**
   * Builds the character class for {@code \v} (vertical whitespace) or {@code \V}.
   *
   * <p>Vertical whitespace: U+000A (LF), U+000B (VT), U+000C (FF), U+000D (CR),
   * U+0085 (NEL), U+2028 (LS), U+2029 (PS).
   *
   * @param negated {@code true} for {@code \V} (complement)
   * @return the character-class expression; never null
   */
  private static Expr verticalSpaceClass(boolean negated) {
    List<CharRange> positive = List.of(
        new CharRange('\n', '\r'),        // U+000A–U+000D (LF, VT, FF, CR)
        new CharRange('\u0085', '\u0085'),
        new CharRange('\u2028', '\u2029'));
    if (!negated) {
      return new CharClass(false, positive);
    }
    return new CharClass(false, complementOf(positive));
  }
}
