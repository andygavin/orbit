package com.orbital.grammar;

import com.orbital.parse.Expr;
import com.orbital.parse.Parser;
import com.orbital.parse.PatternSyntaxException;
import com.orbital.prog.CompileResult;
import com.orbital.prog.Prog;
import com.orbital.prog.Metadata;
import com.orbital.util.EngineHint;
import com.orbital.prefilter.Prefilter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Grammar system inspired by Raku's grammar system.
 */
public final class Grammar {

    private final Map<String, Rule> rules;
    private final Expr rootRule;

    private Grammar(Map<String, Rule> rules, Expr rootRule) {
        this.rules = rules;
        this.rootRule = rootRule;
    }

    /**
     * Compiles a grammar class annotated with @Grammar.
     */
    public static Grammar compile(Class<?> grammarClass) {
        if (grammarClass == null) {
            throw new NullPointerException("Grammar class cannot be null");
        }

        Map<String, Rule> rules = new HashMap<>();
        Expr rootRule = null;

        for (Method method : grammarClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Rule.class)) {
                Rule annotation = method.getAnnotation(Rule.class);
                String ruleName = annotation.name().isEmpty() ? method.getName() : annotation.name();

                // Parse the rule expression
                try {
                    Expr expr = Parser.parse(annotation.value());
                    Rule rule = new Rule(ruleName, expr, method);
                    rules.put(ruleName, rule);

                    // Check if this is the root rule
                    if (annotation.isRoot()) {
                        if (rootRule != null) {
                            throw new GrammarException("Multiple root rules defined: " +
                                rootRule.ruleName() + " and " + ruleName);
                        }
                        rootRule = expr;
                    }
                } catch (PatternSyntaxException e) {
                    throw new GrammarException("Invalid syntax in rule '" + ruleName + "': " + e.getMessage(), e);
                }
            }
        }

        if (rootRule == null) {
            throw new GrammarException("No root rule defined in grammar class " + grammarClass.getName());
        }

        return new Grammar(rules, rootRule);
    }

    /**
     * Parses input using the grammar.
     */
    public ParseTree parse(String input) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }

        // For now, return a simple parse tree - in a full implementation this would:
        // 1. Create a parsing context
        // 2. Execute the grammar rules
        // 3. Build a proper parse tree

        return new ParseTree(rootRule, input);
    }

    /**
     * Transforms input using the grammar and provided actions.
     */
    public String transform(String input, Actions actions) {
        if (input == null) {
            throw new NullPointerException("Input cannot be null");
        }
        if (actions == null) {
            throw new NullPointerException("Actions cannot be null");
        }

        // For now, return the input unchanged
        // In a full implementation, this would:
        // 1. Parse the input
        // 2. Walk the parse tree
        // 3. Apply actions to transform the output

        return input;
    }

    /**
     * Rule definition.
     */
    public static class Rule {
        private final String ruleName;
        private final Expr expression;
        private final Method actionMethod;

        public Rule(String ruleName, Expr expression, Method actionMethod) {
            this.ruleName = ruleName;
            this.expression = expression;
            this.actionMethod = actionMethod;
        }

        public String ruleName() {
            return ruleName;
        }

        public Expr expression() {
            return expression;
        }

        public Method actionMethod() {
            return actionMethod;
        }
    }

    /**
     * Actions interface for grammar callbacks.
     */
    public interface Actions {
        // User implements per-rule callback methods
    }

    /**
     * Parse tree result.
     */
    public static class ParseTree {
        private final Expr matchedRule;
        private final String input;

        public ParseTree(Expr matchedRule, String input) {
            this.matchedRule = matchedRule;
            this.input = input;
        }

        public Expr matchedRule() {
            return matchedRule;
        }

        public String input() {
            return input;
        }
    }

    /**
     * Grammar compilation exception.
     */
    public static class GrammarException extends RuntimeException {
        public GrammarException(String message) {
            super(message);
        }

        public GrammarException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Rule annotation.
     */
    public @interface Rule {
        String name() default "";
        String value();
        boolean isRoot() default false;
    }
}