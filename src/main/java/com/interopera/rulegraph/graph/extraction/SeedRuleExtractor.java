package com.interopera.rulegraph.graph.extraction;

import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.RuleIntent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deterministic baseline {@link RuleExtractor}.
 *
 * <p>It loads the <em>approved</em> set of rule intents for the Meridian guidelines from the
 * {@code seed_rules.json} resource and binds each to the real source chunk it appears in (so
 * provenance and page numbers are genuine, taken from the parsed PDF — not invented). The rules are
 * data, in the exact JSON shape an LLM-backed extractor returns: in the production flow an LLM
 * proposes these intents and a human approves them at gate G2; here we ship the post-approval reply
 * as a file so the system runs offline. Because both extractors hand that JSON to the same
 * {@link RuleIntentJsonMapper}, swapping one for the other changes nothing downstream.
 *
 * <p>This class contains no portfolio figures and performs no computation — the resource only states
 * which limit/threshold a passage expresses and which trusted {@code FormulaKey} computes it.
 */
@Component
public class SeedRuleExtractor implements RuleExtractor {

    private static final String SEED_RESOURCE = "/seed_rules.json";

    private final RuleIntentJsonMapper mapper;
    private final String seedJson;

    public SeedRuleExtractor(RuleIntentJsonMapper mapper) {
        this.mapper = mapper;
        this.seedJson = loadResource(SEED_RESOURCE);
    }

    /** The raw {@code seed_rules.json} text, so a fallback can show the operator the cached rule set. */
    public String rawJson() {
        return seedJson;
    }

    @Override
    public List<RuleIntent> extract(List<GuidelineChunk> chunks, List<String> knownAssetClassCodes) {
        // The seed set already uses the canonical codes, so the holdings vocabulary is not needed here;
        // each rule's source_keyword is resolved against the supplied chunks for genuine provenance.
        try {
            return mapper.map(seedJson, chunks);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse the seed rule set from " + SEED_RESOURCE, e);
        }
    }

    private static String loadResource(String path) {
        try (InputStream in = SeedRuleExtractor.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Seed rule resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read seed rule resource: " + path, e);
        }
    }
}
