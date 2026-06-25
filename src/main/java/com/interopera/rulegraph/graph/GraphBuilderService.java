package com.interopera.rulegraph.graph;

import com.interopera.rulegraph.config.RuleGraphProperties;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.Position;
import com.interopera.rulegraph.domain.RuleIntent;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the single Neo4j knowledge graph from extracted rule intents, guideline chunks, and
 * holdings positions. Every node and every edge carries provenance ({@code source_doc, page,
 * chunk_id, ingested_at, confidence}); every rule terminates at the {@code GuidelineChunk} it was
 * defined by via a {@code DEFINED_BY} edge — the terminus of the {@code figure -> path -> source}
 * trace. Cypher is parameterised and {@code MERGE}-based, so a rebuild on the same inputs is
 * idempotent and the graph is stable across runs.
 */
@Service
public class GraphBuilderService {

    private final Driver driver;
    private final RuleGraphProperties props;

    /** Breach actions + owners for risk metrics (reference data from the §3.1 table). */
    private static final Map<String, String[]> BREACH = Map.of(
            "modified_duration", new String[]{"PM notification within 1h", "Portfolio Manager"},
            "portfolio_dv01", new String[]{"Risk Committee alert", "Risk Committee"});

    public GraphBuilderService(Driver driver, RuleGraphProperties props) {
        this.driver = driver;
        this.props = props;
    }

