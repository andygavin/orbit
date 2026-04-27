package com.orbit.parse;

/**
 * Exception thrown when a pattern cannot be parsed or compiled.
 *
 * <p>The formatted message conforms to the JDK {@code java.util.regex.PatternSyntaxException}
 * output format: {@code "<message> near index <offset>\n  <source>\n  <caret>"}, where
 * {@code <caret>} is a row of spaces terminated by {@code ^} pointing at the error position.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public class PatternSyntaxException extends RuntimeException {

  private final int index;
  private final String snippet;

  /**
   * Creates a new exception with full source-position information.
   *
   * @param message a short description of the syntax error; must not be null
   * @param source  the full pattern string that caused the error; must not be null
   * @param index   the zero-based byte offset of the error position within {@code source}
   */
  public PatternSyntaxException(String message, String source, int index) {
    super(createMessage(message, source, index));
    this.index = index;
    this.snippet = createSnippet(source, index);
  }

  /**
   * Creates a new exception without source-position information.
   *
   * @param message a short description of the syntax error; must not be null
   */
  public PatternSyntaxException(String message) {
    super(message);
    this.index = -1;
    this.snippet = null;
  }

  /**
   * Builds a JDK-compatible multi-line error message.
   *
   * <p>Format:
   * <pre>
   * {message} near index {index}
   *   {source-window}
   *   {caret}
   * </pre>
   *
   * @param message the short error description; must not be null
   * @param source  the full pattern string; must not be null
   * @param index   the zero-based error offset
   * @return the formatted message string, never null
   */
  private static String createMessage(String message, String source, int index) {
    if (index < 0) {
      // No position information available; omit the source window and caret.
      return message;
    }
    int windowStart = Math.max(0, index - 40);
    int windowEnd = Math.min(source.length(), index + 40);
    String window = source.substring(windowStart, windowEnd);
    int caretPos = index - windowStart;
    String caret = " ".repeat(caretPos) + "^";
    return String.format("%s near index %d%n  %s%n  %s", message, index, window, caret);
  }

  /**
   * Returns the source window (at most 80 characters) centred on the error position.
   *
   * @param source the full pattern string
   * @param index  the zero-based error offset
   * @return the source window, or {@code null} if {@code source} is null
   */
  private static String createSnippet(String source, int index) {
    if (source == null || source.length() <= 80) {
      return source;
    }
    int start = Math.max(0, index - 40);
    int end = Math.min(source.length(), index + 40);
    return source.substring(start, end);
  }

  /**
   * Returns the zero-based byte offset of the error position within the source pattern.
   * Returns {@code -1} if no position information is available.
   *
   * @return the error index, or {@code -1} when unavailable
   */
  public int sourceOffset() {
    return index;
  }

  /**
   * Returns the zero-based byte offset of the error position within the source pattern.
   * Returns {@code -1} if no position information is available.
   *
   * @return the error index, or {@code -1} when unavailable
   * @deprecated Use {@link #sourceOffset()} instead.
   */
  @Deprecated
  public int sourceColumn() {
    return index;
  }

  /**
   * Returns the source snippet (at most 80 characters) centred on the error position,
   * or {@code null} when no source information is available.
   *
   * @return the source snippet, or {@code null}
   */
  public String snippet() {
    return snippet;
  }
}
