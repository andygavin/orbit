package com.orbital.api;

/**
 * Sealed token hierarchy for structured output.
 */
public sealed interface Token permits MatchToken, OutputToken, ErrorToken {

    int start();

    int end();
}