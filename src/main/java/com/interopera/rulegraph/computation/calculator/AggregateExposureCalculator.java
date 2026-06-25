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
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate exposure across contributing asset classes vs a cap (Section 2 note — non-IG).
 *
 * <p>Firm-switchable: when {@code includeFallenAngels} is set (Firm B convention 1), positions
 * downgraded below investment grade count toward the aggregate even if their asset class does not —
 * driven purely by config, not a code branch the engine knows as "Firm B".
 */
@Component
public class AggregateExposureCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.AGGREGATE_EXPOSURE_PERCENT;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        List<String> contributors = rule.contributors();
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(contributors);

        boolean fallenAngels = ctx.firm().includeFallenAngels();
        if (fallenAngels) {
            mv = mv.add(ctx.portfolio().fallenAngelMarketValueOutside(contributors));
        }

        BigDecimal pct = Formatting.percentOf(mv, ctx.nav());
        FigureStatus status = Statuses.againstMax(pct, rule.max());
        String util = Formatting.utilization(pct, rule.max(), ctx.firm());
        String limit = "max " + Formatting.percentBound(rule.max()) + "%";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct), status,
                limit, util, buildPath(rule, fallenAngels), rule.citation(), pct);
    }

    private String buildPath(ResolvedRule rule, boolean fallenAngels) {
        List<String> hops = new ArrayList<>();
        for (String c : rule.contributors()) {
            hops.add("(AssetClass:" + c + ")-[:CONTRIBUTES_TO]->(Aggregate:" + rule.code() + ")");
        }
        String path = String.join(" , ", hops)
                + " -[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";
        if (fallenAngels) {
            path += " (+ fallen-angel Positions via downgraded_from)";
        }
        return path;
    }
}
