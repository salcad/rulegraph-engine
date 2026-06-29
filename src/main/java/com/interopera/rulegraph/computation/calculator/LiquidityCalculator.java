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
import java.util.ArrayList;
import java.util.List;

/** Liquid-assets ratio (SGS + MAS Bills + Cash) vs the liquidity floor (Section 3.3). */
@Component
public class LiquidityCalculator implements FigureCalculator {

    private final FormulaLibrary formulas;

    public LiquidityCalculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.LIQUIDITY_RATIO;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal selects the liquid buckets; the registry formula does the arithmetic.
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(rule.contributors());
        List<FigureInput> inputs = List.of(
                new FigureInput("subject_mv", mv,
                        "Σ market value of liquid classes (" + String.join(" + ", rule.contributors()) + ")",
                        ctx.portfolio().marketValueInAssetClassesCypher(rule.contributors())),
                new FigureInput("nav", ctx.nav(), "Σ market value of all positions (NAV)",
                        ctx.portfolio().navCypher()));
        BigDecimal pct = formulas.evaluate(key(), FigureInput.vars(inputs));

        String util = Formatting.utilization(pct, rule.min(), ctx.firm());
        String limit = "min " + Formatting.percentBound(rule.min()) + "%";

        List<String> hops = new ArrayList<>();
        TraceCypher trace = TraceCypher.trace();
        for (String c : rule.contributors()) {
            // The value sums the positions in each contributing class, so the trace starts at Position
            // (as every other figure's path does), not at the asset class.
            hops.add("(Position)-[:IN_ASSET_CLASS]->(AssetClass:" + c
                    + ")-[:CONTRIBUTES_TO]->(LiquidityFloor:" + rule.code() + ")");
            trace.match().node("Position")
                    .rel("IN_ASSET_CLASS").node("AssetClass", c)
                    .rel("CONTRIBUTES_TO").node("LiquidityFloor", rule.code()).end();
        }
        String path = String.join(" , ", hops)
                + " -[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";
        String cypher = trace.match().node("LiquidityFloor", rule.code())
                .rel("DEFINED_BY").node("GuidelineChunk", rule.citation().chunkId())
                .end().build();

        return new FigureResult(rule.code(), Formatting.percent1dp(pct),
                Statuses.againstMin(pct, rule.min()), limit, util,
                formulas.expression(key()), inputs, path, cypher, rule.citation(), pct);
    }
}
