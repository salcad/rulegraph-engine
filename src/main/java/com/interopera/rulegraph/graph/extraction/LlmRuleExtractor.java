package com.interopera.rulegraph.graph.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interopera.rulegraph.config.LlmProperties;
import com.interopera.rulegraph.domain.FormulaKey;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.domain.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM-backed {@link RuleExtractor}. It reads the parsed guideline chunks and asks a frontier model
 * (via the OpenRouter chat-completions API) to interpret each passage into a structured
 * {@link RuleIntent}: a rule type, a target code, the trusted {@link FormulaKey} that computes it,
 * the limit/threshold values stated in the text, and the source chunk that justifies it.
 *
 * <p><b>Constraint 3 is preserved structurally.</b> The model may only <em>name</em> a
 * {@code FormulaKey} and a {@code RuleType} drawn from fixed allow-lists, and may only echo
 * thresholds that appear in the guideline text — it never produces a portfolio figure. Any key or
 * type not on the allow-list is dropped here (gate G1), and any {@code sourceChunkId} the model
 * invents that is not a real chunk becomes {@code chunk_unresolved}, so it surfaces downstream as an
 * untraceable rule rather than a fabricated citation. The intents this class returns are exactly the
 * same shape the deterministic {@link SeedRuleExtractor} produces, so everything downstream — the
 * human approval gate, the graph builder, the calculators — is unchanged.
 *
 * <p><b>Determinism.</b> Extraction runs before the human-approval gate and before the graph is
 * trusted; the deterministic engine computes figures from the approved graph, not from this output.
 * The request uses {@code temperature = 0} to keep extraction as stable as possible, but the
 * audited guarantee (constraint 1) rests on the engine, not on the model.
 *
 * <p><b>Offline fallback.</b> If the API key is absent, or the call or parse fails for any reason,
 * this extractor logs a warning and delegates to {@link SeedRuleExtractor} so the system still runs.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "rulegraph.llm", name = "enabled", havingValue = "true")
