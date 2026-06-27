package com.interopera.rulegraph.graph.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>Both this and {@link SeedRuleExtractor} are always registered as beans; the active one is chosen
 * per run (see {@link ExtractorMode}) rather than by a startup-time condition, so the report viewer
 * can switch between hardcoded and LLM extraction without a restart.
 */
@Component
public class LlmRuleExtractor implements RuleExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmRuleExtractor.class);

    private final LlmProperties props;
    private final SeedRuleExtractor fallback;
    private final ObjectMapper mapper;
    private final RuleIntentJsonMapper intentMapper;
    private final HttpClient httpClient;

    public LlmRuleExtractor(LlmProperties props, SeedRuleExtractor fallback, ObjectMapper mapper,
                            RuleIntentJsonMapper intentMapper) {
        this.props = props;
        this.fallback = fallback;
        this.mapper = mapper;
        this.intentMapper = intentMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .build();
    }

    @Override
    public List<RuleIntent> extract(List<GuidelineChunk> chunks, List<String> knownAssetClassCodes) {
        return extractWithExchange(chunks, knownAssetClassCodes).intents();
    }

    /**
     * Like {@link #extract}, but also returns the verbatim prompt sent to the model and the raw reply
     * it returned (or the reason extraction fell back to the seed extractor), so the report viewer can
     * show the operator exactly what was asked and answered when they pick the LLM extractor.
     */
    public Extraction extractWithExchange(List<GuidelineChunk> chunks,
                                          List<String> knownAssetClassCodes) {
        String systemPrompt = systemPrompt();
        String userPrompt = userPrompt(chunks, knownAssetClassCodes);

        if (!props.hasApiKey()) {
            log.warn("rulegraph.llm.enabled=true but no API key is set — falling back to the "
                    + "deterministic seed extractor. Set OPENROUTER_API_KEY to use the LLM.");
            return new Extraction(fallback.extract(chunks, knownAssetClassCodes),
                    new LlmExchange(props.model(), systemPrompt, userPrompt, "", true,
                            "No API key is set, so no request was made. Fell back to the "
                                    + "deterministic seed extractor. Set OPENROUTER_API_KEY to use "
                                    + "the LLM.", fallback.rawJson()));
        }
        try {
            String content = callModel(systemPrompt, userPrompt);
            List<RuleIntent> intents = intentMapper.map(content, chunks);
            if (intents.isEmpty()) {
                log.warn("LLM returned no usable rule intents — falling back to the seed extractor");
                return new Extraction(fallback.extract(chunks, knownAssetClassCodes),
                        new LlmExchange(props.model(), systemPrompt, userPrompt, content, true,
                                "The model returned no usable rule intents. Fell back to the seed "
                                        + "extractor.", fallback.rawJson()));
            }
            log.info("LLM extractor produced {} rule intent(s) from {} chunks via model {}",
                    intents.size(), chunks.size(), props.model());
            return new Extraction(intents,
                    new LlmExchange(props.model(), systemPrompt, userPrompt, content, false, null, null));
        } catch (Exception e) {
            log.warn("LLM extraction failed ({}: {}) — falling back to the seed extractor",
                    e.getClass().getSimpleName(), e.getMessage());
            return new Extraction(fallback.extract(chunks, knownAssetClassCodes),
                    new LlmExchange(props.model(), systemPrompt, userPrompt, "", true,
                            "LLM extraction failed (" + e.getClass().getSimpleName() + ": "
                                    + e.getMessage() + "). Fell back to the seed extractor.",
                            fallback.rawJson()));
        }
    }

    /** Intents plus the record of the prompt/reply exchange that produced them. */
    public record Extraction(List<RuleIntent> intents, LlmExchange exchange) {
    }

    /**
     * The verbatim prompt sent to the model and the reply it returned, for display in the viewer.
     * {@code fellBack} is true when the seed extractor produced the intents instead (no key, an error,
     * or an empty result); {@code note} then explains why and {@code seedRules} carries the raw
     * {@code seed_rules.json} actually used, so the viewer can show the cached rule set. {@code seedRules}
     * is null on a successful LLM run. The API key is never included.
     */
    public record LlmExchange(String model, String systemPrompt, String userPrompt, String reply,
                              boolean fellBack, String note,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String seedRules) {
    }

    // --- OpenRouter call -----------------------------------------------------------------------

    private String callModel(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

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
                - Emit AT MOST ONE rule per target_code. If the text states more than one threshold for
                  the same limited thing (for example a normal-conditions and a stress-conditions
                  liquidity floor), emit only the standard / normal-conditions rule and ignore the
                  conditional variant — do not emit a second rule with the same target_code.
                - Group concentration of government-related entities (GREs) uses target_code
                  "gre_issuer" with formula_key GROUP_CONCENTRATION (it rolls issuers up to their parent
                  group). Use ISSUER_CONCENTRATION only for a single, ungrouped issuer.

                Field meaning:
                - min_value: lower bound of an allocation band or a floor; null if none.
                - max_value: upper bound / cap / threshold value; null if none.
                - unit: e.g. PERCENT, YEARS, SGD_PER_BP.
                - contributing_codes: asset-class codes that feed an aggregate (EXPOSURE_LIMIT / LIQUIDITY_FLOOR).
                - target_code: snake_case code of the limited thing, e.g. high_yield,
                  aggregate_non_ig_exposure, single_corporate_issuer, gre_issuer, liquid_assets_ratio,
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

    // --- allow-list for the system prompt ------------------------------------------------------

    private static String enumList(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : values) {
            names.add(e.name());
        }
        return String.join(", ", names);
    }
}
