package com.interopera.rulegraph.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * One computed report figure, in the shape the brief specifies.
 *
 * <p>Every field except the narrative is produced by a deterministic calculator. {@code value} and
 * {@code utilization} are formatted strings; {@code numericValue} carries the exact unrounded value
 * for reconciliation and the no-LLM-numbers firewall (Phase 5). A figure that cannot be traced
 * {@code figure -> graphPath -> citation} is emitted with {@link FigureStatus#ERROR}, never a value.
 *
 * @param figure       canonical figure id, e.g. {@code aggregate_non_ig_exposure}
 * @param value        formatted value, e.g. {@code "15.0%"}
 * @param status       compliance status
 * @param limit        human-readable limit, e.g. {@code "max 20%"}
 * @param utilization  how much of the limit is used, e.g. {@code "75.0%"} or {@code "n/a"}
 * @param formula      the registry (DSL) expression evaluated to produce {@code numericValue},
 *                     e.g. {@code "subject_mv / nav * 100"}; null when ERROR
 * @param inputs       the variables bound into {@code formula}, each with its value and how it was
 *                     derived from the graph — so a figure is a traceable substitution; null on ERROR
 * @param graphPath    the graph traversal that produced this figure, as a human-readable path
 * @param cypher       the same traversal as runnable Cypher ({@code MATCH ... RETURN}) that
 *                     copy-pastes into the Neo4j browser; null when ERROR. The {@code graphPath} is
 *                     for reading, this is for running — see {@link com.interopera.rulegraph.computation.TraceCypher}
 * @param citation     the source passage the rule was defined by
 * @param numericValue exact unrounded value (for reconciliation/firewall); null when ERROR
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"figure", "value", "status", "limit", "utilization", "formula", "inputs",
        "graph_path", "cypher", "citation"})
public record FigureResult(
        String figure,
        String value,
        FigureStatus status,
        String limit,
        String utilization,
        String formula,
        List<FigureInput> inputs,
        String graphPath,
        String cypher,
        Citation citation,
        BigDecimal numericValue
) {
    public static FigureResult error(String figure, String reason) {
        return new FigureResult(figure, null, FigureStatus.ERROR, null, reason, null, null, null, null, null, null);
    }
}
