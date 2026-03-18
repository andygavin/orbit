package com.orbital.util;

/**
 * Classification of which engine is safe for a given pattern.
 */
public enum EngineHint {
    DFA_SAFE,           // No backrefs, no lookarounds, no balancing groups
    ONE_PASS_SAFE,      // DFA_SAFE + one-pass check passed + bounded output
    PIKEVM_ONLY,        // Captures or moderate transducers
    NEEDS_BACKTRACKER,  // Balancing groups or contextual -> rules
    GRAMMAR_RULE        // Recursive grammar production
}