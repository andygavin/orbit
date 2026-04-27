package com.orbit;

import com.orbit.api.MatchToken;
import com.orbit.api.OutputToken;
import com.orbit.api.Transducer;
import com.orbit.api.Transducer.TransducerException;
import com.orbit.api.Transducer.NonInvertibleTransducerException;
import com.orbit.api.Token;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransducerXmlIT {

  /**
   * Reconstructs a string from tokenize output: matched tokens use their transducer
   * output; gap tokens preserve the original text.
   */
  private static String reconstruct(List<Token> tokens) {
    StringBuilder sb = new StringBuilder();
    for (Token tok : tokens) {
      if (tok instanceof OutputToken ot) {
        sb.append(ot.output());
      } else if (tok instanceof MatchToken mt) {
        sb.append(mt.value());
      }
    }
    return sb.toString();
  }

  @Test
  void testXmlTagTransformation() {
    // tokenize replaces all <old> opening tags with <new>; gaps are passed through verbatim
    Transducer transducer = Transducer.compile("<old>:<new>");
    String result = reconstruct(transducer.tokenize("<old>Hello</old>"));
    assertEquals("<new>Hello</old>", result);
  }

  @Test
  void testXmlAttributeTransformation() {
    // Replace the attribute name "name=" with "id=" wherever it appears
    Transducer transducer = Transducer.compile("name=:id=");
    String result = reconstruct(transducer.tokenize("<tag name=\"test\"></tag>"));
    assertEquals("<tag id=\"test\"></tag>", result);
  }

  @Test
  void testXmlContentTransformation() {
    // Full-match transduction: the entire input must equal the input side of the pattern
    Transducer transducer = Transducer.compile("<tag>:<div>");
    assertEquals("<div>", transducer.applyUp("<tag>"));
  }

  @Test
  void testNestedXmlTags() {
    // Only the <outer> opening tag is renamed; inner content is preserved in gaps
    Transducer transducer = Transducer.compile("<outer>:<section>");
    String result = reconstruct(transducer.tokenize("<outer><inner>content</inner></outer>"));
    assertEquals("<section><inner>content</inner></outer>", result);
  }

  @Test
  void testXmlWithAttributes() {
    // Replace the attribute keyword "class=" with "style=" via tokenize
    Transducer transducer = Transducer.compile("class=:style=");
    String result = reconstruct(transducer.tokenize("<tag class=\"test\">content</tag>"));
    assertEquals("<tag style=\"test\">content</tag>", result);
  }

  @Test
  void testXmlEntities() {
    // Replace all &amp; entity references with a literal ampersand
    Transducer transducer = Transducer.compile("&amp;:&");
    String result = reconstruct(transducer.tokenize("a &amp; b &amp; c"));
    assertEquals("a & b & c", result);
  }

  @Test
  void testLargeXmlDocument() {
    // Replace all <item> opening tags with <element>; closing tags are untouched gaps
    Transducer transducer = Transducer.compile("<item>:<element>");
    String input = "<root><item>1</item><item>2</item><item>3</item></root>";
    String result = reconstruct(transducer.tokenize(input));
    assertEquals("<root><element>1</item><element>2</item><element>3</item></root>", result);
  }

  @Test
  void testMalformedXml() {
    // tokenize handles partial/malformed input correctly — gaps are preserved
    Transducer transducer = Transducer.compile("<tag>:<div>");
    String result = reconstruct(transducer.tokenize("<tag>unclosed"));
    assertEquals("<div>unclosed", result);
  }

  @Test
  void testNestedTransformations() {
    // Compose two transducers: <old> -> <tmp> -> <new>
    // The composed applyUp chains the output of the first as input to the second
    Transducer step1 = Transducer.compile("<old>:<tmp>");
    Transducer step2 = Transducer.compile("<tmp>:<new>");
    Transducer composed = step1.compose(step2);
    assertEquals("<new>", composed.applyUp("<old>"));
  }

  @Test
  void testTransducerException() {
    Transducer transducer = Transducer.compile("nonexistent");
    assertThrows(TransducerException.class, () -> transducer.applyUp("test"));
  }

  @Test
  void testNonInvertibleTransducerException() {
    // Composed transducers cannot be inverted
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    Transducer composed = t1.compose(t2);
    assertThrows(NonInvertibleTransducerException.class, composed::invert);
  }

  @Test
  void testTransducerCompositionChains() {
    // compose() always succeeds in Phase 6 and correctly chains applyUp
    Transducer t1 = Transducer.compile("a:b");
    Transducer t2 = Transducer.compile("b:c");
    Transducer composed = t1.compose(t2);
    assertNotNull(composed);
    assertEquals("c", composed.applyUp("a"));
  }

  @Test
  void testEmptyInput() {
    // The empty pattern is an identity transducer; it matches and returns the empty string
    Transducer transducer = Transducer.compile("");
    assertEquals("", transducer.applyUp(""));
  }

  @Test
  void testNoMatch() {
    Transducer transducer = Transducer.compile("<tag>");
    assertEquals("", transducer.tryApplyUp("no tags here").orElse(""));
  }

  @Test
  void testTokenizeXml() {
    // <tag> matches the 5-char literal; the rest of the input becomes a gap token
    Transducer transducer = Transducer.compile("<tag>:<div>");
    List<Token> tokens = transducer.tokenize("<tag>hello</tag>");
    assertNotNull(tokens);
    assertEquals(2, tokens.size());
    assertInstanceOf(OutputToken.class, tokens.get(0));
    assertEquals("<div>", ((OutputToken) tokens.get(0)).output());
    assertInstanceOf(MatchToken.class, tokens.get(1));
    assertEquals("hello</tag>", ((MatchToken) tokens.get(1)).value());
  }
}
