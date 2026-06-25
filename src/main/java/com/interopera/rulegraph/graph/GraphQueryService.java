package com.interopera.rulegraph.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Multi-hop traversal queries over the knowledge graph. These demonstrate that questions are
 * answered by traversing the graph rather than re-reading the source document (Phase 2 requirement),
 * and they are the same traversals the computation layer will use to resolve a figure to its rule
 * and source chunk.
 */
@Service
public class GraphQueryService {

    private final Driver driver;

    public GraphQueryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Answers "what is the breach action if portfolio duration exceeds its limit, and who is
     * notified?" by traversing {@code RiskMetric -> Threshold -> BreachAction -> Owner}.
     */
    public BreachAnswer breachActionFor(String riskMetricCode) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:RiskMetric {code: $code})-[:HAS_THRESHOLD]->(t:Threshold)
                              -[:ON_BREACH]->(a:BreachAction)-[:OWNED_BY]->(o:Owner)
                        RETURN t.min AS min, t.max AS max, t.unit AS unit,
                               a.description AS action, o.role AS owner
                        """, Map.of("code", riskMetricCode));
                if (!result.hasNext()) {
                    return null;
                }
                var r = result.single();
                return new BreachAnswer(
                        riskMetricCode, r.get("min").asObject(), r.get("max").asObject(),
                        r.get("unit").asString(), r.get("action").asString(), r.get("owner").asString());
            });
        }
    }

    /** Asset-class codes that contribute to an aggregate (e.g. non-IG = high_yield + structured_credit). */
    public List<String> contributorsTo(String aggregateCode) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (ac:AssetClass)-[:CONTRIBUTES_TO]->(agg:Aggregate {code: $code})
                    RETURN ac.code AS code ORDER BY ac.code
                    """, Map.of("code", aggregateCode))
                    .list(row -> row.get("code").asString()));
        }
    }

    /** The source chunk that defines a given limit/aggregate/threshold code (the trace terminus). */
    public Map<String, Object> sourceChunkFor(String code) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (rule {code: $code})-[:DEFINED_BY]->(g:GuidelineChunk)
                        RETURN g.chunk_id AS chunkId, g.page AS page,
                               g.passage_summary AS summary, g.source_doc AS sourceDoc
                        LIMIT 1
                        """, Map.of("code", code));
                return result.hasNext() ? result.single().asMap() : Map.of();
            });
        }
    }

    /** Result of the duration-breach traversal. */
    public record BreachAnswer(String metric, Object min, Object max, String unit,
                               String action, String owner) {
    }
}
