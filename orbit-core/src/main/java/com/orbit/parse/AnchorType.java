package com.orbital.parse;

/**
 * Types of anchor expressions.
 */
public enum AnchorType {
    START,          // ^
    END,            // $
    WORD_BOUNDARY,  // \b
    NOT_WORD_BOUNDARY, // \B
    LINE_START,     // \A
    LINE_END,       // \z, \Z
    BOF,            // \G
    EOF             // \z
}