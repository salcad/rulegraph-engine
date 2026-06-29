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
import java.math.RoundingMode;
import java.util.List;

/** Market-value-weighted portfolio modified duration vs its band (Section 3.1). */
@Component
public class DurationCalculator implements FigureCalculator {

    private final FormulaLibrary formulas;

    public DurationCalculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.PORTFOLIO_DURATION;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal supplies Σ(mv × duration); the registry formula divides by NAV.
        BigDecimal weighted = ctx.portfolio().durationWeightedSum();
        List<FigureInput> inputs = List.of(
                new FigureInput("duration_weighted_sum", weighted,
                        "Σ(market value × modified duration) over all positions",
                        ctx.portfolio().durationWeightedSumCypher()),
                new FigureInput("nav", ctx.nav(), "Σ market value of all positions (NAV)",
                        ctx.portfolio().navCypher()));
        BigDecimal duration = formulas.evaluate(key(), FigureInput.vars(inputs));
        BigDecimal display = duration.setScale(2, RoundingMode.HALF_UP);

        String limit = Formatting.yearsBound(rule.min()) + "–" + Formatting.yearsBound(rule.max()) + " yrs";
        String path = "(Position)-[:IN_ASSET_CLASS]->(AssetClass) || (RiskMetric:" + rule.code()
                + ")-[:HAS_THRESHOLD]->(Threshold)-[:DEFINED_BY]->(GuidelineChunk:"
                + rule.citation().chunkId() + ")";
        String cypher = TraceCypher.trace()
                .match().node("Position").rel("IN_ASSET_CLASS").node("AssetClass").end()
                .match().node("RiskMetric", rule.code())
                        .rel("HAS_THRESHOLD").node("Threshold", rule.code())
                        .rel("DEFINED_BY").node("GuidelineChunk", rule.citation().chunkId())
                .end().build();

        return new FigureResult(rule.code(), display.toPlainString() + " yrs",
                Statuses.againstBand(duration, rule.min(), rule.max()),
                limit, "n/a", formulas.expression(key()), inputs, path, cypher, rule.citation(), display);
    }
}
