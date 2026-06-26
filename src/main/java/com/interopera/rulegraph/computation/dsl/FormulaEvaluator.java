package com.interopera.rulegraph.computation.dsl;

import com.interopera.rulegraph.computation.Formatting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Evaluates a parsed {@link Expr} to an exact {@link BigDecimal} against a set of bound variables.
 *
 * <p><b>Deterministic by construction.</b> {@code + - *} are exact {@link BigDecimal} operations and
 * {@code /} divides at {@link Formatting#DIV_SCALE} with {@link RoundingMode#HALF_UP} — the same
 * scale and rounding the hand-written calculators used via {@link Formatting#percentOf}. So moving a
 * figure's arithmetic into a formula does not change a single digit of its value; re-running yields
 * byte-identical numbers (constraint 1).
 *
 * <p><b>No silent failure.</b> An unbound variable or a division by zero throws
 * {@link FormulaException}. The evaluator never invents a value to keep going, so a misconfigured
 * formula becomes a visible computation error rather than a wrong figure.
 */
public final class FormulaEvaluator {

    private FormulaEvaluator() {
    }

    public static BigDecimal eval(Expr expr, Map<String, BigDecimal> vars) {
        return switch (expr) {
            case Expr.Num n -> n.value();
            case Expr.Var v -> {
                BigDecimal bound = vars.get(v.name());
                if (bound == null) {
                    throw new FormulaException("unbound variable '" + v.name() + "'");
                }
                yield bound;
            }
            case Expr.Neg neg -> eval(neg.operand(), vars).negate();
            case Expr.Bin bin -> {
                BigDecimal l = eval(bin.left(), vars);
                BigDecimal r = eval(bin.right(), vars);
                yield switch (bin.op()) {
                    case '+' -> l.add(r);
                    case '-' -> l.subtract(r);
                    case '*' -> l.multiply(r);
                    case '/' -> {
                        if (r.signum() == 0) {
                            throw new FormulaException("division by zero");
                        }
                        yield l.divide(r, Formatting.DIV_SCALE, RoundingMode.HALF_UP);
                    }
                    default -> throw new FormulaException("unknown operator '" + bin.op() + "'");
                };
            }
        };
    }
}
