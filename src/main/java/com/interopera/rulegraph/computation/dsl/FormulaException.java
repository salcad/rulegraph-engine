package com.interopera.rulegraph.computation.dsl;

/**
 * Raised when a formula cannot be parsed or evaluated: an illegal character, malformed syntax, an
 * unbound variable, or a division by zero. The evaluator never returns a fabricated or partial
 * number on error — it fails loudly, so a bad formula surfaces as a computation error rather than a
 * silently wrong figure.
 */
public class FormulaException extends RuntimeException {
    public FormulaException(String message) {
        super(message);
    }
}
