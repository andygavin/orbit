package com.orbit.parse;

/**
 * Types of anchor expressions.
 */
public enum AnchorType {
    START,          // ^
    END,            // $
    WORD_BOUNDARY,  // \b
    NOT_WORD_BOUNDARY, // \B
    LINE_START,     // \A  — absolute start of input
    LINE_END,       // \Z  — end of input, or before final line terminator
    BOF,            // \G  — position after previous match
    EOF             // \z  — strict end of input (no trailing terminator exception)
}