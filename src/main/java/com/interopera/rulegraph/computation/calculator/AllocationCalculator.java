package com.interopera.rulegraph.computation.calculator;

import com.interopera.rulegraph.computation.ComputationContext;
import com.interopera.rulegraph.computation.FigureCalculator;
import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.computation.ResolvedRule;
import com.interopera.rulegraph.computation.Statuses;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import com.interopera.rulegraph.domain.FormulaKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Allocation % of an asset class vs its min/max band (Section 2). */
@Component
public class AllocationCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.ALLOCATION_PERCENT;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(List.of(rule.code()));
        BigDecimal pct = Formatting.percentOf(mv, ctx.nav());

        FigureStatus status = allocationStatus(pct, rule.min(), rule.max());
        // When the binding constraint is the floor (and it is breached), utilization-vs-max is not
        // meaningful — report n/a, matching the answer key's convention.
        String util = pct.compareTo(rule.min()) < 0
                ? "n/a"
                : Formatting.utilization(pct, rule.max(), ctx.firm());

        String limit = Formatting.percentBound(rule.min()) + "–" + Formatting.percentBound(rule.max()) + "%";
        String path = "(Position)-[:IN_ASSET_CLASS]->(AssetClass:" + rule.code()
                + ")-[:HAS_LIMIT]->(Limit:" + rule.code()
                + ")-[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct), status,
                limit, util, path, rule.citation(), pct);
    }

    private FigureStatus allocationStatus(BigDecimal pct, BigDecimal min, BigDecimal max) {
        if (pct.compareTo(min) < 0 || pct.compareTo(max) > 0) return FigureStatus.BREACH;
        return Statuses.againstMax(pct, max); // AT_LIMIT if exactly at the cap, else OK
    }
}
