package com.interopera.rulegraph.computation.dsl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grammar, arithmetic semantics, and — most importantly — the safety properties of the restricted
 * formula language: it evaluates only pure decimal arithmetic over bound variables, and it fails
 * loudly on anything malformed rather than inventing a value.
 */
class FormulaParserTest {

    private static BigDecimal eval(String formula, Map<String, BigDecimal> vars) {
        return FormulaEvaluator.eval(FormulaParser.parse(formula), vars);
    }

    private static BigDecimal eval(String formula) {
        return eval(formula, Map.of());
    }

    @Test
    void arithmeticAndPrecedence() {
        assertThat(eval("2 + 3 * 4")).isEqualByComparingTo("14");
        assertThat(eval("(2 + 3) * 4")).isEqualByComparingTo("20");
        assertThat(eval("10 - 2 - 3")).isEqualByComparingTo("5");   // left-associative
        assertThat(eval("-5 + 2")).isEqualByComparingTo("-3");
        assertThat(eval("-(2 + 3)")).isEqualByComparingTo("-5");
    }

    @Test
    void boundVariablesResolve() {
        Map<String, BigDecimal> vars = Map.of(
                "subject_mv", new BigDecimal("35000000"),
                "nav", new BigDecimal("100000000"));
        assertThat(eval("subject_mv / nav * 100", vars)).isEqualByComparingTo("35");
    }

    @Test
    void divisionUsesTheSameScaleAndRoundingAsTheCalculators() {
        // 1 / 3 at DIV_SCALE (12) HALF_UP -> 0.333333333333
        BigDecimal r = eval("1 / 3", Map.of());
        assertThat(r.scale()).isEqualTo(12);
        assertThat(r).isEqualByComparingTo("0.333333333333");
    }

    @Test
    void evaluationIsDeterministic() {
        Map<String, BigDecimal> vars = Map.of("a", new BigDecimal("7"), "b", new BigDecimal("13"));
        assertThat(eval("a / b * 100", vars)).isEqualTo(eval("a / b * 100", vars));
    }

    @Test
    void unboundVariableFailsLoudly() {
        assertThatThrownBy(() -> eval("subject_mv / nav", Map.of("nav", BigDecimal.ONE)))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("unbound variable 'subject_mv'");
    }

    @Test
    void divisionByZeroFailsLoudly() {
        assertThatThrownBy(() -> eval("1 / 0"))
                .isInstanceOf(FormulaException.class)
                .hasMessageContaining("division by zero");
    }

    @Test
    void illegalCharactersAreRejected() {
        // No functions, no power/modulo, no comparison — the language is closed to arithmetic only.
        assertThatThrownBy(() -> FormulaParser.parse("max(a, b)")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("a ^ b")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("a % b")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("a & b")).isInstanceOf(FormulaException.class);
    }

    @Test
    void malformedSyntaxIsRejected() {
        assertThatThrownBy(() -> FormulaParser.parse("")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("   ")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("(1 + 2")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("1 + 2)")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("1 2")).isInstanceOf(FormulaException.class);
        assertThatThrownBy(() -> FormulaParser.parse("1 +")).isInstanceOf(FormulaException.class);
    }
}
