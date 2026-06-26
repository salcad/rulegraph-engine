package com.interopera.rulegraph.computation.dsl;

import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.domain.FormulaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the bundled {@code formulas.yaml} loads, covers every {@link FormulaKey}, and — the point
 * of the whole exercise — that evaluating a figure's formula yields a value <em>byte-identical</em>
 * to the arithmetic the hand-written calculators used. Because only the location of the arithmetic
 * changed (config instead of inlined Java), reconciliation against both answer keys is unaffected;
 * these parity assertions prove that offline, without standing up Neo4j.
 */
class FormulaLibraryTest {

    private FormulaLibrary library;

    @BeforeEach
    void setUp() {
        library = new FormulaLibrary(new ClassPathResource("formulas.yaml"));
        library.load();
    }

    @Test
    void everyFormulaKeyHasARegisteredFormula() {
        for (FormulaKey key : FormulaKey.values()) {
            assertThat(library.hasFormula(key)).as("formula for %s", key).isTrue();
        }
    }

    @Test
    void percentFamiliesMatchLegacyPercentOf() {
        BigDecimal nav = new BigDecimal("100000000");
        for (BigDecimal mv : new BigDecimal[]{
                new BigDecimal("35000000"), new BigDecimal("12500000"), new BigDecimal("7333333")}) {
            BigDecimal legacy = Formatting.percentOf(mv, nav);
            Map<String, BigDecimal> vars = Map.of("subject_mv", mv, "nav", nav);
            for (FormulaKey key : new FormulaKey[]{
                    FormulaKey.ALLOCATION_PERCENT, FormulaKey.AGGREGATE_EXPOSURE_PERCENT,
                    FormulaKey.ISSUER_CONCENTRATION, FormulaKey.GROUP_CONCENTRATION,
                    FormulaKey.LIQUIDITY_RATIO}) {
                // isEqualTo on BigDecimal compares value AND scale -> byte-identical numericValue.
                assertThat(library.evaluate(key, vars))
                        .as("%s for mv=%s", key, mv).isEqualTo(legacy);
            }
        }
    }

    @Test
    void durationMatchesLegacyWeightedDivide() {
        BigDecimal weighted = new BigDecimal("383500000");
        BigDecimal nav = new BigDecimal("100000000");
        BigDecimal legacy = weighted.divide(nav, Formatting.DIV_SCALE, RoundingMode.HALF_UP);
        assertThat(library.evaluate(FormulaKey.PORTFOLIO_DURATION,
                Map.of("duration_weighted_sum", weighted, "nav", nav))).isEqualTo(legacy);
    }

    @Test
    void dv01MatchesLegacyOneBpMultiply() {
        BigDecimal weighted = new BigDecimal("383500000");
        BigDecimal legacy = weighted.multiply(new BigDecimal("0.0001"));
        assertThat(library.evaluate(FormulaKey.PORTFOLIO_DV01,
                Map.of("duration_weighted_sum", weighted))).isEqualTo(legacy);
    }

    @Test
    void sha256IsStableAndCoversTheExpressions() {
        String hash = library.sha256();
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");

        FormulaLibrary reloaded = new FormulaLibrary(new ClassPathResource("formulas.yaml"));
        reloaded.load();
        assertThat(reloaded.sha256()).isEqualTo(hash);

        assertThat(library.expressions())
                .containsEntry("ALLOCATION_PERCENT", "subject_mv / nav * 100")
                .containsEntry("PORTFOLIO_DV01", "duration_weighted_sum * 0.0001");
    }

    @Test
    void evaluatingAnUnboundVariableFailsRatherThanGuesses() {
        assertThatThrownBy(() -> library.evaluate(FormulaKey.ALLOCATION_PERCENT,
                Map.of("nav", new BigDecimal("100000000"))))
                .isInstanceOf(FormulaException.class);
    }
}
