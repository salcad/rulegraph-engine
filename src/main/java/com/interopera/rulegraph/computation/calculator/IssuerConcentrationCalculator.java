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
import java.util.Map;

/** Largest single corporate issuer concentration vs the per-issuer cap (Section 3.2). */
@Component
public class IssuerConcentrationCalculator implements FigureCalculator {

    private final FormulaLibrary formulas;

    public IssuerConcentrationCalculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.ISSUER_CONCENTRATION;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal selects the largest issuer; the registry formula does the arithmetic.
        Map<String, BigDecimal> byIssuer = ctx.portfolio().sumByIssuer("corporate");
        Concentrations.Top top = Concentrations.largest(byIssuer);
        List<FigureInput> inputs = List.of(
                new FigureInput("subject_mv", top.value(),
                        "market value of the largest single corporate issuer (" + top.key() + ")",
                        ctx.portfolio().sumByIssuerCypher("corporate")),
                new FigureInput("nav", ctx.nav(), "Σ market value of all positions (NAV)",
                        ctx.portfolio().navCypher()));
        BigDecimal pct = formulas.evaluate(key(), FigureInput.vars(inputs));

        String util = Formatting.utilization(pct, rule.max(), ctx.firm());
        String limit = "max " + Formatting.percentBound(rule.max()) + "%";
        String path = "(Position)-[:ISSUED_BY]->(Issuer:" + top.key()
                + ") || (ConcentrationLimit:" + rule.code()
                + ")-[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";
        String cypher = TraceCypher.trace()
                .match().node("Position").rel("ISSUED_BY").node("Issuer", top.key()).end()
                .match().node("ConcentrationLimit", rule.code())
                        .rel("DEFINED_BY").node("GuidelineChunk", rule.citation().chunkId())
                .end().build();

        return new FigureResult(rule.code(), Formatting.percent1dp(pct),
                Statuses.againstMax(pct, rule.max()), limit, util,
                formulas.expression(key()), inputs, path, cypher, rule.citation(), pct);
    }
}
