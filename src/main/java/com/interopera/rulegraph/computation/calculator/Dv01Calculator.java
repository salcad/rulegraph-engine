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

/** Portfolio DV01 = Σ(mv × duration) × 1bp, vs the DV01 cap (Section 3.1). */
@Component
public class Dv01Calculator implements FigureCalculator {

    private static final BigDecimal ONE_BP = new BigDecimal("0.0001");

    @Override
    public FormulaKey key() {
        return FormulaKey.PORTFOLIO_DV01;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        BigDecimal weighted = ctx.portfolio().durationWeightedSum();
        BigDecimal dv01 = weighted.multiply(ONE_BP);

        String value = "SGD " + Formatting.grouped(dv01) + " / bp";
        String limit = "max " + Formatting.grouped(rule.max());
        String util = Formatting.utilization(dv01, rule.max(), ctx.firm());
        String path = "(Position)-[:IN_ASSET_CLASS]->(AssetClass) || (RiskMetric:" + rule.code()
                + ")-[:HAS_THRESHOLD]->(Threshold)-[:DEFINED_BY]->(GuidelineChunk:"
                + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), value,
                Statuses.againstMax(dv01, rule.max()), limit, util, path, rule.citation(), dv01);
    }
}
