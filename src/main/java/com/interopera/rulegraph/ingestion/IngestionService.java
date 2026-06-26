package com.interopera.rulegraph.ingestion;

import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.Position;
import com.interopera.rulegraph.domain.RuleIntent;
import com.interopera.rulegraph.graph.AssetClassCodes;
import com.interopera.rulegraph.graph.GraphBuilderService;
import com.interopera.rulegraph.graph.extraction.RuleExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates Phase 2: parse the guidelines PDF and holdings CSV, extract rule intents, and build
 * the knowledge graph. Returns a summary so the run can be reported and (later) audited.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GuidelinePdfParser pdfParser;
    private final HoldingsCsvParser csvParser;
    private final RuleExtractor ruleExtractor;
    private final GraphBuilderService graphBuilder;
    private final AssetClassCodes assetClassCodes;

    public IngestionService(GuidelinePdfParser pdfParser,
                            HoldingsCsvParser csvParser,
                            RuleExtractor ruleExtractor,
                            GraphBuilderService graphBuilder,
                            AssetClassCodes assetClassCodes) {
        this.pdfParser = pdfParser;
        this.csvParser = csvParser;
        this.ruleExtractor = ruleExtractor;
        this.graphBuilder = graphBuilder;
        this.assetClassCodes = assetClassCodes;
    }

    public IngestionResult ingest() {
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

        List<RuleIntent> intents = ruleExtractor.extract(chunks, assetClassVocabulary);
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

        return new IngestionResult(chunks.size(), positions.size(), intents.size(),
                graph.nodeCount(), graph.edgeCount(), unresolved);
    }

    /** Summary of an ingestion run. */
    public record IngestionResult(int chunks, int positions, int ruleIntents,
                                  long graphNodes, long graphEdges, long unresolvedRules) {
    }
}
