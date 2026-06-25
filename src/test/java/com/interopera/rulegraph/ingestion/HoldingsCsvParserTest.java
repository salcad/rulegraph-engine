package com.interopera.rulegraph.ingestion;

import com.interopera.rulegraph.config.RuleGraphProperties;
import com.interopera.rulegraph.domain.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic ingestion test — no Spring context, no Neo4j. Verifies the holdings parse is exact
 * and that NAV sums to the expected SGD 100,000,000 (the anchor the whole reconciliation rests on).
 */
class HoldingsCsvParserTest {

    private static final String SAMPLE_DOCS = System.getProperty(
            "rulegraph.sampleDocs",
            "/home/salcad/interopera-homework/rulegraph-docs/sample_docs/sample_docs");

    private HoldingsCsvParser parser() {
        return new HoldingsCsvParser(new RuleGraphProperties(
                SAMPLE_DOCS, "sample_fund_guidelines.pdf", "sample_holdings.csv"));
    }

    @Test
    void parsesAllThirteenPositions() {
        List<Position> positions = parser().parse();
        assertThat(positions).hasSize(13);
        assertThat(positions.getFirst().instrumentId()).isEqualTo("SGS-01");
    }

    @Test
    void navSumsToOneHundredMillion() {
        BigDecimal nav = parser().parse().stream()
                .map(Position::marketValueSgd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(nav).isEqualByComparingTo(new BigDecimal("100000000"));
    }

    @Test
    void detectsFallenAngel() {
        Position marinaBay = parser().parse().stream()
                .filter(p -> p.instrumentId().equals("COR-05"))
                .findFirst().orElseThrow();
        assertThat(marinaBay.isFallenAngel()).isTrue();
        assertThat(marinaBay.downgradedFrom()).isEqualTo("BBB-");
    }
}
