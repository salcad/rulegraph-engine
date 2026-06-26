package com.interopera.rulegraph.cli;

import com.interopera.rulegraph.graph.GraphQueryService;
import com.interopera.rulegraph.ingestion.IngestionService;
import com.interopera.rulegraph.ingestion.IngestionService.IngestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Command-line entry for Phase 2. Runs only when the application is started with the {@code ingest}
 * argument, so ordinary startup and tests do not require a live Neo4j instance.
 *
 * <pre>  java -jar rulegraph-engine.jar ingest  </pre>
 *
 * It ingests the sample materials into the graph, then runs two multi-hop traversals to prove the
 * graph is queryable without re-reading the source documents.
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestionService ingestionService;
    private final GraphQueryService queryService;
    private final org.springframework.context.ApplicationContext context;

    public IngestRunner(IngestionService ingestionService, GraphQueryService queryService,
                        org.springframework.context.ApplicationContext context) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains("ingest")) {
            return;
        }

        IngestionResult result = ingestionService.ingest();
        log.info("=== Ingestion complete ===");
        log.info("chunks={}, positions={}, ruleIntents={}, graphNodes={}, graphEdges={}, unresolvedRules={}",
                result.chunks(), result.positions(), result.ruleIntents(),
                result.graphNodes(), result.graphEdges(), result.unresolvedRules());

        // Multi-hop demo 1: breach action + owner for portfolio duration.
        GraphQueryService.BreachAnswer breach = queryService.breachActionFor("modified_duration");
        log.info("=== Multi-hop: duration breach action ===");
        log.info("{}", breach);

        // Multi-hop demo 2: which asset classes contribute to the non-IG aggregate, and its source.
        List<String> contributors = queryService.contributorsTo("aggregate_non_ig_exposure");
        log.info("=== Multi-hop: non-IG aggregate contributors ===");
        log.info("contributors={}", contributors);
        log.info("source={}", queryService.sourceChunkFor("aggregate_non_ig_exposure"));

        // One-shot command: exit so the web server does not stay up.
        System.exit(org.springframework.boot.SpringApplication.exit(context, () -> 0));
    }
}
