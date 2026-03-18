package com.orbital.api;

import com.orbital.prog.MatchResult;
import com.orbital.prog.Prog;
import com.orbital.engine.Engine;
import com.orbital.engine.MetaEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Matcher class - drop-in compatible with java.util.regex.Matcher.
 */
public class Matcher {

    private final Pattern pattern;
    private final CharSequence input;
    private int from;
    private int to;
    private MatchResult lastResult;
    private boolean findFromStart;

    public Matcher(Pattern pattern, CharSequence input) {
        this.pattern = pattern;
        this.input = input;
        this.from = 0;
        this.to = input.length();
        this.lastResult = null;
        this.findFromStart = true;
    }

    /**
     * Attempts to match the entire region against the pattern.
     */
    public boolean matches() {
        Prog prog = pattern.prog();
        Engine engine = getEngine(pattern.metadata().hint());

        lastResult = engine.execute(prog, input.toString(), from, to);
        return lastResult.matches() && lastResult.start() == from && lastResult.end() == to;
    }

    /**
     * Attempts to find the next subsequence of the input sequence that matches the pattern.
     */
    public boolean find() {
        Prog prog = pattern.prog();
        Engine engine = getEngine(pattern.metadata().hint());

        if (findFromStart) {
            findFromStart = false;
            from = 0;
        }

        // Try to find a match starting from 'from'
        for (int i = from; i <= to; i++) {
            lastResult = engine.execute(prog, input.toString(), i, to);
            if (lastResult.matches()) {
                from = lastResult.end();
                return true;
            }
        }

        return false;
    }

    /**
     * Resets this matcher and then attempts to match the input sequence, starting at the beginning, against the pattern.
     */
    public Matcher reset() {
        this.from = 0;
        this.to = input.length();
        this.lastResult = null;
        this.findFromStart = true;
        return this;
    }

    /**
     * Returns the start index of the previous match.
     */
    public int start() {
        if (lastResult == null) {
            throw new IllegalStateException("No match available");
        }
        return lastResult.start();
    }

    /**
     * Returns the end index of the previous match.
     */
    public int end() {
        if (lastResult == null) {
            throw new IllegalStateException("No match available");
        }
        return lastResult.end();
    }

    /**
     * Returns the input subsequence matched by the previous match.
     */
    public String group() {
        if (lastResult == null) {
            throw new IllegalStateException("No match available");
        }
        return input.subSequence(lastResult.start(), lastResult.end()).toString();
    }

    /**
     * Returns the input subsequence captured by the given group during the previous match operation.
     */
    public String group(int group) {
        if (lastResult == null) {
            throw new IllegalStateException("No match available");
        }
        if (group < 0 || group >= lastResult.groups().size()) {
            throw new IndexOutOfBoundsException("Group index out of range: " + group);
        }
        return lastResult.groups().get(group);
    }

    /**
     * Returns the number of capturing groups in this matcher's pattern.
     */
    public int groupCount() {
        return lastResult != null ? lastResult.groups().size() : 0;
    }

    /**
     * Replaces every subsequence of the input sequence that matches the pattern with the given replacement string.
     */
    public String replaceAll(String replacement) {
        if (replacement == null) {
            throw new NullPointerException("Replacement cannot be null");
        }

        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        boolean found = false;

        while (find()) {
            found = true;
            sb.append(input.subSequence(lastEnd, start()));
            sb.append(replacement);
            lastEnd = end();
        }

        if (!found) {
            return input.toString();
        }

        sb.append(input.subSequence(lastEnd, input.length()));
        return sb.toString();
    }

    /**
     * Returns the MatchResult for the last match operation.
     */
    public MatchResult toMatchResult() {
        if (lastResult == null) {
            throw new IllegalStateException("No match available");
        }
        return new MatchResult() {
            @Override
            public int start() {
                return lastResult.start();
            }

            @Override
            public int start(int group) {
                if (group != 0) {
                    throw new IndexOutOfBoundsException("Group index out of range: " + group);
                }
                return lastResult.start();
            }

            @Override
            public int end() {
                return lastResult.end();
            }

            @Override
            public int end(int group) {
                if (group != 0) {
                    throw new IndexOutOfBoundsException("Group index out of range: " + group);
                }
                return lastResult.end();
            }

            @Override
            public String group() {
                return Matcher.this.group();
            }

            @Override
            public String group(int group) {
                return Matcher.this.group(group);
            }

            @Override
            public int groupCount() {
                return Matcher.this.groupCount();
            }

            @Override
            public String toString() {
                return Matcher.this.group();
            }
        };
    }

    /**
     * Gets the appropriate engine for the given hint.
     */
    private Engine getEngine(EngineHint hint) {
        return MetaEngine.getEngine(hint);
    }
}