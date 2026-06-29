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

    private final FormulaLibrary formulas;

    public AggregateExposureCalculator(FormulaLibrary formulas) {
        this.formulas = formulas;
    }

    @Override
    public FormulaKey key() {
        return FormulaKey.AGGREGATE_EXPOSURE_PERCENT;
    }

    @Override
    public FigureResult compute(ResolvedRule rule, ComputationContext ctx) {
        // Graph traversal (plus the firm's fallen-angel convention) selects the contributing market
        // value; the registry formula does the arithmetic.
        List<String> contributors = rule.contributors();
        BigDecimal mv = ctx.portfolio().marketValueInAssetClasses(contributors);

        boolean fallenAngels = ctx.firm().includeFallenAngels();
        if (fallenAngels) {
            mv = mv.add(ctx.portfolio().fallenAngelMarketValueOutside(contributors));
        }

        String subjectDesc = "Σ market value of non-IG contributing classes (" + String.join(" + ", contributors) + ")"
                + (fallenAngels ? " plus fallen-angel positions downgraded below IG" : "");
        String subjectQuery = ctx.portfolio().marketValueInAssetClassesCypher(contributors)
                + (fallenAngels ? "\n\n-- plus fallen angels --\n"
                        + ctx.portfolio().fallenAngelCypher(contributors) : "");
        List<FigureInput> inputs = List.of(
                new FigureInput("subject_mv", mv, subjectDesc, subjectQuery),
                new FigureInput("nav", ctx.nav(), "Σ market value of all positions (NAV)",
                        ctx.portfolio().navCypher()));
        BigDecimal pct = formulas.evaluate(key(), FigureInput.vars(inputs));
        FigureStatus status = Statuses.againstMax(pct, rule.max());
        String util = Formatting.utilization(pct, rule.max(), ctx.firm());
        String limit = "max " + Formatting.percentBound(rule.max()) + "%";

        return new FigureResult(rule.code(), Formatting.percent1dp(pct), status,
                limit, util, formulas.expression(key()), inputs,
                buildPath(rule, fallenAngels), buildCypher(rule), rule.citation(), pct);
    }

    private String buildPath(ResolvedRule rule, boolean fallenAngels) {
        List<String> hops = new ArrayList<>();
        for (String c : rule.contributors()) {
            // The value sums the positions in each contributing class, so the trace starts at Position
            // (as every other figure's path does), not at the asset class.
            hops.add("(Position)-[:IN_ASSET_CLASS]->(AssetClass:" + c
                    + ")-[:CONTRIBUTES_TO]->(Aggregate:" + rule.code() + ")");
        }
        String path = String.join(" , ", hops)
                + " -[:DEFINED_BY]->(GuidelineChunk:" + rule.citation().chunkId() + ")";
        if (fallenAngels) {
            path += " (+ fallen-angel Positions via downgraded_from)";
        }
        return path;
    }

    // The fallen-angel note on the display path is firm convention, not an edge in the graph, so the
    // runnable trace covers the positions in each contributing class rolling up to the aggregate and
    // its source.
    private String buildCypher(ResolvedRule rule) {
        TraceCypher trace = TraceCypher.trace();
        for (String c : rule.contributors()) {
            trace.match().node("Position")
                    .rel("IN_ASSET_CLASS").node("AssetClass", c)
                    .rel("CONTRIBUTES_TO").node("Aggregate", rule.code()).end();
        }
        return trace.match().node("Aggregate", rule.code())
                .rel("DEFINED_BY").node("GuidelineChunk", rule.citation().chunkId())
                .end().build();
    }
}
