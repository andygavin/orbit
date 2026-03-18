package com.orbital.api;

import com.orbital.parse.Expr;
import com.orbital.parse.Parser;
import com.orbital.parse.PatternSyntaxException;
import com.orbital.prog.CompileResult;
import com.orbital.prog.Prog;
import com.orbital.prog.Metadata;
import com.orbital.util.EngineHint;
import com.orbital.util.PatternFlag;
import com.orbital.engine.Engine;
import com.orbital.engine.MetaEngine;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pattern class - drop-in compatible with java.util.regex.Pattern.
 */
public final class Pattern implements Serializable {

    private static final int DEFAULT_CACHE_SIZE = 512;
    private static final ConcurrentMap<CacheKey, CompileResult> cache =
        new ConcurrentHashMap<>(DEFAULT_CACHE_SIZE);

    // Pattern configuration
    private final String pattern;
    private final EnumSet<PatternFlag> flags;
    private final CompileResult compileResult;

    private Pattern(String pattern, EnumSet<PatternFlag> flags, CompileResult compileResult) {
        this.pattern = pattern;
        this.flags = flags;
        this.compileResult = compileResult;
    }

    /**
     * Compiles the given regular expression.
     */
    public static Pattern compile(String regex) {
        return compile(regex, PatternFlag.values());
    }

    /**
     * Compiles the given regular expression with the specified flags.
     */
    public static Pattern compile(String regex, PatternFlag... flags) {
        if (regex == null) {
            throw new NullPointerException("Regex cannot be null");
        }
        if (flags == null) {
            throw new NullPointerException("Flags cannot be null");
        }

        EnumSet<PatternFlag> flagSet = flags.length == 0
            ? EnumSet.noneOf(PatternFlag.class)
            : EnumSet.copyOf(List.of(flags));

        CacheKey key = new CacheKey(regex, flagSet);
        CompileResult result = cache.get(key);

        if (result == null) {
            try {
                Expr expr = Parser.parse(regex, flags);
                result = compileExpression(expr, flagSet);
                cache.put(key, result);
            } catch (PatternSyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        return new Pattern(regex, flagSet, result);
    }

    /**
     * Returns a matcher that will match the given input against this pattern.
     */
    public Matcher matcher(CharSequence input) {
        return new Matcher(this, input);
    }

    /**
     * Returns whether this pattern is one-pass safe.
     */
    public boolean isOnePassSafe() {
        return compileResult.metadata().hint() == EngineHint.ONE_PASS_SAFE;
    }

    /**
     * Returns the engine hint for this pattern.
     */
    public EngineHint engineHint() {
        return compileResult.metadata().hint();
    }

    /**
     * Returns the original pattern string.
     */
    public String pattern() {
        return pattern;
    }

    /**
     * Returns the flags used to compile this pattern.
     */
    public PatternFlag[] flags() {
        return flags.toArray(new PatternFlag[0]);
    }

    /**
     * Returns the compiled program.
     */
    Prog prog() {
        return compileResult.prog();
    }

    /**
     * Returns the metadata about this pattern.
     */
    Metadata metadata() {
        return compileResult.metadata();
    }

    /**
     * Cache key for compiled patterns.
     */
    private static final class CacheKey {
        private final String pattern;
        private final EnumSet<PatternFlag> flags;
        private final int hashCode;

        CacheKey(String pattern, EnumSet<PatternFlag> flags) {
            this.pattern = pattern;
            this.flags = flags;
            this.hashCode = computeHashCode();
        }

        private int computeHashCode() {
            int result = pattern.hashCode();
            for (PatternFlag flag : flags) {
                result = 31 * result + flag.hashCode();
            }
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CacheKey)) return false;
            CacheKey other = (CacheKey) obj;
            return pattern.equals(other.pattern) && flags.equals(other.flags);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    /**
     * Compiles an expression into a CompileResult.
     */
    private static CompileResult compileExpression(Expr expr, EnumSet<PatternFlag> flags) {
        // Run analysis pipeline
        com.orbital.hir.HirNode hir = com.orbital.hir.AnalysisVisitor.analyze(expr);

        // Build the Prog
        Prog prog = buildProg(hir);

        // Create metadata
        Metadata metadata = new Metadata(
            hir.getHint(),
            hir.getPrefilter(),
            // Group count - for now we assume 0
            0,
            // Max output length
            0,
            // Weighted
            false,
            // Is transducer
            false
        );

        return new CompileResult(prog, hir.getPrefilter(), metadata);
    }

    /**
     * Builds the Prog from the HIR.
     */
    private static Prog buildProg(com.orbital.hir.HirNode hir) {
        // Simple implementation - in a full version this would:
        // 1. Convert HIR to Thompson NFA
        // 2. Convert NFA to Instr[] array
        // 3. Return Prog with start/accept PCs

        // For now, return minimal valid Prog
        return new Prog(new com.orbital.prog.Instr[0], new Metadata(hir.getHint(), hir.getPrefilter(), 0, 0, false, false), 0, 0);
    }

    // Static methods from java.util.regex.Pattern
    public static boolean matches(String regex, CharSequence input) {
        Pattern pattern = compile(regex);
        return pattern.matcher(input).matches();
    }

    public static String quote(String s) {
        if (s == null) {
            throw new NullPointerException("String cannot be null");
        }
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isMetacharacter(c)) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String[] split(String regex, CharSequence input) {
        return split(regex, input, 0);
    }

    public static String[] split(String regex, CharSequence input, int limit) {
        Pattern pattern = compile(regex);
        Matcher matcher = pattern.matcher(input);
        List<String> list = new ArrayList<>();
        int lastIndex = 0;

        while (limit == 0 || list.size() + 1 < limit) {
            if (!matcher.find()) {
                break;
            }
            list.add(input.subSequence(lastIndex, matcher.start()).toString());
            lastIndex = matcher.end();
        }

        list.add(input.subSequence(lastIndex, input.length()).toString());

        if (limit > 0 && list.size() > limit) {
            list = list.subList(0, limit);
        }

        return list.toArray(new String[0]);
    }

    private static boolean isMetacharacter(char c) {
        switch (c) {
            case '\':n        case '.':
            case '*':
            case '+':
            case '?':
            case '^':
            case '$':
            case '|':
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
            case '<':
            case '>':
            case '=':
            case '!':
                return true;
            default:
                return false;
        }
    }
}