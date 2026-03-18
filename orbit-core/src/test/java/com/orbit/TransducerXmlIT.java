package com.orbital;

import com.orbital.api.Transducer;
import com.orbital.api.Transducer.TransducerException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransducerXmlIT {

    @Test
    void testXmlTagTransformation() {
        // Transducer to rename XML tags: <old> -> <new>
        Transducer transducer = Transducer.compile("<old>:<new>");

        String input = "<old>Hello</old>";
        String expected = "<new>Hello</new>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testXmlAttributeTransformation() {
        // Transducer to rename attributes: name="value" -> id="value"
        Transducer transducer = Transducer.compile("name=:(id=)");

        String input = "<tag name=\"test\"></tag>";
        String expected = "<tag id=\"test\"></tag>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testXmlContentTransformation() {
        // Transducer to uppercase content: <tag>text</tag> -> <tag>TEXT</tag>
        Transducer transducer = Transducer.compile(">:<tag>:</tag>");

        String input = "<tag>hello</tag>";
        String expected = "<tag>HELLO</tag>";

        String result = transducer.applyUp(input.toUpperCase());
        assertEquals(expected, result);
    }

    @Test
    void testNestedXmlTags() {
        // Transducer to rename nested tags
        Transducer transducer = Transducer.compile("<outer>:<outer><inner>:<inner>");

        String input = "<outer><inner>content</inner></outer>";
        String expected = "<outer><inner>content</inner></outer>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testXmlWithAttributes() {
        // Transducer to rename tags with attributes
        Transducer transducer = Transducer.compile("<tag class=:<div class=);");

        String input = "<tag class=\"test\">content</tag>";
        String expected = "<div class=\"test\">content</div>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testXmlEntities() {
        // Transducer to handle XML entities
        Transducer transducer = Transducer.compile("&lt;:&lt;&gt;:&gt;);");

        String input = "&lt;div&gt;content&lt;/div&gt;";
        String expected = "<div>content</div>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testLargeXmlDocument() {
        // Test with a larger XML document
        String input = "<root><item>1</item><item>2</item><item>3</item></root>";
        Transducer transducer = Transducer.compile("<item>:<element>");

        String expected = "<root><element>1</element><element>2</element><element>3</element></root>";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testMalformedXml() {
        // Test with malformed XML - should still transform what it can
        String input = "<tag>unclosed";
        Transducer transducer = Transducer.compile("<tag>:<div>");

        String expected = "<div>unclosed";

        String result = transducer.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testNestedTransformations() {
        // Test composing multiple transformations
        Transducer renameTags = Transducer.compile("<old>:<new>");
        Transducer uppercaseContent = Transducer.compile(">:<tag>:</tag>");

        String input = "<old>hello</old>";
        String expected = "<new>HELLO</new>";

        // Compose transformations: first rename, then uppercase
        Transducer composed = renameTags.compose(uppercaseContent);
        String result = composed.applyUp(input);
        assertEquals(expected, result);
    }

    @Test
    void testTransducerException() {
        Transducer transducer = Transducer.compile("nonexistent");
        assertThrows(TransducerException.class, () -> transducer.applyUp("test"));
    }

    @Test
    void testNonInvertibleTransducerException() {
        Transducer transducer = Transducer.compile("a:(b)");
        assertThrows(TransducerException.class, () -> transducer.applyDown("b"));
    }

    @Test
    void testTransducerCompositionException() {
        Transducer transducer1 = Transducer.compile("a:(b)");
        Transducer transducer2 = Transducer.compile("c:(d)");
        assertThrows(TransducerException.class, () -> transducer1.compose(transducer2));
    }

    @Test
    void testEmptyInput() {
        Transducer transducer = Transducer.compile("<tag>:</tag>");
        assertEquals("", transducer.applyUp(""));
    }

    @Test
    void testNoMatch() {
        Transducer transducer = Transducer.compile("<tag>:</tag>");
        assertEquals("", transducer.applyUp("no tags here").orElse(""));
    }

    @Test
    void testTokenizeXml() {
        Transducer transducer = Transducer.compile("<tag>:</tag>");
        List<com.orbital.api.Token> tokens = transducer.tokenize("<tag>hello</tag>");
        assertNotNull(tokens);
        assertEquals(1, tokens.size());
    }
}