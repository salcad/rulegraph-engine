package com.interopera.rulegraph.computation.dsl;

import java.math.BigDecimal;

/**
 * The abstract syntax tree of a formula. Deliberately tiny: a formula is only literals, bound
 * variables, the four arithmetic operators, parentheses, and unary minus. There are no function
 * calls, no I/O, no assignment, and no control flow — so the set of things a formula <em>can</em>
 * express is closed and auditable by inspection, not just by reading any one expression.
 *
 * <p>This closed shape is what lets the arithmetic live in configuration without widening the
 * trusted computing base: the evaluator that walks these nodes ({@link FormulaEvaluator}) is the
 * only thing that needs to be trusted, and it can only ever do pure decimal arithmetic over the
 * variables a calculator binds.
 */
public sealed interface Expr permits Expr.Num, Expr.Var, Expr.Neg, Expr.Bin {

    /** A numeric literal, parsed exactly as written (e.g. {@code 100}, {@code 0.0001}). */
    record Num(BigDecimal value) implements Expr {
    }

    /** A reference to a variable a calculator binds at evaluation time (e.g. {@code subject_mv}). */
    record Var(String name) implements Expr {
    }

    /** Unary negation. */
    record Neg(Expr operand) implements Expr {
    }

    /** A binary arithmetic operation; {@code op} is one of {@code + - * /}. */
    record Bin(char op, Expr left, Expr right) implements Expr {
    }
}
