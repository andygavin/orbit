package com.orbital.parse;

import com.orbital.parse.Expr;
import com.orbital.parse.CharClass;
import com.orbital.parse.CharRange;
import com.orbital.parse.Concat;
import com.orbital.parse.Epsilon;
import com.orbital.parse.Group;
import com.orbital.parse.Literal;
import com.orbital.parse.Pair;
import com.orbital.parse.Quantifier;
import com.orbital.parse.Union;
import com.orbital.parse.Anchor;
import com.orbital.parse.Backref;
import com.orbital.parse.AnchorType;
import com.orbital.util.PatternFlag;
import com.orbital.util.PatternSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;

/**
 * LL(1) parser for regular expressions and transducers.
 */
public class Parser {

    public static Expr parse(String source, PatternFlag... flags) throws PatternSyntaxException {
        if (source == null) {
            throw new NullPointerException("Source cannot be null");
        }
        if (source.isEmpty()) {
            return new Epsilon();
        }

        Parser parser = new Parser(source);
        Expr result = parser.parseExpression();

        if (!parser.isAtEnd()) {
            throw new PatternSyntaxException("Unexpected character at end of input", source, parser.getCursor());
        }

        return result;
    }

    // Parser implementation
    private final String source;
    private int cursor;

    private Parser(String source) {
        this.source = source;
        this.cursor = 0;
    }

    private boolean isAtEnd() {
        return cursor >= source.length();
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(cursor);
    }

    private char advance() {
        return isAtEnd() ? '\0' : source.charAt(cursor++);
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(cursor) != expected) {
            return false;
        }
        cursor++;
        return true;
    }

    private void expect(char expected) throws PatternSyntaxException {
        if (!match(expected)) {
            throw new PatternSyntaxException("Expected '" + expected + "'", source, cursor);
        }
    }

    private Expr parseExpression() throws PatternSyntaxException {
        return parseAlternation();
    }

    private Expr parseAlternation() throws PatternSyntaxException {
        Expr left = parseConcatenation();

        while (match('|')) {
            Expr right = parseConcatenation();
            left = new Union(List.of(left, right));
        }

        return left;
    }

    private Expr parseConcatenation() throws PatternSyntaxException {
        List<Expr> parts = new ArrayList<>();
        parts.add(parseAtom());

        while (!isAtEnd() && !isAlternationNext()) {
            parts.add(parseAtom());
        }

        return parts.size() == 1 ? parts.get(0) : new Concat(parts);
    }

    private boolean isAlternationNext() {
        return peek() == '|';
    }

    private Expr parseAtom() throws PatternSyntaxException {
        char ch = advance();

        switch (ch) {
            case '(':
                return parseGroup();
            case '[':
                return parseCharClass();
            case '^':
                return new Anchor(AnchorType.START);
            case '$':
                return new Anchor(AnchorType.END);
            case '.':
                return new CharClass(false, List.of(new CharRange('\u0000', '\uffff')));
            case '*':
            case '+':
            case '?':
                throw new PatternSyntaxException("Quantifier without preceding atom", source, cursor - 1);
            case ')':
                throw new PatternSyntaxException("Unmatched closing parenthesis", source, cursor - 1);
            case ']':
                throw new PatternSyntaxException("Unmatched closing bracket", source, cursor - 1);
            case ':':
                throw new PatternSyntaxException("Unexpected colon", source, cursor - 1);
            default:
                return parseLiteral(ch);
        }
    }

    private Expr parseGroup() throws PatternSyntaxException {
        // Simple group for now - could extend to named groups
        Expr body = parseExpression();
        expect(')');
        return new Group(body, 0, null);
    }

    private Expr parseCharClass() throws PatternSyntaxException {
        boolean negated = match('^');
        List<CharRange> ranges = new ArrayList<>();

        while (!match(']')) {
            if (isAtEnd()) {
                throw new PatternSyntaxException("Unclosed character class", source, cursor);
            }

            char start = advance();
            if (match('-') && !isAtEnd()) {
                char end = advance();
                if (end == ']') {
                    // Treat as literal '-'
                    ranges.add(new CharRange('-', '-'));
                    break;
                }
                ranges.add(new CharRange(start, end));
            } else {
                ranges.add(new CharRange(start, start));
            }
        }

        return new CharClass(negated, ranges);
    }

    private Expr parseLiteral(char first) throws PatternSyntaxException {
        StringBuilder sb = new StringBuilder();
        sb.append(first);

        while (!isAtEnd() && isLiteralChar(peek())) {
            sb.append(advance());
        }

        return new Literal(sb.toString());
    }

    private boolean isLiteralChar(char ch) {
        return ch != '(' && ch != ')' && ch != '[' && ch != ']' && ch != '*' &&
               ch != '+' && ch != '?' && ch != '|' && ch != '^' && ch != '$' &&
               ch != '.' && ch != '{';
    }

    private int getCursor() {
        return cursor;
    }
}