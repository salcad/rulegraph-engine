package com.interopera.rulegraph.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

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
 * @param graphPath    the graph traversal that produced this figure
 * @param citation     the source passage the rule was defined by
 * @param numericValue exact unrounded value (for reconciliation/firewall); null when ERROR
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"figure", "value", "status", "limit", "utilization", "graph_path", "citation"})
public record FigureResult(
        String figure,
        String value,
        FigureStatus status,
        String limit,
        String utilization,
        String graphPath,
        Citation citation,
        BigDecimal numericValue
) {
    public static FigureResult error(String figure, String reason) {
        return new FigureResult(figure, null, FigureStatus.ERROR, null, reason, null, null, null);
    }
}
