package com.interopera.rulegraph.api;

import com.interopera.rulegraph.graph.GraphQueryService;
import com.interopera.rulegraph.graph.GraphView;
import com.interopera.rulegraph.ingestion.IngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web API for the knowledge graph view used by the viewer's Graph tab. Returns the connected
 * rule-and-position graph so it can be drawn in the browser.
 */
@RestController
@RequestMapping("/rulegraph-api")
public class GraphController {

    private final GraphQueryService graphQueryService;
    private final IngestionService ingestionService;

    public GraphController(GraphQueryService graphQueryService, IngestionService ingestionService) {
        this.graphQueryService = graphQueryService;
        this.ingestionService = ingestionService;
    }

    /** The current knowledge graph. If it has not been built yet, the materials are ingested first. */
    @GetMapping("/graph")
    public GraphView graph() {
        ensureBuilt();
        return graphQueryService.graphView();
    }

    /**
     * The trace subgraph for one figure: the live result of the traversal that figure was computed
     * along, from its contributing positions through the rule to the guideline chunk that defines it.
     */
    @GetMapping("/figure-graph")
    public GraphView figureGraph(@RequestParam String figure) {
        ensureBuilt();
        return graphQueryService.figureSubgraph(figure);
    }

    private void ensureBuilt() {
        if (graphQueryService.isEmpty()) {
            ingestionService.ingest();
        }
    }
}
