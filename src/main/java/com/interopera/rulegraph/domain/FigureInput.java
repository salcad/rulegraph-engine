package com.interopera.rulegraph.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One named input bound into a figure's formula, with the exact value used and a short, human
 * description of how it was obtained from the graph.
 *
 * <p>This is what turns a formula on the report from an abstract expression into a traceable
 * substitution: the page can show {@code subject_mv = 35,000,000 (Σ market value of SGS positions)}
 * next to {@code subject_mv / nav * 100}. The values are produced by the graph traversal in the
 * calculator, never by the language model.
 *
 * @param name        the variable name as it appears in the formula, e.g. {@code subject_mv}
 * @param value       the exact value bound to it for this run
 * @param description how it was derived (the traversal / sum behind it)
 * @param query       the exact graph query that produced the value (params rendered inline)
 */
public record FigureInput(String name, BigDecimal value, String description, String query) {

    /** Collects a binding list into the variable map the evaluator consumes (so the two never drift). */
    public static Map<String, BigDecimal> vars(List<FigureInput> inputs) {
        Map<String, BigDecimal> vars = new LinkedHashMap<>();
        for (FigureInput in : inputs) {
            vars.put(in.name(), in.value());
        }
        return vars;
    }
}
