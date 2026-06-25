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
import java.util.Map;

/** Largest single corporate issuer concentration vs the per-issuer cap (Section 3.2). */
@Component
public class IssuerConcentrationCalculator implements FigureCalculator {

    @Override
    public FormulaKey key() {
        return FormulaKey.ISSUER_CONCENTRATION;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        Map<String, BigDecimal> byIssuer = ctx.portfolio().sumByIssuer("corporate");
        Concentrations.Top top = Concentrations.largest(byIssuer);
        BigDecimal pct = Formatting.percentOf(top.value(), ctx.nav());

        String util = Formatting.utilization(pct, rule.max(), ctx.firm());
        String limit = "max " + Formatting.percentBound(rule.max()) + "%";
        String path = "(Position)-[:ISSUED_BY]->(Issuer:" + top.key()
                + ") || (ConcentrationLimit:" + rule.code()
                + ")-[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct),
                Statuses.againstMax(pct, rule.max()), limit, util, path, rule.citation(), pct);
    }
}
