package com.interopera.rulegraph.computation.dsl;

import java.math.BigDecimal;

/**
 * A recursive-descent parser for the restricted formula grammar:
 *
 * <pre>
 *   expr   := term (('+' | '-') term)*
 *   term   := factor (('*' | '/') factor)*
 *   factor := '-' factor | '(' expr ')' | number | identifier
 * </pre>
 *
 * <p>The lexer accepts only digits and a single dot (numbers), lower-case identifier characters
 * ({@code [a-z_][a-z0-9_]*}), the operators {@code + - * /}, and parentheses. Whitespace is ignored.
 * Any other character is rejected immediately. There is no escape hatch — no strings, no function
 * names, no operators beyond the four arithmetic ones — so a formula file cannot smuggle in anything
 * the {@link FormulaEvaluator} could execute beyond pure arithmetic. This is a one-shot parser;
 * construct one per formula.
 */
public final class FormulaParser {

    private final String src;
    private int pos;

    private FormulaParser(String src) {
        this.src = src;
    }

    /** Parse a formula string into an {@link Expr}, or throw {@link FormulaException}. */
    public static Expr parse(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new FormulaException("empty formula");
        }
        FormulaParser p = new FormulaParser(formula);
        Expr e = p.expr();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new FormulaException("unexpected '" + p.src.charAt(p.pos) + "' at position " + p.pos
                    + " in: " + formula);
        }
        return e;
    }

    private Expr expr() {
        Expr left = term();
        while (true) {
            char op = peekOp();
            if (op == '+' || op == '-') {
                pos++;
                left = new Expr.Bin(op, left, term());
            } else {
                return left;
            }
        }
    }

    private Expr term() {
        Expr left = factor();
        while (true) {
            char op = peekOp();
            if (op == '*' || op == '/') {
                pos++;
                left = new Expr.Bin(op, left, factor());
            } else {
                return left;
            }
        }
    }

    private Expr factor() {
        skipWs();
        if (pos >= src.length()) {
            throw new FormulaException("unexpected end of formula: " + src);
        }
        char c = src.charAt(pos);
        if (c == '-') {
            pos++;
            return new Expr.Neg(factor());
        }
        if (c == '(') {
            pos++;
            Expr inner = expr();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ')') {
                throw new FormulaException("missing ')' in: " + src);
            }
            pos++;
            return inner;
        }
        if (isDigit(c) || c == '.') {
            return number();
        }
        if (isIdentStart(c)) {
            return identifier();
        }
        throw new FormulaException("illegal character '" + c + "' at position " + pos + " in: " + src);
    }

    private Expr number() {
        int start = pos;
        boolean dotSeen = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (isDigit(c)) {
                pos++;
            } else if (c == '.' && !dotSeen) {
                dotSeen = true;
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (text.equals(".")) {
            throw new FormulaException("malformed number '.' in: " + src);
        }
        return new Expr.Num(new BigDecimal(text));
    }

    private Expr identifier() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return new Expr.Var(src.substring(start, pos));
    }

    /** Returns the next non-whitespace operator-ish character without consuming it, or '\0'. */
    private char peekOp() {
        skipWs();
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }
}
