package com.interopera.rulegraph.computation.calculator;

import com.interopera.rulegraph.computation.ComputationContext;
import com.interopera.rulegraph.computation.FigureCalculator;
import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.computation.ResolvedRule;
import com.interopera.rulegraph.computation.Statuses;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FormulaKey;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * GRE (government-related entity) concentration vs the GRE cap (Section 3.2).
 *
 * <p>Firm-switchable: Firm A measures per issuer; Firm B (convention 2) aggregates issuers sharing a
 * {@code parent_issuer} and tests the group. Driven by {@code greGroupBy} in config — the traversal
 * chosen (ISSUED_BY only, vs ISSUED_BY + ROLLS_UP_TO) changes, the engine code does not.
 */
@Component
public class GroupConcentrationCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.GROUP_CONCENTRATION;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        boolean byParent = ctx.firm().greGroupBy() == FirmConfig.GreGroupBy.PARENT_ISSUER;
        Map<String, BigDecimal> totals = byParent
                ? ctx.portfolio().sumByParentIssuer("GRE")
                : ctx.portfolio().sumByIssuer("GRE");
        Concentrations.Top top = Concentrations.largest(totals);
        BigDecimal pct = Formatting.percentOf(top.value(), ctx.nav());

        String util = Formatting.utilization(pct, rule.max(), ctx.firm());
        String limit = "max " + Formatting.percentBound(rule.max()) + "%";
        String groupHop = byParent
                ? "(Issuer)-[:ROLLS_UP_TO]->(ParentIssuer:" + top.key() + ")"
                : "(Issuer:" + top.key() + ")";
        String path = "(Position)-[:ISSUED_BY]->" + groupHop
                + " || (ConcentrationLimit:" + rule.code()
                + ")-[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct),
                Statuses.againstMax(pct, rule.max()), limit, util, path, rule.citation(), pct);
    }
}
