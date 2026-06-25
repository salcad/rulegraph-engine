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
import java.util.ArrayList;
import java.util.List;

/** Liquid-assets ratio (SGS + MAS Bills + Cash) vs the liquidity floor (Section 3.3). */
@Component
public class LiquidityCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.LIQUIDITY_RATIO;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(rule.contributors());
        BigDecimal pct = Formatting.percentOf(mv, ctx.nav());

        String util = Formatting.utilization(pct, rule.min(), ctx.firm());
        String limit = "min " + Formatting.percentBound(rule.min()) + "%";

        List<String> hops = new ArrayList<>();
        for (String c : rule.contributors()) {
            hops.add("(AssetClass:" + c + ")-[:CONTRIBUTES_TO]->(LiquidityFloor:" + rule.code() + ")");
        }
        String path = String.join(" , ", hops)
                + " -[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct),
                Statuses.againstMin(pct, rule.min()), limit, util, path, rule.citation(), pct);
    }
}
