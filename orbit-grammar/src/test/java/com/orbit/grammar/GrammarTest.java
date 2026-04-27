package com.orbit.grammar;

import com.orbit.grammar.Grammar;
import com.orbit.parse.Expr;
import com.orbit.parse.Parser;
import com.orbit.parse.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.*;

class GrammarTest {

    @Test
    void testCompileSimpleGrammar() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertNotNull(grammar);
        assertEquals("root", grammar.getRules().get("root").ruleName());
        assertEquals("expr", grammar.getRules().get("expr").ruleName());
    }

    @Test
    void testCompileWithRootRule() throws Exception {
        Grammar grammar = Grammar.compile(RootGrammar.class);
        assertNotNull(grammar);
        assertNotNull(grammar.getRootRule());
        assertEquals("root", grammar.getRules().get("root").ruleName());
    }

    @Test
    void testCompileMultipleRootRules() throws Exception {
        assertThrows(Grammar.GrammarException.class, () -> {
            Grammar.compile(MultipleRootGrammar.class);
        });
    }

    @Test
    void testCompileNoRootRule() throws Exception {
        assertThrows(Grammar.GrammarException.class, () -> {
            Grammar.compile(NoRootGrammar.class);
        });
    }

    @Test
    void testCompileInvalidSyntax() throws Exception {
        assertThrows(Grammar.GrammarException.class, () -> {
            Grammar.compile(InvalidSyntaxGrammar.class);
        });
    }

    @Test
    void testParse() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertNotNull(grammar.parse("test input"));
    }

    @Test
    void testTransform() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertNotNull(grammar.transform("test input", new SimpleActions()));
    }

    @Test
    void testNullInputToParse() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertThrows(NullPointerException.class, () -> grammar.parse(null));
    }

    @Test
    void testNullInputToTransform() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertThrows(NullPointerException.class, () -> grammar.transform(null, new SimpleActions()));
    }

    @Test
    void testNullActions() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        assertThrows(NullPointerException.class, () -> grammar.transform("test", null));
    }

    @Test
    void testNullGrammarClass() throws Exception {
        assertThrows(NullPointerException.class, () -> Grammar.compile(null));
    }

    @Test
    void testRuleAnnotation() throws Exception {
        Grammar grammar = Grammar.compile(NamedRulesGrammar.class);
        assertNotNull(grammar.getRules().get("custom_name"));
    }

    @Test
    void testActionMethod() throws Exception {
        Grammar grammar = Grammar.compile(ActionGrammar.class);
        assertNotNull(grammar.getRules().get("expr").actionMethod());
    }

    @Test
    void testParseTree() throws Exception {
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        Grammar.ParseTree tree = grammar.parse("test");
        assertNotNull(tree);
        assertNotNull(tree.matchedRule());
        assertEquals("test", tree.input());
    }

    @Test
    void testParseTreeNullRule() throws Exception {
        Grammar.ParseTree tree = new Grammar.ParseTree(null, "test");
        assertNull(tree.matchedRule());
        assertEquals("test", tree.input());
    }

    @Test
    void testParseTreeNullInput() throws Exception {
        // Create a tree with a non-null expression obtained from compiling a grammar
        Grammar grammar = Grammar.compile(SimpleGrammar.class);
        Grammar.ParseTree tree = new Grammar.ParseTree(grammar.getRootRule(), null);
        assertNotNull(tree.matchedRule());
        assertNull(tree.input());
    }

    @Test
    void testGrammarException() throws Exception {
        Grammar.GrammarException ex = new Grammar.GrammarException("test message");
        assertEquals("test message", ex.getMessage());

        Grammar.GrammarException ex2 = new Grammar.GrammarException("test", new Exception("cause"));
        assertEquals("test", ex2.getMessage());
        assertNotNull(ex2.getCause());
    }

    @Test
    void testRuleDefinitionAttributes() throws Exception {
        Grammar.RuleDefinition rule = new Grammar.RuleDefinition("test", null, null);
        assertEquals("test", rule.ruleName());
        assertNull(rule.expression());
        assertNull(rule.actionMethod());
    }

    @Test
    void testGrammarInterface() throws Exception {
        assertNotNull(Grammar.Actions.class);
    }

    // Test grammar classes
    @Grammar.Rule(name = "root", value = "expr", isRoot = true)
    static class SimpleGrammar {
        @Grammar.Rule(name = "expr", value = "[a-z]+")
        void expr() {}
    }

    @Grammar.Rule(name = "root", value = "expr", isRoot = true)
    @Grammar.Rule(name = "expr", value = "[0-9]+")
    static class RootGrammar {}

    @Grammar.Rule(name = "root", value = "expr", isRoot = true)
    @Grammar.Rule(name = "expr", value = "expr", isRoot = true)
    static class MultipleRootGrammar {}

    @Grammar.Rule(name = "expr", value = "[0-9]+")
    static class NoRootGrammar {}

    static class InvalidSyntaxGrammar {
        @Grammar.Rule(name = "expr", value = "[") // Invalid syntax
        void expr() {}
    }

    @Grammar.Rule(name = "custom_name", value = "expr", isRoot = true)
    static class NamedRulesGrammar {
        @Grammar.Rule(name = "expr", value = "[a-z]+")
        void expr() {}
    }

    @Grammar.Rule(name = "expr", value = "[a-z]+", isRoot = true)
    static class ActionGrammar {
        void expr() {} // Action method
    }

    static class SimpleActions implements Grammar.Actions {
        // Implementation would go here
    }
}