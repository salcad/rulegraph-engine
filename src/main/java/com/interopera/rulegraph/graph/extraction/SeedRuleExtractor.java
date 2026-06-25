package com.interopera.rulegraph.graph.extraction;

import com.interopera.rulegraph.domain.FormulaKey;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.domain.RuleType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic baseline {@link RuleExtractor}.
 *
 * <p>It produces the <em>approved</em> set of rule intents for the Meridian guidelines, binding each
 * to the real source chunk it appears in (so provenance and page numbers are genuine, taken from the
 * parsed PDF — not invented). In the production flow an LLM proposes these intents and a human
 * approves them at gate G2; here we ship the post-approval set so the system runs offline. Because
 * an LLM-backed extractor implements the same interface, swapping it in changes nothing downstream.
 *
 * <p>This class contains no portfolio figures and performs no computation — it only states which
 * limit/threshold a passage expresses and which trusted {@code FormulaKey} computes it.
 */
@Component
public class SeedRuleExtractor implements RuleExtractor {

    private static final String PCT = "PERCENT";

    @Override
    public List<RuleIntent> extract(List<GuidelineChunk> chunks) {
        List<RuleIntent> intents = new ArrayList<>();

        // --- Section 2: per-asset-class allocation bands (min/max % of NAV) ---
        intents.add(allocation("singapore_government_securities", 20, 60,
                chunkFor(chunks, "Singapore Government Securities"), "Section 2 — SGS allocation band"));
        intents.add(allocation("mas_bills", 0, 40,
                chunkFor(chunks, "MAS Bills"), "Section 2 — MAS Bills allocation band"));
        intents.add(allocation("investment_grade_corporate_bonds", 10, 50,
                chunkFor(chunks, "Investment Grade Corporate Bonds"), "Section 2 — IG corporate allocation band"));
        intents.add(allocation("high_yield", 0, 15,
                chunkFor(chunks, "High Yield"), "Section 2 — High Yield allocation band"));
        intents.add(allocation("foreign_currency_bonds", 0, 20,
                chunkFor(chunks, "Foreign Currency Bonds"), "Section 2 — FX bonds allocation band"));
        intents.add(allocation("structured_credit", 0, 10,
                chunkFor(chunks, "Structured Credit"), "Section 2 — Structured credit allocation band"));
        intents.add(allocation("cash", 5, 25,
                chunkFor(chunks, "Cash"), "Section 2 — Cash allocation band"));

        // --- Section 2 note: aggregate non-IG exposure cap ---
        intents.add(new RuleIntent(
                RuleType.EXPOSURE_LIMIT, "aggregate_non_ig_exposure", FormulaKey.AGGREGATE_EXPOSURE_PERCENT,
                null, bd(20), PCT,
                List.of("high_yield", "structured_credit"),
                chunkFor(chunks, "non-investment-grade"),
                "Section 2 note — aggregate non-IG exposure cap", 1.0));

        // --- Section 3.2: concentration caps ---
        intents.add(new RuleIntent(
                RuleType.CONCENTRATION_LIMIT, "single_corporate_issuer", FormulaKey.ISSUER_CONCENTRATION,
                null, bd(8), PCT, List.of(),
                chunkFor(chunks, "single issuer"),
                "Section 3.2 — single issuer concentration cap", 1.0));
        intents.add(new RuleIntent(
                RuleType.CONCENTRATION_LIMIT, "gre_issuer", FormulaKey.GROUP_CONCENTRATION,
                null, bd(12), PCT, List.of(),
                chunkFor(chunks, "Government-related entities"),
                "Section 3.2 — GRE concentration cap", 1.0));

        // --- Section 3.3: liquidity floor (SGS + MAS Bills + Cash are the liquid asset classes) ---
        intents.add(new RuleIntent(
                RuleType.LIQUIDITY_FLOOR, "liquid_assets_ratio", FormulaKey.LIQUIDITY_RATIO,
                bd(25), null, PCT,
                List.of("singapore_government_securities", "mas_bills", "cash"),
                chunkFor(chunks, "Liquid assets"),
                "Section 3.3 — liquidity floor (normal conditions)", 1.0));

        // --- Section 3.1: market-risk metrics with thresholds + breach actions ---
        intents.add(new RuleIntent(
                RuleType.RISK_METRIC, "modified_duration", FormulaKey.PORTFOLIO_DURATION,
                bd(2.0), bd(6.5), "YEARS", List.of(),
                chunkFor(chunks, "Modified Duration"),
                "Section 3.1 — modified duration band", 1.0));
        intents.add(new RuleIntent(
                RuleType.RISK_METRIC, "portfolio_dv01", FormulaKey.PORTFOLIO_DV01,
                null, bd(85000), "SGD_PER_BP", List.of(),
                chunkFor(chunks, "DV01"),
                "Section 3.1 — portfolio DV01 limit", 1.0));

        return intents;
    }

    private RuleIntent allocation(String code, int min, int max, String chunkId, String summary) {
        return new RuleIntent(RuleType.ALLOCATION_LIMIT, code, FormulaKey.ALLOCATION_PERCENT,
                bd(min), bd(max), PCT, List.of(), chunkId, summary, 1.0);
    }

    /**
     * Finds the chunk a rule is defined by, by locating the first chunk whose text contains the
     * keyword. Returns the real chunk id (genuine provenance) or {@code "chunk_unresolved"} if the
     * passage could not be located — which would surface downstream as an untraceable figure rather
     * than a silently fabricated citation.
     */
    private String chunkFor(List<GuidelineChunk> chunks, String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        return chunks.stream()
                .filter(c -> c.text().toLowerCase(Locale.ROOT).contains(needle))
                .map(GuidelineChunk::chunkId)
                .findFirst()
                .orElse("chunk_unresolved");
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
