package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.Citation;
import com.interopera.rulegraph.domain.FormulaKey;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves every rule in the graph into a {@link ResolvedRule}, by traversing from each rule node to
 * its bounds, contributors, and {@code DEFINED_BY} source chunk. The computation layer iterates the
 * result of this resolver — so the set of figures, their limits, and their citations are all
 * graph-derived rather than hard-coded.
 */
@Component
public class GraphRuleResolver {

    private final Driver driver;

    public GraphRuleResolver(Driver driver) {
        this.driver = driver;
    }

    public List<ResolvedRule> resolveAll() {
        List<ResolvedRule> rules = new ArrayList<>();
        try (Session s = driver.session()) {
            s.executeRead(tx -> {
                // Limits, aggregates, concentration caps, liquidity floors: formula_key on the node,
                // DEFINED_BY straight to the chunk; optional CONTRIBUTES_TO for aggregate/liquidity.
                tx.run("""
                        MATCH (n)-[:DEFINED_BY]->(g:GuidelineChunk)
                        WHERE n.formula_key IS NOT NULL
                        OPTIONAL MATCH (c:AssetClass)-[:CONTRIBUTES_TO]->(n)
                        RETURN n.code AS code, n.formula_key AS fk,
                               coalesce(n.min, n.floor) AS minB,
                               coalesce(n.max, n.cap) AS maxB,
                               n.unit AS unit,
                               g.chunk_id AS chunkId, g.page AS page,
                               g.passage_summary AS summary, g.source_doc AS doc,
                               [x IN collect(c.code) WHERE x IS NOT NULL] AS contributors
                        ORDER BY code
                        """).list().forEach(r -> rules.add(toRule(
                        r.get("code").asString(), r.get("fk").asString(),
                        r.get("minB"), r.get("maxB"), r.get("unit").asString(),
                        r.get("contributors").asList(Value::asString),
                        r.get("doc").asString(), r.get("page").asInt(),
                        r.get("chunkId").asString(), r.get("summary").asString())));

                // Risk metrics: formula_key on the metric, threshold bounds + DEFINED_BY on the threshold.
                tx.run("""
                        MATCH (m:RiskMetric)-[:HAS_THRESHOLD]->(t:Threshold)-[:DEFINED_BY]->(g:GuidelineChunk)
                        RETURN m.code AS code, m.formula_key AS fk,
                               t.min AS minB, t.max AS maxB, t.unit AS unit,
                               g.chunk_id AS chunkId, g.page AS page,
                               g.passage_summary AS summary, g.source_doc AS doc
                        ORDER BY code
                        """).list().forEach(r -> rules.add(toRule(
                        r.get("code").asString(), r.get("fk").asString(),
                        r.get("minB"), r.get("maxB"), r.get("unit").asString(),
                        List.of(),
                        r.get("doc").asString(), r.get("page").asInt(),
                        r.get("chunkId").asString(), r.get("summary").asString())));
                return null;
            });
        }
        return rules;
    }

    private ResolvedRule toRule(String code, String fk, Value minB, Value maxB, String unit,
                               List<String> contributors, String doc, int page,
                               String chunkId, String summary) {
        return new ResolvedRule(
                code,
                FormulaKey.valueOf(fk),
                bd(minB),
                bd(maxB),
                unit,
                contributors,
                new Citation(doc, page, chunkId, summary));
    }

    private static BigDecimal bd(Value v) {
        return v == null || v.isNull() ? null : BigDecimal.valueOf(v.asDouble());
    }
}
