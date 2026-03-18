package com.orbital.parse;

/**
 * Exception thrown when a pattern cannot be parsed or compiled.
 */
public class PatternSyntaxException extends RuntimeException {
    private final int column;
    private final String snippet;

    public PatternSyntaxException(String message, String source, int column) {
        super(createMessage(message, source, column));
        this.column = column;
        this.snippet = createSnippet(source, column);
    }

    public PatternSyntaxException(String message) {
        super(message);
        this.column = -1;
        this.snippet = null;
    }

    private static String createMessage(String message, String source, int column) {
        return String.format("PatternSyntaxException: %s at column %d", message, column);
    }

    private static String createSnippet(String source, int column) {
        if (source == null || source.length() <= 80) {
            return source;
        }
        int start = Math.max(0, column - 40);
        int end = Math.min(source.length(), column + 40);
        return source.substring(start, end);
    }

    public int sourceColumn() {
        return column;
    }

    public String snippet() {
        return snippet;
    }
}