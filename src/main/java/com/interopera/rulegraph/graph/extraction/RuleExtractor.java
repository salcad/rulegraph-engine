package com.interopera.rulegraph.graph.extraction;

import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.RuleIntent;

import java.util.List;

/**
 * Turns guideline chunks into structured {@link RuleIntent}s.
 *
 * <p>This is the seam where the language model plugs in: an LLM implementation interprets each
 * chunk's prose into a rule intent (ruleType, target, formula key, source chunk). Crucially, an
 * intent names a {@code FormulaKey} and a threshold read from the text — it never produces a
 * portfolio figure. The deterministic baseline implementation
 * ({@link SeedRuleExtractor}) lets the system run end-to-end without an API key; an LLM-backed
 * implementation can replace it behind this same interface with no downstream change.
 */
public interface RuleExtractor {

    /**
     * @param chunks all chunks parsed from the guidelines document
     * @return the rule intents extracted from them, each linked to its source chunk
     */
    List<RuleIntent> extract(List<GuidelineChunk> chunks);
}
