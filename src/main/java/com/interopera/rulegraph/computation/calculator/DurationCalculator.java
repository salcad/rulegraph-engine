package com.interopera.rulegraph.computation.calculator;

import com.interopera.rulegraph.computation.ComputationContext;
import com.interopera.rulegraph.computation.FigureCalculator;
import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.computation.ResolvedRule;
import com.interopera.rulegraph.computation.Statuses;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FormulaKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Market-value-weighted portfolio modified duration vs its band (Section 3.1). */
@Component
public class DurationCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.PORTFOLIO_DURATION;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Σ(mv × duration) / NAV
        BigDecimal weighted = ctx.portfolio().durationWeightedSum();
        BigDecimal duration = weighted.divide(ctx.nav(), Formatting.DIV_SCALE, RoundingMode.HALF_UP);
        BigDecimal display = duration.setScale(2, RoundingMode.HALF_UP);

        String limit = Formatting.yearsBound(rule.min()) + "–" + Formatting.yearsBound(rule.max()) + " yrs";
        String path = "(Position)-[:IN_ASSET_CLASS]->(AssetClass) || (RiskMetric:" + rule.code()
                + ")-[:HAS_THRESHOLD]->(Threshold)-[:DEFINED_BY]->(GuidelineChunk:"
                + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), display.toPlainString() + " yrs",
                Statuses.againstBand(duration, rule.min(), rule.max()),
                limit, "n/a", path, rule.citation(), display);
    }
}