public class LlmRuleExtractor implements RuleExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmRuleExtractor.class);

    private final LlmProperties props;
    private final SeedRuleExtractor fallback;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public LlmRuleExtractor(LlmProperties props, SeedRuleExtractor fallback, ObjectMapper mapper) {
        this.props = props;
        this.fallback = fallback;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .build();
    }

    @Override
    public List<RuleIntent> extract(List<GuidelineChunk> chunks, List<String> knownAssetClassCodes) {
        if (!props.hasApiKey()) {
            log.warn("rulegraph.llm.enabled=true but no API key is set — falling back to the "
                    + "deterministic seed extractor. Set OPENROUTER_API_KEY to use the LLM.");
            return fallback.extract(chunks, knownAssetClassCodes);
        }
        try {
            String content = callModel(chunks, knownAssetClassCodes);
            List<RuleIntent> intents = parseIntents(content, chunks);
            if (intents.isEmpty()) {
                log.warn("LLM returned no usable rule intents — falling back to the seed extractor");
                return fallback.extract(chunks, knownAssetClassCodes);
            }
            log.info("LLM extractor produced {} rule intent(s) from {} chunks via model {}",
                    intents.size(), chunks.size(), props.model());
            return intents;
        } catch (Exception e) {
            log.warn("LLM extraction failed ({}: {}) — falling back to the seed extractor",
                    e.getClass().getSimpleName(), e.getMessage());
            return fallback.extract(chunks, knownAssetClassCodes);
        }
    }

    // --- OpenRouter call -----------------------------------------------------------------------

    private String callModel(List<GuidelineChunk> chunks, List<String> knownAssetClassCodes)
            throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt(chunks, knownAssetClassCodes));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .header("X-Title", "RuleGraph rule extraction")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("OpenRouter response had no message content: "
                    + response.body());
        }
        return content.asText();
    }

    // --- Prompting -----------------------------------------------------------------------------

    private String systemPrompt() {
        return """
                You extract portfolio-compliance rules from fund guideline text into structured JSON.

                Hard rules:
                - You interpret rules only. You must NEVER compute, estimate, or invent any portfolio
                  figure, allocation, utilization, or report number. Only copy limit/threshold values
                  that appear literally in the provided text.
                - "rule_type" MUST be one of: %s
                - "formula_key" MUST be one of: %s
                - "source_chunk_id" MUST be one of the chunk_id values given in the user message. Never
                  invent a chunk id. If you cannot locate the passage, omit the rule.
                - Use null for a missing min or max bound.
                - "extraction_confidence" is your own 0.0-1.0 confidence that the rule was read correctly.

                Field meaning:
                - min_value: lower bound of an allocation band or a floor; null if none.
                - max_value: upper bound / cap / threshold value; null if none.
                - unit: e.g. PERCENT, YEARS, SGD_PER_BP.
                - contributing_codes: asset-class codes that feed an aggregate (EXPOSURE_LIMIT / LIQUIDITY_FLOOR).
                - target_code: snake_case code of the limited thing, e.g. high_yield,
                  aggregate_non_ig_exposure, single_corporate_issuer, liquid_assets_ratio,
                  modified_duration, portfolio_dv01.

                Respond with a single JSON object of the form:
                {"intents": [ { "rule_type": ..., "target_code": ..., "formula_key": ...,
                  "min_value": ..., "max_value": ..., "unit": ..., "contributing_codes": [...],
                  "source_chunk_id": ..., "passage_summary": ..., "extraction_confidence": ... } ]}
                """.formatted(enumList(RuleType.values()), enumList(FormulaKey.values()));
    }

    private String userPrompt(List<GuidelineChunk> chunks, List<String> knownAssetClassCodes) {
        StringBuilder sb = new StringBuilder();
        if (knownAssetClassCodes != null && !knownAssetClassCodes.isEmpty()) {
            sb.append("The holdings snapshot uses exactly these asset-class codes:\n")
                    .append(String.join(", ", knownAssetClassCodes))
                    .append("\n\nWhen a rule targets or aggregates an asset class, target_code and ")
                    .append("contributing_codes MUST be drawn from this list verbatim, so a limit and ")
                    .append("the positions it governs resolve to the same node. Rules that are not about ")
                    .append("an asset class (e.g. issuer concentration, portfolio risk metrics) use the ")
                    .append("descriptive snake_case codes from the system instructions instead.\n\n");
        }
        sb.append("Extract every rule you can find from these guideline chunks. ")
                .append("Each chunk is delimited and labelled with its chunk_id and page.\n\n");
        for (GuidelineChunk c : chunks) {
            sb.append("--- chunk_id=").append(c.chunkId())
                    .append(" page=").append(c.page()).append(" ---\n")
                    .append(c.text().strip()).append("\n\n");
        }
        return sb.toString();
    }

    // --- Parsing + allow-list validation -------------------------------------------------------

    private List<RuleIntent> parseIntents(String content, List<GuidelineChunk> chunks)
            throws Exception {
        Set<String> validChunkIds = new LinkedHashSet<>();
        for (GuidelineChunk c : chunks) {
            validChunkIds.add(c.chunkId());
        }

        JsonNode root = mapper.readTree(content);
        JsonNode intentsNode = root.has("intents") ? root.get("intents") : root;
        if (!intentsNode.isArray()) {
            throw new IllegalStateException("Expected an 'intents' array in the model output");
        }

        List<RuleIntent> intents = new ArrayList<>();
        for (JsonNode node : intentsNode) {
            RuleIntent intent = toIntent(node, validChunkIds);
            if (intent != null) {
                intents.add(intent);
            }
        }
        return intents;
    }

    /** Maps one JSON node to a validated intent, or {@code null} if it fails allow-list checks. */
    private RuleIntent toIntent(JsonNode node, Set<String> validChunkIds) {
        RuleType ruleType = parseEnum(RuleType.class, text(node, "rule_type"));
        FormulaKey formulaKey = parseEnum(FormulaKey.class, text(node, "formula_key"));
        String targetCode = text(node, "target_code");

        // Gate G1: reject anything whose type or formula is not on the trusted allow-list, and any
        // rule without a target — these can never reach a calculator.
        if (ruleType == null || formulaKey == null || targetCode == null || targetCode.isBlank()) {
            log.warn("Dropping LLM intent with off-list or incomplete fields: {}", node);
            return null;
        }

        String chunkId = text(node, "source_chunk_id");
        if (chunkId == null || !validChunkIds.contains(chunkId)) {
            // Keep the rule but mark provenance unresolved, matching the seed extractor's contract:
            // it surfaces downstream as untraceable rather than a fabricated citation.
            chunkId = "chunk_unresolved";
        }

        return new RuleIntent(
                ruleType,
                targetCode.trim(),
                formulaKey,
                decimal(node, "min_value"),
                decimal(node, "max_value"),
                textOr(node, "unit", "PERCENT"),
                stringList(node, "contributing_codes"),
                chunkId,
                textOr(node, "passage_summary", ""),
                confidence(node));
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

    private static String enumList(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : values) {
            names.add(e.name());
        }
        return String.join(", ", names);
    }
}
