package com.interopera.rulegraph.graph.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interopera.rulegraph.domain.FormulaKey;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.Provenance;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.domain.RuleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deterministic baseline now loads from {@code seed_rules.json} through the shared
 * {@link RuleIntentJsonMapper}: it produces the approved 13-rule set, resolves each rule's
 * {@code source_keyword} to a real chunk id against the supplied chunks, and carries the limit values
 * read from the guideline text.
 */
class SeedRuleExtractorTest {

    private final SeedRuleExtractor extractor =
            new SeedRuleExtractor(new RuleIntentJsonMapper(new ObjectMapper()));

    /** A single chunk whose text contains every keyword the seed set resolves against. */
    private static List<GuidelineChunk> chunksWithAllKeywords() {
        String text = String.join(" ",
                "Singapore Government Securities", "MAS Bills", "Investment Grade Corporate Bonds",
                "High Yield", "Foreign Currency Bonds", "Structured Credit", "Cash",
                "non-investment-grade", "single issuer", "Government-related entities",
                "Liquid assets", "Modified Duration", "DV01");
        Provenance prov = Provenance.deterministic("guidelines.pdf", "chunk_real");
        return List.of(new GuidelineChunk("chunk_real", 1, text, "all rules", prov));
    }

    @Test
    void producesTheApprovedThirteenRuleSet() {
        List<RuleIntent> intents = extractor.extract(chunksWithAllKeywords(), List.of());
        assertEquals(13, intents.size(), "the frozen seed set defines 13 rules");
    }

    @Test
    void resolvesEverySourceKeywordToARealChunk() {
        List<RuleIntent> intents = extractor.extract(chunksWithAllKeywords(), List.of());
        assertTrue(
                intents.stream().allMatch(i -> "chunk_real".equals(i.sourceChunkId())),
                "every rule resolves its source_keyword to the supplied chunk");
    }

    @Test
    void unmatchedKeywordSurfacesAsUnresolvedRatherThanFabricated() {
        // No chunk contains any keyword -> provenance is unresolved, never invented.
        Provenance prov = Provenance.deterministic("guidelines.pdf", "chunk_x");
        List<GuidelineChunk> chunks =
                List.of(new GuidelineChunk("chunk_x", 1, "nothing relevant here", "x", prov));
        List<RuleIntent> intents = extractor.extract(chunks, List.of());
        assertTrue(
                intents.stream().allMatch(i -> "chunk_unresolved".equals(i.sourceChunkId())),
                "an unmatched keyword yields chunk_unresolved, not a fabricated citation");
    }

    @Test
    void carriesLimitValuesAndTypesFromTheJson() {
        Map<String, RuleIntent> byCode = extractor.extract(chunksWithAllKeywords(), List.of())
                .stream()
                .collect(Collectors.toMap(RuleIntent::targetCode, Function.identity()));

        RuleIntent highYield = byCode.get("high_yield");
        assertEquals(RuleType.ALLOCATION_LIMIT, highYield.ruleType());
        assertEquals(FormulaKey.ALLOCATION_PERCENT, highYield.formulaKey());
        assertEquals(0, new BigDecimal("0").compareTo(highYield.minValue()));
        assertEquals(0, new BigDecimal("15").compareTo(highYield.maxValue()));

        RuleIntent nonIg = byCode.get("aggregate_non_ig_exposure");
        assertEquals(FormulaKey.AGGREGATE_EXPOSURE_PERCENT, nonIg.formulaKey());
        assertNull(nonIg.minValue(), "a cap has no lower bound");
        assertEquals(0, new BigDecimal("20").compareTo(nonIg.maxValue()));
        assertEquals(List.of("high_yield", "structured_credit"), nonIg.contributingCodes());

        RuleIntent dv01 = byCode.get("portfolio_dv01");
        assertEquals("SGD_PER_BP", dv01.unit());
        assertEquals(0, new BigDecimal("85000").compareTo(dv01.maxValue()));
    }

    @Test
    void dropsOffAllowListRulesAndDuplicateCodes() {
        ObjectMapper json = new ObjectMapper();
        RuleIntentJsonMapper mapper = new RuleIntentJsonMapper(json);
        String raw = """
                {"intents": [
                  {"rule_type": "ALLOCATION_LIMIT", "target_code": "cash",
                   "formula_key": "ALLOCATION_PERCENT", "min_value": 5, "max_value": 25,
                   "source_keyword": "Cash"},
                  {"rule_type": "ALLOCATION_LIMIT", "target_code": "cash",
                   "formula_key": "ALLOCATION_PERCENT", "min_value": 9, "max_value": 9,
                   "source_keyword": "Cash"},
                  {"rule_type": "NOT_A_TYPE", "target_code": "bogus",
                   "formula_key": "ALLOCATION_PERCENT", "source_keyword": "Cash"},
                  {"rule_type": "ALLOCATION_LIMIT", "target_code": "bogus2",
                   "formula_key": "NOT_A_KEY", "source_keyword": "Cash"}
                ]}
                """;
        Provenance prov = Provenance.deterministic("guidelines.pdf", "chunk_cash");
        List<GuidelineChunk> chunks =
                List.of(new GuidelineChunk("chunk_cash", 1, "Cash band", "cash", prov));

        List<RuleIntent> intents;
        try {
            intents = mapper.map(raw, chunks);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(1, intents.size(), "off-list rules dropped (G1), duplicate code dropped (G1b)");
        assertEquals("cash", intents.get(0).targetCode());
        assertEquals(0, new BigDecimal("25").compareTo(intents.get(0).maxValue()),
                "the first rule for a code wins");
        assertFalse(intents.stream().anyMatch(i -> i.targetCode().startsWith("bogus")));
    }
}
