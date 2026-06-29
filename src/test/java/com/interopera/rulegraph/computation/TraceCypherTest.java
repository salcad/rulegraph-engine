package com.interopera.rulegraph.computation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runnable counterpart to a figure's display path. These assert the emitted Cypher parses and
 * runs (keyed nodes get their real property, unkeyed nodes match by label, recurring nodes share one
 * variable so disjoint patterns join), so a reader can paste it straight into the Neo4j browser.
 */
class TraceCypherTest {

    @Test
    void simpleChainKeysEachNodeByItsProperty() {
        String cypher = TraceCypher.trace().match()
                .node("Position")
                .rel("IN_ASSET_CLASS").node("AssetClass", "singapore_government_securities")
                .rel("HAS_LIMIT").node("Limit", "singapore_government_securities")
                .rel("DEFINED_BY").node("GuidelineChunk", "chunk_1cc8")
                .end().build();

        assertThat(cypher).isEqualTo(
                "MATCH (p:Position)-[:IN_ASSET_CLASS]->(ac:AssetClass {code: 'singapore_government_securities'})"
                        + "-[:HAS_LIMIT]->(l:Limit {code: 'singapore_government_securities'})"
                        + "-[:DEFINED_BY]->(gc:GuidelineChunk {chunk_id: 'chunk_1cc8'})\n"
                        + "RETURN p, ac, l, gc;");
    }

    @Test
    void recurringNodeReusesOneVariableSoParallelPatternsJoin() {
        // Two contributing classes rolling up to the same aggregate, then the aggregate's source.
        String cypher = TraceCypher.trace()
                .match().node("AssetClass", "corporate_bonds").rel("CONTRIBUTES_TO").node("Aggregate", "non_ig").end()
                .match().node("AssetClass", "high_yield").rel("CONTRIBUTES_TO").node("Aggregate", "non_ig").end()
                .match().node("Aggregate", "non_ig").rel("DEFINED_BY").node("GuidelineChunk", "chunk_9").end()
                .build();

        // The shared Aggregate is declared once (with its property) and referenced by variable after.
        assertThat(cypher).isEqualTo(
                "MATCH (ac:AssetClass {code: 'corporate_bonds'})-[:CONTRIBUTES_TO]->(a:Aggregate {code: 'non_ig'}),\n"
                        + "      (ac2:AssetClass {code: 'high_yield'})-[:CONTRIBUTES_TO]->(a),\n"
                        + "      (a)-[:DEFINED_BY]->(gc:GuidelineChunk {chunk_id: 'chunk_9'})\n"
                        + "RETURN ac, a, ac2, gc;");
    }

    @Test
    void unkeyedNodeMatchesByLabelAndValuesAreEscaped() {
        String cypher = TraceCypher.trace().match()
                .node("Position")
                .rel("ISSUED_BY").node("Issuer", "Moody's")
                .end().build();

        assertThat(cypher).isEqualTo(
                "MATCH (p:Position)-[:ISSUED_BY]->(i:Issuer {name: 'Moody\\'s'})\nRETURN p, i;");
    }
}
