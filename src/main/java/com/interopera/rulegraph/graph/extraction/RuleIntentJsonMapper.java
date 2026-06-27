package com.interopera.rulegraph.graph.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interopera.rulegraph.domain.FormulaKey;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.domain.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a {@code {"intents": [...]}} JSON document into validated {@link RuleIntent}s, applying the
 * same trust gates regardless of who produced the JSON. Both rule extractors share it: the
 * {@link LlmRuleExtractor} feeds it a frontier model's reply, and the {@link SeedRuleExtractor} feeds
 * it the frozen {@code seed_rules.json} resource. Because the deterministic baseline and the LLM go
 * through one mapper, the seed set is literally "an approved extractor reply, shipped as data" rather
 * than hand-built objects.
 *
 * <p><b>Gate G1 is enforced here.</b> A rule whose {@code rule_type} or {@code formula_key} is not on
 * the {@link RuleType} / {@link FormulaKey} allow-lists, or that has no target, is dropped — it could
 * never reach a calculator. Only the first rule per {@code target_code} is kept (gate G1b), so two
 * rules can never merge onto the same graph node and corrupt a figure.
 *
 * <p><b>Provenance.</b> Two field shapes are accepted with the same meaning. A live LLM emits a
 * literal {@code source_chunk_id}, validated against the chunk ids actually parsed from the document.
 * The frozen seed set instead carries {@code source_keyword}, resolved to a real chunk id by first
 * text match, which stays correct when the guideline PDF is re-parsed (a chunk id is a hash of the
 * page text). Anything that does not resolve becomes {@code chunk_unresolved}, so it surfaces
 * downstream as an untraceable rule rather than a fabricated citation.
 *
 * <p>The mapper never produces a portfolio figure: it only copies the limit/threshold values present
 * in the JSON and names a trusted {@code FormulaKey}.
 */
@Component
public class RuleIntentJsonMapper {

    private static final Logger log = LoggerFactory.getLogger(RuleIntentJsonMapper.class);

    private static final String UNRESOLVED = "chunk_unresolved";

    private final ObjectMapper json;

    public RuleIntentJsonMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Parses raw JSON (optionally wrapped in a Markdown code fence, as some models return) into
     * validated intents. Accepts either {@code {"intents": [...]}} or a bare top-level array.
     *
     * @throws Exception if the text is not valid JSON or has no intents array
     */
    public List<RuleIntent> map(String rawJson, List<GuidelineChunk> chunks) throws Exception {
        JsonNode root = json.readTree(stripCodeFences(rawJson));
        JsonNode intentsNode = root.has("intents") ? root.get("intents") : root;
        if (!intentsNode.isArray()) {
            throw new IllegalStateException("Expected an 'intents' array in the rule JSON");
        }

        List<RuleIntent> intents = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (JsonNode node : intentsNode) {
            RuleIntent intent = toIntent(node, chunks);
            if (intent == null) {
                continue;
            }
            // Gate G1b: one rule per target_code. A second intent for a code already seen (e.g. both
            // a normal and a stress liquidity floor as "liquid_assets_ratio") would otherwise merge
            // onto the same graph node, overwrite its limit, and surface as two corrupted figures.
            if (!seenCodes.add(intent.targetCode())) {
                log.warn("Dropping duplicate intent for target_code '{}' — only the first rule for a "
                        + "code is kept", intent.targetCode());
                continue;
            }
            intents.add(intent);
        }
        return intents;
    }

    /** Maps one JSON node to a validated intent, or {@code null} if it fails the allow-list checks. */
    private RuleIntent toIntent(JsonNode node, List<GuidelineChunk> chunks) {
        RuleType ruleType = parseEnum(RuleType.class, text(node, "rule_type"));
        FormulaKey formulaKey = parseEnum(FormulaKey.class, text(node, "formula_key"));
        String targetCode = text(node, "target_code");

        // Gate G1: reject anything whose type or formula is not on the trusted allow-list, and any
        // rule without a target — these can never reach a calculator.
        if (ruleType == null || formulaKey == null || targetCode == null || targetCode.isBlank()) {
            log.warn("Dropping rule intent with off-list or incomplete fields: {}", node);
            return null;
        }

        return new RuleIntent(
                ruleType,
                targetCode.trim(),
                formulaKey,
                decimal(node, "min_value"),
                decimal(node, "max_value"),
                textOr(node, "unit", "PERCENT"),
                stringList(node, "contributing_codes"),
                resolveChunkId(node, chunks),
                textOr(node, "passage_summary", ""),
                confidence(node));
    }

    /**
     * Resolves a rule's source chunk. {@code source_keyword} (the frozen seed set) wins when present:
     * it is matched to the first chunk whose text contains it, so the citation tracks the live parse.
     * Otherwise {@code source_chunk_id} (a live LLM reply) is validated against the parsed chunk ids.
     * Neither resolving yields {@code chunk_unresolved}.
     */
    private static String resolveChunkId(JsonNode node, List<GuidelineChunk> chunks) {
        String keyword = text(node, "source_keyword");
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            return chunks.stream()
                    .filter(c -> c.text().toLowerCase(Locale.ROOT).contains(needle))
                    .map(GuidelineChunk::chunkId)
                    .findFirst()
                    .orElse(UNRESOLVED);
        }
        String chunkId = text(node, "source_chunk_id");
        if (chunkId != null) {
            for (GuidelineChunk c : chunks) {
                if (c.chunkId().equals(chunkId)) {
                    return chunkId;
                }
            }
        }
        return UNRESOLVED;
    }

    /**
     * Strips a Markdown code fence around model JSON. Despite a {@code json_object} response format,
     * some models still wrap the body in ```json ... ``` (or plain ``` ... ```), whose backtick makes
     * Jackson fail at column 1. We unwrap the first fenced block and parse its contents. The frozen
     * seed resource is never fenced, so this is a no-op for it.
     */
    static String stripCodeFences(String raw) {
        String s = raw.strip();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        s = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
        int closingFence = s.lastIndexOf("```");
        if (closingFence >= 0) {
            s = s.substring(0, closingFence);
        }
        return s.strip();
    }

    // --- small JSON helpers --------------------------------------------------------------------

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode node, String field, String dflt) {
        String v = text(node, field);
        return v == null || v.isBlank() ? dflt : v;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode el : v) {
            if (!el.isNull() && !el.asText().isBlank()) {
                out.add(el.asText().trim());
            }
        }
        return out;
    }

    private static double confidence(JsonNode node) {
        JsonNode v = node.get("extraction_confidence");
        double c = v == null || v.isNull() ? 0.5 : v.asDouble(0.5);
        return Math.max(0.0, Math.min(1.0, c));
    }
}
