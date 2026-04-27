package com.orbit.grammar;

import com.orbit.parse.Expr;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import com.orbit.prog.CompileResult;
import com.orbit.prog.Prog;
import com.orbit.prog.Metadata;
import com.orbit.util.EngineHint;
import com.orbit.prefilter.Prefilter;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Grammar system inspired by Raku's grammar system.
 */
public final class Grammar {

    private final Map<String, RuleDefinition> rules;
    private final Expr rootRule;

    private Grammar(Map<String, RuleDefinition> rules, Expr rootRule) {
        this.rules = rules;
        this.rootRule = rootRule;
    }

    /**
     * Returns an unmodifiable view of the compiled rule definitions, keyed by rule name.
     *
     * @return the rule map, never null
     */
    public Map<String, RuleDefinition> getRules() {
        return Collections.unmodifiableMap(rules);
    }

    /**
     * Returns the root rule expression, or null if no root rule was declared.
     *
     * @return the root expression
     */
    public Expr getRootRule() {
        return rootRule;
    }

    /**
     * Compiles a grammar class annotated with @Grammar.
     */
    public static Grammar compile(Class<?> grammarClass) {
        if (grammarClass == null) {
            throw new NullPointerException("Grammar class cannot be null");
        }

        Map<String, RuleDefinition> rules = new HashMap<>();
        Expr rootRule = null;
        String rootRuleName = null;

        // Process @Rule annotations on the class itself
        for (Rule annotation : grammarClass.getAnnotationsByType(Rule.class)) {
            String ruleName = annotation.name().isEmpty() ? grammarClass.getSimpleName() : annotation.name();
            try {
                Expr expr = Parser.parse(annotation.value());
                rules.put(ruleName, new RuleDefinition(ruleName, expr, null));
                if (annotation.isRoot()) {
                    if (rootRule != null) {
                        throw new GrammarException("Multiple root rules defined: " +
                            rootRuleName + " and " + ruleName);
                    }
                    rootRule = expr;
                    rootRuleName = ruleName;
                }
            } catch (PatternSyntaxException e) {
                throw new GrammarException("Invalid syntax in rule '" + ruleName + "': " + e.getMessage(), e);
            }
        }

        // Process @Rule annotations on methods
        for (Method method : grammarClass.getDeclaredMethods()) {
            for (Rule annotation : method.getAnnotationsByType(Rule.class)) {
                String ruleName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                try {
                    Expr expr = Parser.parse(annotation.value());
                    RuleDefinition rule = new RuleDefinition(ruleName, expr, method);
                    rules.put(ruleName, rule);
                    if (annotation.isRoot()) {
                        if (rootRule != null) {
                            throw new GrammarException("Multiple root rules defined: " +
                                rootRuleName + " and " + ruleName);
                        }
                        rootRule = expr;
                        rootRuleName = ruleName;
                    }
                } catch (PatternSyntaxException e) {
                    throw new GrammarException("Invalid syntax in rule '" + ruleName + "': " + e.getMessage(), e);
                }
            }
        }

        // Link unannotated methods as action methods when their name matches a rule name
        for (Method method : grammarClass.getDeclaredMethods()) {
            if (method.getAnnotationsByType(Rule.class).length == 0) {
                RuleDefinition existing = rules.get(method.getName());
                if (existing != null && existing.actionMethod() == null) {
                    rules.put(method.getName(),
                        new RuleDefinition(existing.ruleName(), existing.expression(), method));
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
     * Compiled rule definition, holding the rule name, parsed expression, and action method.
     */
    public static class RuleDefinition {
        private final String ruleName;
        private final Expr expression;
        private final Method actionMethod;

        public RuleDefinition(String ruleName, Expr expression, Method actionMethod) {
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
     * Container annotation enabling multiple {@link Rule} annotations on a single element.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Rules {
        Rule[] value();
    }

    /**
     * Rule annotation; may be applied multiple times to define several rules on one element.
     */
    @Repeatable(Rules.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Rule {
        /** The rule name; defaults to the annotated method name when empty. */
        String name() default "";
        /** The pattern expression for this rule; must not be blank. */
        String value();
        /** Whether this rule is the root (entry) rule of the grammar. */
        boolean isRoot() default false;
    }
}