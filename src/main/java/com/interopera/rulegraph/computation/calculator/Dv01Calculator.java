package com.interopera.rulegraph.computation.calculator;

import com.interopera.rulegraph.computation.ComputationContext;
import com.interopera.rulegraph.computation.FigureCalculator;
import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.computation.ResolvedRule;
import com.interopera.rulegraph.computation.Statuses;
import com.interopera.rulegraph.computation.TraceCypher;
import com.interopera.rulegraph.computation.dsl.FormulaLibrary;
import com.interopera.rulegraph.domain.FigureInput;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FormulaKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Portfolio DV01 = Σ(mv × duration) × 1bp, vs the DV01 cap (Section 3.1). */
@Component
public class Dv01Calculator implements FigureCalculator {

    private final FormulaLibrary formulas;

    public Dv01Calculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.PORTFOLIO_DV01;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal supplies Σ(mv × duration); the registry formula applies the 1bp factor.
        BigDecimal weighted = ctx.portfolio().durationWeightedSum();
        List<FigureInput> inputs = List.of(
                new FigureInput("duration_weighted_sum", weighted,
                        "Σ(market value × modified duration) over all positions",
                        ctx.portfolio().durationWeightedSumCypher()));
        BigDecimal dv01 = formulas.evaluate(key(), FigureInput.vars(inputs));

        String value = "SGD " + Formatting.grouped(dv01) + " / bp";
        String limit = "max " + Formatting.grouped(rule.max());
        String util = Formatting.utilization(dv01, rule.max(), ctx.firm());
        String path = "(Position)-[:IN_ASSET_CLASS]->(AssetClass) || (RiskMetric:" + rule.code()
                + ")-[:HAS_THRESHOLD]->(Threshold)-[:DEFINED_BY]->(GuidelineChunk:"
                + rule.citation().chunkId() + ")";
        String cypher = TraceCypher.trace()
                .match().node("Position").rel("IN_ASSET_CLASS").node("AssetClass").end()
                .match().node("RiskMetric", rule.code())
                        .rel("HAS_THRESHOLD").node("Threshold", rule.code())
                        .rel("DEFINED_BY").node("GuidelineChunk", rule.citation().chunkId())
                .end().build();

        return new FigureResult(rule.code(), value,
                Statuses.againstMax(dv01, rule.max()), limit, util,
                formulas.expression(key()), inputs, path, cypher, rule.citation(), dv01);
    }
}
