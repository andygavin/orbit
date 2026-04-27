package com.orbit.examples;

import com.orbit.api.MatchToken;
import com.orbit.api.OutputToken;
import com.orbit.api.Transducer;
import com.orbit.api.Token;

import java.util.List;

/**
 * Example: XML tag transformation using transducers.
 *
 * <p>Orbit transducers have two usage modes:
 * <ul>
 *   <li>{@link Transducer#applyUp} — full-match: the <em>entire</em> input string must match
 *       the input pattern. Use this when you are transforming a single, known value.</li>
 *   <li>{@link Transducer#tokenize} — find-replace: scans the input left-to-right and
 *       replaces every non-overlapping match; unmatched text passes through as gap tokens.
 *       Use this when you are transforming occurrences within a larger document.</li>
 * </ul>
 */
public class XMLElementTransformation {

    /**
     * Reassembles a tokenized string: match tokens contribute their transducer output,
     * gap tokens contribute the original text.
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

    public static void main(String[] args) {

        // -----------------------------------------------------------------------
        // 1. Full-match transduction: the entire input must equal the input pattern
        // -----------------------------------------------------------------------
        Transducer tagTransducer = Transducer.compile("<old>:<new>");

        // Rename a single opening tag
        System.out.println(tagTransducer.applyUp("<old>"));   // → <new>

        // -----------------------------------------------------------------------
        // 2. Find-replace via tokenize: replace every occurrence in a document
        // -----------------------------------------------------------------------
        String doc = "<old>Hello</old>";
        String result = reconstruct(tagTransducer.tokenize(doc));
        System.out.println(result);   // → <new>Hello</old>  (gap "</old>" is preserved)

        // Rename an attribute keyword throughout a tag
        Transducer attrTransducer = Transducer.compile("name=:id=");
        String tag = "<tag name=\"test\" name=\"other\"></tag>";
        System.out.println(reconstruct(attrTransducer.tokenize(tag)));
        // → <tag id="test" id="other"></tag>

        // Replace XML entities
        Transducer entityTransducer = Transducer.compile("&amp;:&");
        System.out.println(reconstruct(entityTransducer.tokenize("a &amp; b &amp; c")));
        // → a & b & c

        // -----------------------------------------------------------------------
        // 3. Using backreferences to reuse captured content
        // -----------------------------------------------------------------------
        // Wrap every number in <num>...</num>
        Transducer wrapNumbers = Transducer.compile("([0-9]+):<num>\\1</num>");
        String sentence = "There are 42 items and 7 categories.";
        System.out.println(reconstruct(wrapNumbers.tokenize(sentence)));
        // → There are <num>42</num> items and <num>7</num> categories.

        // -----------------------------------------------------------------------
        // 4. Composition: chain two transducers end-to-end
        //    applyUp of the composed transducer feeds the output of step1 into step2
        // -----------------------------------------------------------------------
        Transducer step1 = Transducer.compile("<old>:<tmp>");
        Transducer step2 = Transducer.compile("<tmp>:<new>");
        Transducer chain = step1.compose(step2);
        System.out.println(chain.applyUp("<old>"));   // → <new>

        // -----------------------------------------------------------------------
        // 5. Inversion: swap input and output sides
        // -----------------------------------------------------------------------
        Transducer forward = Transducer.compile("<old>:<new>");
        Transducer backward = forward.invert();
        System.out.println(backward.applyUp("<new>"));   // → <old>

        // -----------------------------------------------------------------------
        // 6. tryApplyUp: returns Optional.empty() instead of throwing on no-match
        // -----------------------------------------------------------------------
        System.out.println(tagTransducer.tryApplyUp("<other>").orElse("(no match)"));
        // → (no match)
    }
}