    /** @return a short summary of what was written (node/edge counts) */
    public GraphBuildResult build(List<GuidelineChunk> chunks,
                                  List<RuleIntent> intents,
                                  List<Position> positions) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                writeChunks(tx, chunks);
                for (RuleIntent intent : intents) {
                    writeRule(tx, intent);
                }
                for (Position p : positions) {
                    writePosition(tx, p);
                }
                long nodes = tx.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong();
                long edges = tx.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asLong();
                return new GraphBuildResult(nodes, edges);
            });
        }
    }

    private void writeChunks(TransactionContext tx, List<GuidelineChunk> chunks) {
        for (GuidelineChunk c : chunks) {
            Map<String, Object> params = new HashMap<>();
            params.put("chunkId", c.chunkId());
            params.put("page", c.page());
            params.put("text", c.text());
            params.put("summary", c.passageSummary());
            params.put("sourceDoc", c.provenance().sourceDoc());
            params.put("ingestedAt", c.provenance().ingestedAt().toString());
            tx.run("""
                    MERGE (g:GuidelineChunk {chunk_id: $chunkId})
                    SET g.page = $page, g.text = $text, g.passage_summary = $summary,
                        g.source_doc = $sourceDoc, g.ingested_at = $ingestedAt
                    """, params);
        }
    }

    private void writeRule(TransactionContext tx, RuleIntent intent) {
        Map<String, Object> p = ruleParams(intent);
        switch (intent.ruleType()) {
            case ALLOCATION_LIMIT -> tx.run("""
                    MERGE (ac:AssetClass {code: $code})
                    MERGE (lim:Limit {code: $code})
                      SET lim.type='ALLOCATION', lim.min=$min, lim.max=$max, lim.unit=$unit,
                          lim.formula_key=$formulaKey
                    MERGE (ac)-[hl:HAS_LIMIT]->(lim) SET hl += $prov
                    WITH lim
                    MATCH (g:GuidelineChunk {chunk_id: $chunkId})
                    MERGE (lim)-[d:DEFINED_BY]->(g) SET d += $prov
                    """, p);
            case EXPOSURE_LIMIT -> {
                tx.run("""
                        MERGE (agg:Aggregate {code: $code})
                          SET agg.cap=$max, agg.unit=$unit, agg.formula_key=$formulaKey
                        WITH agg
                        MATCH (g:GuidelineChunk {chunk_id: $chunkId})
                        MERGE (agg)-[d:DEFINED_BY]->(g) SET d += $prov
                        """, p);
                for (String contributor : intent.contributingCodes()) {
                    Map<String, Object> cp = new HashMap<>(p);
                    cp.put("contributor", contributor);
                    tx.run("""
                            MERGE (ac:AssetClass {code: $contributor})
                            MERGE (agg:Aggregate {code: $code})
                            MERGE (ac)-[c:CONTRIBUTES_TO]->(agg) SET c += $prov
                            """, cp);
                }
            }
            case CONCENTRATION_LIMIT -> tx.run("""
                    MERGE (cl:ConcentrationLimit {code: $code})
                      SET cl.cap=$max, cl.unit=$unit, cl.formula_key=$formulaKey
                    WITH cl
                    MATCH (g:GuidelineChunk {chunk_id: $chunkId})
                    MERGE (cl)-[d:DEFINED_BY]->(g) SET d += $prov
                    """, p);
            case LIQUIDITY_FLOOR -> tx.run("""
                    MERGE (lf:LiquidityFloor {code: $code})
                      SET lf.floor=$min, lf.unit=$unit, lf.formula_key=$formulaKey
                    WITH lf
                    MATCH (g:GuidelineChunk {chunk_id: $chunkId})
                    MERGE (lf)-[d:DEFINED_BY]->(g) SET d += $prov
                    """, p);
            case RISK_METRIC -> {
                tx.run("""
                        MERGE (m:RiskMetric {code: $code})
                          SET m.formula_key=$formulaKey
                        MERGE (t:Threshold {code: $code})
                          SET t.min=$min, t.max=$max, t.unit=$unit
                        MERGE (m)-[ht:HAS_THRESHOLD]->(t) SET ht += $prov
                        WITH t
                        MATCH (g:GuidelineChunk {chunk_id: $chunkId})
                        MERGE (t)-[d:DEFINED_BY]->(g) SET d += $prov
                        """, p);
                String[] breach = BREACH.get(intent.targetCode());
                if (breach != null) {
                    Map<String, Object> bp = new HashMap<>(p);
                    bp.put("action", breach[0]);
                    bp.put("owner", breach[1]);
                    tx.run("""
                            MATCH (t:Threshold {code: $code})
                            MERGE (a:BreachAction {code: $code}) SET a.description=$action
                            MERGE (o:Owner {role: $owner})
                            MERGE (t)-[ob:ON_BREACH]->(a) SET ob += $prov
                            MERGE (a)-[ow:OWNED_BY]->(o) SET ow += $prov
                            """, bp);
                }
            }
        }
    }

    private void writePosition(TransactionContext tx, Position pos) {
        Map<String, Object> p = new HashMap<>();
        p.put("instrumentId", pos.instrumentId());
        p.put("instrumentName", pos.instrumentName());
        p.put("assetCode", AssetClassCodes.toCode(pos.assetClass()));
        p.put("issuer", pos.issuerName());
        p.put("issuerType", pos.issuerType());
        p.put("rating", pos.creditRating());
        p.put("downgradedFrom", pos.downgradedFrom());
        p.put("marketValue", pos.marketValueSgd().toPlainString());
        p.put("duration", pos.modifiedDuration().toPlainString());
        p.put("prov", positionProv(pos));

        tx.run("""
                MERGE (pos:Position {instrument_id: $instrumentId})
                  SET pos.name=$instrumentName, pos.market_value_sgd=$marketValue,
                      pos.modified_duration=$duration, pos.credit_rating=$rating,
                      pos.downgraded_from=$downgradedFrom, pos.source_doc='holdings'
                MERGE (ac:AssetClass {code: $assetCode})
                MERGE (iss:Issuer {name: $issuer}) SET iss.issuer_type=$issuerType
                MERGE (pos)-[ic:IN_ASSET_CLASS]->(ac) SET ic += $prov
                MERGE (pos)-[ib:ISSUED_BY]->(iss) SET ib += $prov
                """, p);

        if (pos.hasParent()) {
            Map<String, Object> pp = new HashMap<>(p);
            pp.put("parent", pos.parentIssuer());
            tx.run("""
                    MERGE (iss:Issuer {name: $issuer})
                    MERGE (par:ParentIssuer {name: $parent})
                    MERGE (iss)-[ru:ROLLS_UP_TO]->(par) SET ru += $prov
                    """, pp);
        }
    }

    private Map<String, Object> ruleParams(RuleIntent intent) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", intent.targetCode());
        p.put("min", intent.minValue() == null ? null : intent.minValue().doubleValue());
        p.put("max", intent.maxValue() == null ? null : intent.maxValue().doubleValue());
        p.put("unit", intent.unit());
        p.put("formulaKey", intent.formulaKey().name());
        p.put("chunkId", intent.sourceChunkId());
        p.put("prov", ruleProv(intent));
        return p;
    }

    private Map<String, Object> ruleProv(RuleIntent intent) {
        Map<String, Object> prov = new HashMap<>();
        prov.put("source_doc", props.guidelinesPdf());
        prov.put("chunk_id", intent.sourceChunkId());
        prov.put("ingested_at", Instant.now().toString());
        prov.put("confidence", intent.extractionConfidence());
        return prov;
    }

    private Map<String, Object> positionProv(Position pos) {
        Map<String, Object> prov = new HashMap<>();
        prov.put("source_doc", pos.provenance().sourceDoc());
        prov.put("chunk_id", pos.provenance().chunkId());
        prov.put("ingested_at", pos.provenance().ingestedAt().toString());
        prov.put("confidence", pos.provenance().extractionConfidence());
        return prov;
    }

    /** Summary of a graph build. */
    public record GraphBuildResult(long nodeCount, long edgeCount) {
    }
}
