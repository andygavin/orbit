package com.orbital;

import com.orbital.api.Transducer;
import com.orbital.api.Transducer.TransducerException;
import com.orbital.api.Transducer.NonInvertibleTransducerException;
import com.orbital.api.Transducer.TransducerCompositionException;
import com.orbital.util.TransducerFlag;
import org.junit.jupiter.api.Test;
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
        Transducer transducer2 = Transducer.compile("world");
        Transducer composed = transducer1.compose(transducer2);
        assertNotNull(composed);
    }

    @Test
    void testInvert() {
        Transducer transducer = Transducer.compile("hello");
        Transducer inverted = transducer.invert();
        assertNotNull(inverted);
    }

    @Test
    void testTokenizeIterator() {
        Transducer transducer = Transducer.compile("hello");
        assertNotNull(transducer.tokenizeIterator(new java.io.StringReader("hello")));
    }

    @Test
    void testTokenizeStream() {
        Transducer transducer = Transducer.compile("hello");
        assertNotNull(transducer.tokenizeStream(new java.io.StringReader("hello")));
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
        assertEquals("", transducer.applyUp(""));
        assertEquals("", transducer.applyDown(""));
    }

    @Test
    void testNoMatch() {
        Transducer transducer = Transducer.compile("hello");
        assertEquals("", transducer.tryApplyUp("world").orElse(""));
    }

    @Test
    void testTransducerException() {
        Transducer transducer = Transducer.compile("hello");
        assertThrows(TransducerException.class, () -> {
            // This would throw if input doesn't match
            // For now, we test the exception type
        });
    }

    @Test
    void testNonInvertibleTransducerException() {
        Transducer transducer = Transducer.compile("hello");
        assertThrows(NonInvertibleTransducerException.class, () -> {
            // This would throw if transducer is not invertible
            // For now, we test the exception type
        });
    }

    @Test
    void testTransducerCompositionException() {
        Transducer transducer1 = Transducer.compile("hello");
        Transducer transducer2 = Transducer.compile("world");
        assertThrows(TransducerCompositionException.class, () -> {
            // This would throw if composition fails
            // For now, we test the exception type
        });
    }

    @Test
    void testComplexTransducer() {
        Transducer transducer = Transducer.compile("a:(b)");
        assertEquals("b", transducer.applyUp("a"));
    }

    @Test
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
}