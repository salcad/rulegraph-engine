package com.interopera.rulegraph.graph;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the canonical codes the data-driven mapping must produce for the sample fund's holdings
 * labels — the reconciliation against both answer keys depends on these exact codes.
 */
class AssetClassCodesTest {

    private final AssetClassCodes codes =
            new AssetClassCodes(new RuleGraphProperties(null, null, null, null, null));

    @Test
    void mapsSampleLabelsToCanonicalCodes() {
        assertThat(codes.toCode("Singapore Government Securities"))
                .isEqualTo("singapore_government_securities");
        assertThat(codes.toCode("MAS Bills")).isEqualTo("mas_bills");
        assertThat(codes.toCode("Investment Grade Corporate Bonds"))
                .isEqualTo("investment_grade_corporate_bonds");
        assertThat(codes.toCode("High Yield Bonds")).isEqualTo("high_yield");
        assertThat(codes.toCode("Foreign Currency Bonds")).isEqualTo("foreign_currency_bonds");
        assertThat(codes.toCode("Structured Credit")).isEqualTo("structured_credit");
        assertThat(codes.toCode("Cash & Cash Equivalents")).isEqualTo("cash");
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(codes.toCode("HIGH YIELD BONDS")).isEqualTo("high_yield");
    }

    @Test
    void unmatchedLabelFallsBackToSlug() {
        // A label not in the mapping still yields a usable, deterministic code (no rebuild needed).
        assertThat(codes.toCode("Emerging Market Debt")).isEqualTo("emerging_market_debt");
    }
}
