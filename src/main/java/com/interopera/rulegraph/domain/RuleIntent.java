package com.interopera.rulegraph.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * A structured interpretation of a guideline passage — "what kind of rule is this, what entities
 * does it involve, what trusted formula does it map to, and which source chunk justifies it."
 *
 * <p>This is the <em>only</em> structured output the extraction layer (eventually an LLM) produces.
 * It deliberately carries no portfolio figure: it names a {@link FormulaKey}, never a computed
 * value. The graph builder turns these intents into rule nodes with {@code DEFINED_BY} edges back
 * to {@code sourceChunkId}, and the deterministic engine later resolves them to run the calculation.
 *
 * @param ruleType            classification of the rule
 * @param targetCode          canonical code of the thing being limited, e.g. {@code high_yield},
 *                            {@code aggregate_non_ig_exposure}, {@code single_corporate_issuer}
 * @param formulaKey          trusted computation method (must be on the allow-list)
 * @param minValue            lower bound for allocation bands, else {@code null}
 * @param maxValue            upper bound / cap / floor value, else {@code null}
 * @param unit                {@code PERCENT}, {@code YEARS}, {@code SGD_PER_BP}, ...
 * @param contributingCodes   asset-class codes that feed an aggregate (for EXPOSURE_LIMIT)
 * @param sourceChunkId       chunk this rule was extracted from (the trace terminus)
 * @param passageSummary      short human label for the citation
 * @param extractionConfidence 0.0–1.0 confidence; drives the gate-G1 auto-pass decision
 */
public record RuleIntent(
        RuleType ruleType,
        String targetCode,
        FormulaKey formulaKey,
        BigDecimal minValue,
        BigDecimal maxValue,
        String unit,
        List<String> contributingCodes,
        String sourceChunkId,
        String passageSummary,
        double extractionConfidence
) {
    public RuleIntent {
        contributingCodes = contributingCodes == null ? List.of() : List.copyOf(contributingCodes);
    }
}
