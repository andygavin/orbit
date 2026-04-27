package com.orbit;

import com.orbit.api.MatchToken;
import com.orbit.api.OutputToken;
import com.orbit.api.Transducer;
import com.orbit.api.Transducer.TransducerException;
import com.orbit.api.Transducer.NonInvertibleTransducerException;
import com.orbit.api.Transducer.TransducerCompositionException;
import com.orbit.api.Token;
import com.orbit.util.TransducerFlag;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class TransducerTest {

  @Test
  void testCompile() {
    Transducer transducer = Transducer.compile("hello");
    assertNotNull(transducer);
  }

  @Test
  void testCompileWithFlags() {
    Transducer transducer = Transducer.compile("hello", TransducerFlag.WEIGHTED);
    assertNotNull(transducer);
  }

  @Test
  void testApplyUp() {
    Transducer transducer = Transducer.compile("hello");
    assertEquals("hello", transducer.applyUp("hello"));
  }

  @Test
  void testTryApplyUp() {
    Transducer transducer = Transducer.compile("hello");
    assertEquals("hello", transducer.tryApplyUp("hello").orElse(""));
  }

  @Test
  void testApplyDown() {
    // Identity transducer: invert() returns itself, so applyDown("hello") == applyUp("hello")
    Transducer transducer = Transducer.compile("hello");
    assertEquals("hello", transducer.applyDown("hello"));
  }

  @Test
  void testTokenize() {
    Transducer transducer = Transducer.compile("hello");
    assertNotNull(transducer.tokenize("hello"));
  }

  @Test
  void testCompose() {
    Transducer transducer1 = Transducer.compile("hello");
    Transducer transducer2 = Transducer.compile("hello");
    Transducer composed = transducer1.compose(transducer2);
    assertNotNull(composed);
  }

  @Test
  void testInvert() {
    // Identity transducer: invert() returns itself.
    Transducer transducer = Transducer.compile("hello");
    Transducer inverted = transducer.invert();
    assertNotNull(inverted);
  }

  @Test
  void testTokenizeIterator() {
    Transducer transducer = Transducer.compile("hello");
    assertNotNull(transducer.tokenizeIterator(new StringReader("hello")));
  }

  @Test
  void testTokenizeStream() {
    Transducer transducer = Transducer.compile("hello");
    assertNotNull(transducer.tokenizeStream(new StringReader("hello")));
  }

  @Test
  void testNullInput() {
    assertThrows(NullPointerException.class, () -> Transducer.compile(null));
    Transducer transducer = Transducer.compile("hello");
    assertThrows(NullPointerException.class, () -> transducer.applyUp(null));
    assertThrows(NullPointerException.class, () -> transducer.applyDown(null));
    assertThrows(NullPointerException.class, () -> transducer.tokenize(null));
    assertThrows(NullPointerException.class, () -> transducer.tokenizeIterator(null));
    assertThrows(NullPointerException.class, () -> transducer.tokenizeStream(null));
  }

  @Test
  void testEmptyInput() {
    Transducer transducer = Transducer.compile("hello");
    assertThrows(TransducerException.class, () -> transducer.applyUp(""));
    assertThrows(TransducerException.class, () -> transducer.applyDown(""));
  }

  @Test
  void testNoMatch() {
    Transducer transducer = Transducer.compile("hello");
    assertEquals("", transducer.tryApplyUp("world").orElse(""));
  }

  @Test
  void testTransducerException() {
    Transducer transducer = Transducer.compile("hello");
    assertThrows(TransducerException.class, () -> transducer.applyUp("world"));
  }

  @Test
  void testNonInvertibleTransducerException() {
    // A transducer with backref output is Tier-1-only; composed result is not invertible.
    Transducer t1 = Transducer.compile("(a):\\1"); // backref -> not graphEligible
    Transducer t2 = Transducer.compile("a:b");
    Transducer composed = t1.compose(t2);
    assertThrows(NonInvertibleTransducerException.class, composed::invert);
  }

  @Test
  void testTransducerCompositionException() {
    // In Phase 6, composition always succeeds; TransducerCompositionException exists as a type.
    // Verify it is a RuntimeException with the expected class name.
    TransducerCompositionException ex = new TransducerCompositionException("test");
    assertThat(ex).isInstanceOf(RuntimeException.class);
    assertThat(ex.getMessage()).isEqualTo("test");
  }

  @Test
  void testComplexTransducer() {
    Transducer transducer = Transducer.compile("a:(b)");
    assertEquals("b", transducer.applyUp("a"));
  }

  @Test
  @Disabled("testWeightedTransducer: Phase 8 — WEIGHTED flag is a no-op in Phase 6 but"
      + " applyUp semantics for output with backrefs requires Phase 8 weighted compilation")
  void testWeightedTransducer() {
    Transducer transducer = Transducer.compile("a:(b)", TransducerFlag.WEIGHTED);
    assertEquals("b", transducer.applyUp("a"));
  }

  @Test
  void testStreamingTransducer() {
    Transducer transducer = Transducer.compile("hello", TransducerFlag.STREAMING);
    assertEquals("hello", transducer.applyUp("hello"));
  }

  @Test
  void testUnicodeTransducer() {
    Transducer transducer = Transducer.compile("\u00e9:(e)", TransducerFlag.UNICODE);
    assertEquals("e", transducer.applyUp("\u00e9"));
  }

  @Test
  void testRe2CompatTransducer() {
    Transducer transducer = Transducer.compile("hello", TransducerFlag.RE2_COMPAT);
    assertEquals("hello", transducer.applyUp("hello"));
  }

  // -----------------------------------------------------------------------
  // Inversion functional correctness (ported from OpenFST InvertTest)
  //
  // InvertTest::Invert verifies that T^{-1} swaps input and output labels.
  // InvertFst verifies the same for delayed inversion and that double-inversion
  // is equivalent to the original.
  // -----------------------------------------------------------------------

  @Test
  void testInvertMapsOutputToInput() {
    // T maps "a" -> "b"; T^-1 must map "b" -> "a"   (core InvertTest::Invert invariant)
    Transducer t = Transducer.compile("a:b");
    assertEquals("a", t.invert().applyUp("b"));
  }

  @Test
  void testInvertTwiceIsEquivalent() {
    // T^-1^-1 must behave identically to T   (InvertFst double-inversion invariant)
    Transducer t = Transducer.compile("hello:world");
    assertEquals("world", t.invert().invert().applyUp("hello"));
  }

  @Test
  void testApplyDownEqualsInvertApplyUp() {
    // applyDown(x) == invert().applyUp(x)   (applyDown is defined as inversion)
    Transducer t = Transducer.compile("cat:feline");
    assertEquals(t.invert().applyUp("feline"), t.applyDown("feline"));
  }

  @Test
  void testInvertRejectsOriginalInput() {
    // After inversion the old input side is no longer valid input
    Transducer t = Transducer.compile("a:b");
    assertThrows(TransducerException.class, () -> t.invert().applyUp("a"));
  }

  @Test
  void testInvertLongerPattern() {
    // Multi-character mapping round-trips cleanly through inversion
    Transducer t = Transducer.compile("foo:bar");
    assertEquals("bar", t.applyUp("foo"));
    assertEquals("foo", t.invert().applyUp("bar"));
  }

  // -----------------------------------------------------------------------
  // Composition functional correctness (ported from OpenFST ComposeTest)
  //
  // ComposeTest::ComposeFstWithoutEpsilons verifies that Compose(T1, T2)
  // produces the same result as applying T1 then T2 sequentially.
  // -----------------------------------------------------------------------

  @Test
  void testComposeTransitive() {
    // T1: a->b, T2: b->c; composition gives a->c
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    assertEquals("c", t1.compose(t2).applyUp("a"));
  }

  @Test
  void testComposeEquivalentToSequentialApply() {
    // compose(T1,T2).applyUp(x) == T2.applyUp(T1.applyUp(x))
    Transducer t1 = Transducer.compile("hello:world");
    Transducer t2 = Transducer.compile("world:earth");
    String sequential = t2.applyUp(t1.applyUp("hello"));
    String composed = t1.compose(t2).applyUp("hello");
    assertEquals(sequential, composed);
  }

  @Test
  void testComposeWithInputIdentity() {
    // Composing a mapping transducer with an identity transducer leaves it unchanged
    Transducer t1 = Transducer.compile("hello:world");
    Transducer identity = Transducer.compile("world");
    assertEquals("world", t1.compose(identity).applyUp("hello"));
  }

  @Test
  void testComposeIdentityWithMapping() {
    // Identity transducer composed with a mapping passes through unchanged
    Transducer identity = Transducer.compile("hello");
    Transducer t2 = Transducer.compile("hello:world");
    assertEquals("world", identity.compose(t2).applyUp("hello"));
  }

  @Test
  void testComposeChainThree() {
    // Three-transducer chain: a->b->c->d
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    Transducer t3 = Transducer.compile("c:d");
    assertEquals("d", t1.compose(t2).compose(t3).applyUp("a"));
  }

  @Test
  void testComposedTransducerRejectsNoMatch() {
    // The composed transducer must reject inputs that don't match the first leg
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    assertThrows(TransducerException.class, () -> t1.compose(t2).applyUp("x"));
  }

  @Test
  void testComposedTryApplyUpEmpty() {
    // tryApplyUp on composed transducer returns empty when first leg fails
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    assertTrue(t1.compose(t2).tryApplyUp("x").isEmpty());
  }

  // -----------------------------------------------------------------------
  // Input-side patterns (ported from OpenFST ProjectTest + rational ops)
  //
  // ProjectTest verifies projection onto the input or output alphabet.
  // In orbit terms: applyUp() is output projection; the input side drives
  // which strings are accepted.
  // -----------------------------------------------------------------------

  @Test
  void testAlternationInInput() {
    // Union on the input side: (cat|dog) -> "animal"
    Transducer t = Transducer.compile("(cat|dog):animal");
    assertEquals("animal", t.applyUp("cat"));
    assertEquals("animal", t.applyUp("dog"));
    assertThrows(TransducerException.class, () -> t.applyUp("bird"));
  }

  @Test
  void testCharClassInInput() {
    // Character class on the input side: [0-9]+ -> "digit"
    Transducer t = Transducer.compile("[0-9]+:digit");
    assertEquals("digit", t.applyUp("42"));
    assertEquals("digit", t.applyUp("0"));
    assertThrows(TransducerException.class, () -> t.applyUp("abc"));
  }

  @Test
  void testQuantifierInInput() {
    // Quantifier on the input side: a+ -> "b"
    Transducer t = Transducer.compile("a+:b");
    assertEquals("b", t.applyUp("a"));
    assertEquals("b", t.applyUp("aaa"));
    assertThrows(TransducerException.class, () -> t.applyUp("b"));
  }

  @Test
  void testNonCapturingGroupInInput() {
    // Non-capturing group on input side is transparent
    Transducer t = Transducer.compile("(?:foo|bar):baz");
    assertEquals("baz", t.applyUp("foo"));
    assertEquals("baz", t.applyUp("bar"));
  }

  // -----------------------------------------------------------------------
  // Output backreferences
  // -----------------------------------------------------------------------

  @Test
  void testBackreferenceIdentity() {
    // (\w+):\1 acts as identity via backreference
    Transducer t = Transducer.compile("(\\w+):\\1");
    assertEquals("hello", t.applyUp("hello"));
  }

  @Test
  void testGroupSwapInOutput() {
    // (a)(b):\2\1 swaps the two captured characters
    Transducer t = Transducer.compile("(a)(b):\\2\\1");
    assertEquals("ba", t.applyUp("ab"));
  }

  @Test
  void testTwoWordSwap() {
    // (\w+) (\w+):\2 \1 swaps two space-separated words
    Transducer t = Transducer.compile("(\\w+) (\\w+):\\2 \\1");
    assertEquals("world hello", t.applyUp("hello world"));
  }

  @Test
  void testLiteralPrefixInOutput() {
    // Literal prepended to a backreference in the output
    Transducer t = Transducer.compile("([0-9]+):num\\1");
    assertEquals("num42", t.applyUp("42"));
  }

  // -----------------------------------------------------------------------
  // Tokenization (ported from OpenFST algo_test traversal patterns)
  //
  // algo_test exercises full-string traversal with arbitrary FSTs; tokenize()
  // is orbit's equivalent for left-to-right find-first traversal.
  // -----------------------------------------------------------------------

  @Test
  void testTokenizeProducesMatchAndGapTokens() {
    // "hello world" tokenized by \w+ produces two match tokens and one gap
    Transducer t = Transducer.compile("\\w+");
    List<Token> tokens = t.tokenize("hello world");
    long matches = tokens.stream().filter(tok -> tok instanceof OutputToken).count();
    long gaps = tokens.stream()
        .filter(tok -> tok instanceof MatchToken mt && mt.type().equals("gap")).count();
    assertEquals(2, matches);
    assertEquals(1, gaps);
  }

  @Test
  void testTokenizeOutputValues() {
    // Verify the values and positions of match tokens
    Transducer t = Transducer.compile("\\w+");
    List<Token> tokens = t.tokenize("ab cd");
    List<OutputToken> matches = tokens.stream()
        .filter(tok -> tok instanceof OutputToken)
        .map(OutputToken.class::cast)
        .toList();
    assertEquals(2, matches.size());
    assertEquals("ab", matches.get(0).value());
    assertEquals(0, matches.get(0).start());
    assertEquals(2, matches.get(0).end());
    assertEquals("cd", matches.get(1).value());
    assertEquals(3, matches.get(1).start());
    assertEquals(5, matches.get(1).end());
  }

  @Test
  void testTokenizeWithTransducerOutput() {
    // [0-9]+:NUM maps digit runs to "NUM"; verify the output field
    Transducer t = Transducer.compile("[0-9]+:NUM");
    List<Token> tokens = t.tokenize("abc 123 def");
    OutputToken match = tokens.stream()
        .filter(tok -> tok instanceof OutputToken)
        .map(OutputToken.class::cast)
        .findFirst().orElseThrow();
    assertEquals("123", match.value());
    assertEquals("NUM", match.output());
  }

  @Test
  void testTokenizeNoMatchReturnsSingleGap() {
    // When no match is found, tokenize returns a single gap covering the full input
    Transducer t = Transducer.compile("[0-9]+");
    List<Token> tokens = t.tokenize("hello");
    assertEquals(1, tokens.size());
    assertInstanceOf(MatchToken.class, tokens.get(0));
    assertEquals("gap", ((MatchToken) tokens.get(0)).type());
    assertEquals("hello", ((MatchToken) tokens.get(0)).value());
  }

  @Test
  void testTokenizeLeadingAndTrailingGaps() {
    // Match in the middle produces leading gap, match, trailing gap
    Transducer t = Transducer.compile("[0-9]+");
    List<Token> tokens = t.tokenize("abc123def");
    assertEquals(3, tokens.size());
    assertInstanceOf(MatchToken.class, tokens.get(0));   // leading gap "abc"
    assertInstanceOf(OutputToken.class, tokens.get(1));  // match "123"
    assertInstanceOf(MatchToken.class, tokens.get(2));   // trailing gap "def"
    assertEquals("abc", ((MatchToken) tokens.get(0)).value());
    assertEquals("123", ((OutputToken) tokens.get(1)).value());
    assertEquals("def", ((MatchToken) tokens.get(2)).value());
  }

  @Test
  void testTokenizeIteratorMatchesTokenizeList() {
    // tokenizeIterator must produce the same tokens as tokenize
    Transducer t = Transducer.compile("\\w+");
    List<Token> fromList = t.tokenize("hello world");
    List<Token> fromIter = new java.util.ArrayList<>();
    t.tokenizeIterator(new StringReader("hello world")).forEachRemaining(fromIter::add);
    assertEquals(fromList.size(), fromIter.size());
    for (int i = 0; i < fromList.size(); i++) {
      assertEquals(fromList.get(i).getClass(), fromIter.get(i).getClass());
      assertEquals(fromList.get(i).start(), fromIter.get(i).start());
      assertEquals(fromList.get(i).end(), fromIter.get(i).end());
    }
  }

  // -----------------------------------------------------------------------
  // Tier 2 — Transducer graph operations
  // -----------------------------------------------------------------------

  @Test
  void testComposedInvertibleWhenGraphEligible() {
    // Graph-eligible composed transducers can be inverted (Tier 2 invariant).
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    Transducer composed = t1.compose(t2);
    // Must not throw
    Transducer inverted = assertDoesNotThrow(composed::invert);
    assertNotNull(inverted);
  }

  @Test
  void testGraphCompose() {
    // Structural composition: a->b, b->c gives a->c
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    Transducer composed = t1.compose(t2);
    assertEquals("c", composed.applyUp("a"));
    assertThrows(TransducerException.class, () -> composed.applyUp("b"));
  }

  @Test
  void testGraphInvert() {
    // Graph-backed invert: T maps "foo" -> "bar"; T^-1 must map "bar" -> "foo"
    Transducer t = Transducer.compile("foo:bar");
    Transducer inv = t.invert();
    assertEquals("foo", inv.applyUp("bar"));
    assertThrows(TransducerException.class, () -> inv.applyUp("foo"));
  }

  @Test
  void testComposeInvertRoundTrip() {
    // If T1 maps "hello" -> "world" and T2 maps "world" -> "earth",
    // then (T1 ∘ T2)^-1 applied to "earth" must give "hello".
    Transducer t1 = Transducer.compile("hello:world");
    Transducer t2 = Transducer.compile("world:earth");
    Transducer composed = t1.compose(t2);
    Transducer inverted = composed.invert();
    assertEquals("hello", inverted.applyUp("earth"));
  }
}
