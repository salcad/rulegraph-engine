package com.interopera.rulegraph.computation.calculator;

import com.interopera.rulegraph.computation.ComputationContext;
import com.interopera.rulegraph.computation.FigureCalculator;
import com.interopera.rulegraph.computation.Formatting;
import com.interopera.rulegraph.computation.ResolvedRule;
import com.interopera.rulegraph.computation.Statuses;
import com.interopera.rulegraph.computation.dsl.FormulaLibrary;
import com.interopera.rulegraph.domain.FigureInput;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import com.interopera.rulegraph.domain.FormulaKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Allocation % of an asset class vs its min/max band (Section 2). */
@Component
public class AllocationCalculator implements FigureCalculator {

    private final FormulaLibrary formulas;

    public AllocationCalculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.ALLOCATION_PERCENT;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal selects the positions; the registry formula does the arithmetic.
        List<String> codes = List.of(rule.code());
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(codes);
        List<FigureInput> inputs = List.of(
                new FigureInput("subject_mv", mv,
                        "Σ market value of positions in asset class '" + rule.code() + "'",
                        ctx.portfolio().marketValueInAssetClassesCypher(codes)),
                new FigureInput("nav", ctx.nav(), "Σ market value of all positions (NAV)",
                        ctx.portfolio().navCypher()));
        BigDecimal pct = formulas.evaluate(key(), FigureInput.vars(inputs));

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
                limit, util, formulas.expression(key()), inputs, path, rule.citation(), pct);
    }

    private FigureStatus allocationStatus(BigDecimal pct, BigDecimal min, BigDecimal max) {
        if (pct.compareTo(min) < 0 || pct.compareTo(max) > 0) return FigureStatus.BREACH;
        return Statuses.againstMax(pct, max); // AT_LIMIT if exactly at the cap, else OK
    }
}
