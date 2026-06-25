package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Computes every report figure for a given firm by:
 * <ol>
 *   <li>resolving all rules from the graph (limits, caps, floors, thresholds + their citations),</li>
 *   <li>dispatching each to its trusted {@link FigureCalculator} via the {@link FormulaRegistry},</li>
 *   <li>returning the figures in a fixed canonical order so output is byte-identical across runs.</li>
 * </ol>
 *
 * A rule that cannot be traced to a source chunk is emitted as an ERROR figure rather than a value
 * (constraint 2). No language model participates anywhere in this path (constraint 3).
 */
@Service
public class FigureComputationService {

    /** Stable report ordering; rules not listed sort to the end by code. */
    private static final List<String> ORDER = List.of(
            "singapore_government_securities", "mas_bills", "investment_grade_corporate_bonds",
            "high_yield", "foreign_currency_bonds", "structured_credit", "cash",
            "aggregate_non_ig_exposure", "single_corporate_issuer", "gre_issuer",
            "liquid_assets_ratio", "modified_duration", "portfolio_dv01");

    private final GraphRuleResolver ruleResolver;
    private final GraphPortfolioQueries portfolio;
    private final FormulaRegistry registry;

    public FigureComputationService(GraphRuleResolver ruleResolver,
                                    GraphPortfolioQueries portfolio,
                                    FormulaRegistry registry) {
        this.ruleResolver = ruleResolver;
        this.portfolio = portfolio;
        this.registry = registry;
    }

    public List<FigureResult> computeAll(FirmConfig firm) {
        BigDecimal nav = portfolio.nav();
        ComputationContext ctx = new ComputationContext(portfolio, nav, firm);

        return ruleResolver.resolveAll().stream()
                .sorted(Comparator.comparingInt(r -> orderIndex(r.code())))
                .map(rule -> computeOne(rule, ctx))
                .toList();
    }

    private FigureResult computeOne(ResolvedRule rule, ComputationContext ctx) {
        // Constraint 2: an untraceable figure is an error, never a silent value.
        if (!rule.isTraceable()) {
            return FigureResult.error(rule.code(), "untraceable: no resolvable source chunk");
        }
        // Constraint 3: only a registered calculator may produce a number.
        if (!registry.isRegistered(rule.formulaKey())) {
            return FigureResult.error(rule.code(),
                    "no registered calculator for " + rule.formulaKey());
        }
        return registry.get(rule.formulaKey()).compute(rule, ctx);
    }

    private int orderIndex(String code) {
        int idx = ORDER.indexOf(code);
        return idx < 0 ? ORDER.size() : idx;
    }

    /** Convenience for callers that want figures keyed by id. */
    public Map<String, FigureResult> computeAllByCode(FirmConfig firm) {
        return computeAll(firm).stream()
                .collect(java.util.stream.Collectors.toMap(
                        FigureResult::figure, f -> f, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
