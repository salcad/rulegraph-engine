package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.Citation;
import com.interopera.rulegraph.domain.FormulaKey;

import java.math.BigDecimal;
import java.util.List;

/**
 * A rule resolved from the graph, ready to compute: its bounds, the trusted formula to run, the
 * asset classes that contribute to it, and the source chunk it is defined by. Produced by
 * {@link GraphRuleResolver} via traversal — so a figure's limit and citation come from the graph,
 * not from code.
 *
 * @param code         canonical figure/rule id
 * @param formulaKey   trusted computation method
 * @param min          lower bound (allocation min / liquidity floor / duration min), else null
 * @param max          upper bound (allocation max / cap / duration max / DV01 max), else null
 * @param unit         {@code PERCENT|YEARS|SGD_PER_BP}
 * @param contributors asset-class codes feeding an aggregate / liquidity figure
 * @param citation     source passage (trace terminus)
 */
public record ResolvedRule(
        String code,
        FormulaKey formulaKey,
        BigDecimal min,
        BigDecimal max,
        String unit,
        List<String> contributors,
        Citation citation
) {
    public boolean isTraceable() {
        return citation != null
                && citation.chunkId() != null
                && !"chunk_unresolved".equals(citation.chunkId());
    }
}
