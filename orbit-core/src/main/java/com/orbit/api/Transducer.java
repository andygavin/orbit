package com.orbital.api;

import com.orbital.prog.Prog;
import com.orbital.prog.Metadata;
import com.orbital.util.TransducerFlag;
import com.orbital.parse.Expr;
import com.orbital.parse.Parser;
import com.orbital.parse.PatternSyntaxException;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Transducer class for regex-based text transformations.
 */
public final class Transducer implements Serializable {

    private final Prog prog;
    private final Metadata metadata;

    private Transducer(Prog prog, Metadata metadata) {
        this.prog = prog;
        this.metadata = metadata;
    }

    /**
     * Compiles a transducer expression.
     */
    public static Transducer compile(String transducerExpr, TransducerFlag... flags) {
        if (transducerExpr == null) {
            throw new NullPointerException("Transducer expression cannot be null");
        }
        if (flags == null) {
            throw new NullPointerException("Flags cannot be null");
        }

        // Parse the transducer expression (handles the : syntax for output)
        Expr expr = parseTransducerExpression(transducerExpr);

        // Run analysis pipeline
        com.orbital.hir.HirNode hir = com.orbital.hir.AnalysisVisitor.analyze(expr);

        // Build the Prog
        Prog prog = buildProg(hir);

        // Create metadata
        Metadata metadata = new Metadata(
            hir.getHint(),
            hir.getPrefilter(),
            // Group count
            0,
            // Max output length
            0,
            // Weighted
            false,
            // Is transducer
            true
        );

        return new Transducer(prog, metadata);
    }

    /**
     * Applies the transducer to the input string.
     */
    public String applyUp(String input) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }

        // Execute the transducer program
        MatchResult result = executeTransducer(input);

        if (!result.matches()) {
            throw new TransducerException("Input does not match transducer pattern");
        }

        return result.output();
    }

    /**
     * Tries to apply the transducer without throwing exceptions.
     */
    public Optional<String> tryApplyUp(String input) {
        try {
            return Optional.of(applyUp(input));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Applies the transducer in reverse (requires invertible transducer).
     */
    public String applyDown(String output) {
        if (output == null) {
            throw new NullPointerException("Output cannot be null");
        }

        // Check if transducer is invertible
        if (!isTransducerInvertible()) {
            throw new NonInvertibleTransducerException("Transducer is not invertible");
        }

        // Execute reverse transformation
        // For now, we return the same output (placeholder)
        return output;
    }

    /**
     * Tokenizes the input into structured tokens.
     */
    public List<Token> tokenize(String input) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }

        // Execute the transducer
        MatchResult result = executeTransducer(input);

        if (!result.matches()) {
            return List.of(new ErrorToken("No match", 0, input.length()));
        }

        // For now, return simple match token
        return List.of(new MatchToken("match", group(), result.start(), result.end()));
    }

    /**
     * Composes this transducer with another.
     */
    public Transducer compose(Transducer other) {
        if (other == null) {
            throw new NullPointerException("Other transducer cannot be null");
        }

        // Check alphabet compatibility
        if (!checkAlphabetCompatibility(other)) {
            throw new TransducerCompositionException("Output alphabet of this transducer does not intersect input alphabet of other");
        }

        // Compose the transducer programs
        // For now, return a new transducer (placeholder)
        return new Transducer(this.prog, this.metadata);
    }

    /**
     * Returns the inverse of this transducer.
     */
    public Transducer invert() {
        // Check if transducer is invertible
        if (!isTransducerInvertible()) {
            throw new NonInvertibleTransducerException("Transducer is not invertible");
        }

        // Create inverse transducer
        // For now, return a new transducer (placeholder)
        return new Transducer(this.prog, this.metadata);
    }

    /**
     * Tokenizes from a Reader using an iterator.
     */
    public Iterator<Token> tokenizeIterator(java.io.Reader input) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }

        // For now, return empty iterator (placeholder)
        return List.<Token>of().iterator();
    }

    /**
     * Tokenizes from a Reader using a stream.
     */
    public Stream<Token> tokenizeStream(java.io.Reader input) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }

        // For now, return empty stream (placeholder)
        return Stream.empty();
    }

    /**
     * Returns the underlying program.
     */
    Prog prog() {
        return prog;
    }

    /**
     * Returns the metadata.
     */
    Metadata metadata() {
        return metadata;
    }

    /**
     * Parses a transducer expression (handles : syntax).
     */
    private static Expr parseTransducerExpression(String expr) {
        // Find the first colon that separates input from output
        int colonIndex = expr.indexOf(':');
        if (colonIndex == -1) {
            // No output part - just a regular expression
            return Parser.parse(expr);
        }

        String inputPart = expr.substring(0, colonIndex);
        String outputPart = expr.substring(colonIndex + 1);

        // Parse input and output separately
        Expr inputExpr = Parser.parse(inputPart);
        Expr outputExpr = Parser.parse(outputPart);

        // Create Pair node
        return new com.orbital.parse.Pair(inputExpr, outputExpr, java.util.OptionalDouble.empty());
    }

    /**
     * Executes the transducer program.
     */
    private MatchResult executeTransducer(String input) {
        // For now, return a simple result (placeholder)
        return new MatchResult(true, 0, input.length(), new ArrayList<>(), input);
    }

    /**
     * Checks if transducer is invertible.
     */
    private boolean isTransducerInvertible() {
        // For now, assume all transducers are invertible (placeholder)
        return true;
    }

    /**
     * Checks alphabet compatibility with another transducer.
     */
    private boolean checkAlphabetCompatibility(Transducer other) {
        // For now, assume all transducers are compatible (placeholder)
        return true;
    }

    /**
     * Transducer-specific exception.
     */
    public static class TransducerException extends RuntimeException {
        public TransducerException(String message) {
            super(message);
        }
    }

    /**
     * Exception for non-invertible transducers.
     */
    public static class NonInvertibleTransducerException extends RuntimeException {
        public NonInvertibleTransducerException(String message) {
            super(message);
        }
    }

    /**
     * Exception for transducer composition errors.
     */
    public static class TransducerCompositionException extends RuntimeException {
        public TransducerCompositionException(String message) {
            super(message);
        }
    }
}