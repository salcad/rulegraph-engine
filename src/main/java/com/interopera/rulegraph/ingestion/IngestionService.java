package com.interopera.rulegraph.ingestion;

import com.interopera.rulegraph.config.LlmProperties;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.Position;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.graph.AssetClassCodes;
import com.interopera.rulegraph.graph.GraphBuilderService;
import com.interopera.rulegraph.graph.extraction.ExtractorMode;
import com.interopera.rulegraph.graph.extraction.LlmRuleExtractor;
import com.interopera.rulegraph.graph.extraction.SeedRuleExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates Phase 2: parse the guidelines PDF and holdings CSV, extract rule intents, and build
 * the knowledge graph. Returns a summary so the run can be reported and (later) audited.
 *
 * <p>Both rule extractors are injected; which one runs is chosen per call via {@link ExtractorMode}
 * ({@code SEED} = hardcoded baseline, {@code LLM} = model-backed). When no mode is given, the default
 * follows the {@code rulegraph.llm.enabled} configuration flag, preserving the previous behaviour.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GuidelinePdfParser pdfParser;
    private final HoldingsCsvParser csvParser;
    private final SeedRuleExtractor seedExtractor;
    private final LlmRuleExtractor llmExtractor;
    private final GraphBuilderService graphBuilder;
    private final AssetClassCodes assetClassCodes;
    private final ExtractorMode defaultMode;

    /** The most recent build, reused while the requested extractor matches (see {@link #ingest}). */
    private IngestionResult lastResult;

    public IngestionService(GuidelinePdfParser pdfParser,
                            HoldingsCsvParser csvParser,
                            SeedRuleExtractor seedExtractor,
                            LlmRuleExtractor llmExtractor,
                            GraphBuilderService graphBuilder,
                            AssetClassCodes assetClassCodes,
                            LlmProperties llmProperties) {
        this.pdfParser = pdfParser;
        this.csvParser = csvParser;
        this.seedExtractor = seedExtractor;
        this.llmExtractor = llmExtractor;
        this.graphBuilder = graphBuilder;
        this.assetClassCodes = assetClassCodes;
        this.defaultMode = llmProperties.enabled() ? ExtractorMode.LLM : ExtractorMode.SEED;
    }

    /** The mode used when a run does not specify one (driven by {@code rulegraph.llm.enabled}). */
    public ExtractorMode defaultMode() {
        return defaultMode;
    }

    /** Ingests using the configured default extractor. */
    public IngestionResult ingest() {
        return ingest(null);
    }

    /**
     * Ingests using the given extractor mode; a {@code null} mode falls back to {@link #defaultMode()}.
     */
    public IngestionResult ingest(ExtractorMode mode) {
        ExtractorMode effective = mode == null ? defaultMode : mode;

        // The graph (guideline chunks, extracted rules, holdings) does not depend on the firm — only
        // the per-firm figure computation does. So once it is built for an extractor we reuse it:
        // switching firm, or re-requesting the same extractor, does not re-parse the PDF or call the
        // LLM again. Switching extractor (seed <-> llm) rebuilds. All ingest paths run under the
        // ReportService monitor, so this cache needs no further synchronisation.
        if (lastResult != null && lastResult.extractor() == effective) {
            log.info("Reusing the graph already built with the {} rule extractor (no re-extraction)",
                    effective);
            return lastResult.reused();
        }

        log.info("Ingesting with {} rule extractor", effective);

        List<GuidelineChunk> chunks = pdfParser.parse();
        log.info("Parsed {} guideline chunks", chunks.size());

        List<Position> positions = csvParser.parse();
        log.info("Parsed {} holdings positions", positions.size());

        // The asset-class vocabulary actually present in the holdings, so an LLM-backed extractor
        // emits limit codes that resolve to the same nodes as the positions they govern.
        List<String> assetClassVocabulary = positions.stream()
                .map(p -> assetClassCodes.toCode(p.assetClass()))
                .distinct()
                .sorted()
                .toList();

        // For an LLM run, capture the prompt/reply exchange so the viewer can show it; the seed
        // extractor makes no model call and has nothing to show.
        List<RuleIntent> intents;
        LlmRuleExtractor.LlmExchange llmExchange;
        if (effective == ExtractorMode.LLM) {
            LlmRuleExtractor.Extraction extraction =
                    llmExtractor.extractWithExchange(chunks, assetClassVocabulary);
            intents = extraction.intents();
            llmExchange = extraction.exchange();
        } else {
            intents = seedExtractor.extract(chunks, assetClassVocabulary);
            llmExchange = null;
        }
        log.info("Extracted {} rule intents", intents.size());

        long unresolved = intents.stream()
                .filter(i -> "chunk_unresolved".equals(i.sourceChunkId()))
                .count();
        if (unresolved > 0) {
            log.warn("{} rule intent(s) could not be bound to a source chunk — these will surface "
                    + "as untraceable downstream rather than emitting a fabricated citation", unresolved);
        }

        GraphBuilderService.GraphBuildResult graph =
                graphBuilder.build(chunks, intents, positions);
        log.info("Graph built: {} nodes, {} edges", graph.nodeCount(), graph.edgeCount());

        IngestionResult result = new IngestionResult(effective, chunks.size(), positions.size(),
                intents.size(), graph.nodeCount(), graph.edgeCount(), unresolved, llmExchange, true);

        // Cache the built graph for reuse on a firm switch or a repeated request — but never cache a
        // fallback. A fallback means the LLM did not really run (no or invalid key, or an unusable
        // reply): the next request should attempt the LLM again — re-showing the warning while it
        // keeps failing, and picking up a fixed key once it works — rather than silently serving the
        // seed-based graph from cache as though an LLM run had produced it.
        boolean fellBack = llmExchange != null && llmExchange.fellBack();
        if (!fellBack) {
            lastResult = result;
        }
        return result;
    }

    /**
     * Summary of an ingestion run. {@code llmExchange} carries the prompt/reply exchange for an LLM
     * run and is {@code null} for a seed run (which makes no model call). {@code freshlyBuilt} is true
     * when this call rebuilt the graph, and false when an existing graph was reused from cache (e.g. a
     * firm switch) — so callers can tell a real LLM execution from a cache hit.
     */
    public record IngestionResult(ExtractorMode extractor, int chunks, int positions, int ruleIntents,
                                  long graphNodes, long graphEdges, long unresolvedRules,
                                  LlmRuleExtractor.LlmExchange llmExchange, boolean freshlyBuilt) {
        /** A copy of this result marked as reused from cache (no extraction happened this call). */
        IngestionResult reused() {
            return new IngestionResult(extractor, chunks, positions, ruleIntents, graphNodes,
                    graphEdges, unresolvedRules, llmExchange, false);
        }
    }
}
