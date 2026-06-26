package com.interopera.rulegraph.graph.extraction;

import java.util.Locale;

/**
 * Which {@link RuleExtractor} a run should use. {@code SEED} is the deterministic, hardcoded baseline
 * ({@link SeedRuleExtractor}); {@code LLM} asks a frontier model to interpret the guideline text
 * ({@link LlmRuleExtractor}, which itself falls back to the seed set if no API key is configured).
 *
 * <p>This lets the choice be made per request (e.g. a switch in the report viewer) rather than only
 * at startup via configuration.
 */
public enum ExtractorMode {
    SEED,
    LLM;

    /**
     * Parses a request value (case-insensitive) into a mode, returning {@code dflt} when the value is
     * absent or unrecognised so a stray query parameter never fails a report.
     */
    public static ExtractorMode parse(String raw, ExtractorMode dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
