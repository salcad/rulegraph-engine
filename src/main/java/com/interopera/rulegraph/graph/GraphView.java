package com.interopera.rulegraph.graph;

import java.util.List;

/**
 * A view of the knowledge graph for visualisation: the connected nodes and the edges between them.
 * Orphan guideline chunks (lines of the source document that no rule cites) are excluded, so the
 * view shows the meaningful rule-and-position graph rather than every parsed line.
 */
public record GraphView(List<Node> nodes, List<Edge> edges) {

    /**
     * @param id    stable node identifier
     * @param type  node label, e.g. {@code AssetClass}, {@code Position}, {@code GuidelineChunk}
     * @param label human-readable name for display
     */
    public record Node(String id, String type, String label) {
    }

    /**
     * @param source source node id
     * @param target target node id
     * @param type   relationship type, e.g. {@code CONTRIBUTES_TO}
     */
    public record Edge(String source, String target, String type) {
    }
}
