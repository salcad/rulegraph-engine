package com.interopera.rulegraph.computation;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Graph-backed portfolio queries. Every figure's inputs are selected <em>by traversing the graph</em>
 * — the calculators sum the positions these traversals return. This is what makes traceability real
 * and "through the graph": the graph path is the mechanism that chooses which positions feed a
 * figure, not a label attached after the fact. All summation happens in {@link BigDecimal} in Java
 * (never the LLM, never floating point in the result), so figures are byte-identical across runs.
 *
 * <p>Each query is a single {@code Cypher} constant, used both to execute the read and — via the
 * matching {@code *Cypher(...)} accessor — to expose the exact traversal on the report, with its
 * parameters rendered inline for readability. The query a figure shows is therefore the query that
 * actually produced its number.
 */
@Component
public class GraphPortfolioQueries {

    static final String NAV_CYPHER =
            "MATCH (p:Position) RETURN p.market_value_sgd AS v";

    static final String MV_IN_CLASSES_CYPHER = """
            MATCH (p:Position)-[:IN_ASSET_CLASS]->(ac:AssetClass)
            WHERE ac.code IN $codes
            RETURN p.market_value_sgd AS v""";

    static final String FALLEN_ANGEL_CYPHER = """
            MATCH (p:Position)-[:IN_ASSET_CLASS]->(ac:AssetClass)
            WHERE p.downgraded_from IS NOT NULL AND p.downgraded_from <> ''
              AND NOT ac.code IN $codes
            RETURN p.market_value_sgd AS v""";

    static final String SUM_BY_ISSUER_CYPHER = """
            MATCH (p:Position)-[:ISSUED_BY]->(i:Issuer {issuer_type: $t})
            RETURN i.name AS k, p.market_value_sgd AS v ORDER BY i.name""";

    static final String SUM_BY_PARENT_CYPHER = """
            MATCH (p:Position)-[:ISSUED_BY]->(i:Issuer {issuer_type: $t})-[:ROLLS_UP_TO]->(par:ParentIssuer)
            RETURN par.name AS k, p.market_value_sgd AS v ORDER BY par.name""";

    static final String DURATION_WEIGHTED_CYPHER = """
            MATCH (p:Position)
            RETURN p.market_value_sgd AS mv, p.modified_duration AS dur""";

    private final Driver driver;

    public GraphPortfolioQueries(Driver driver) {
        this.driver = driver;
    }

    /** Net asset value = sum of all position market values. */
    public BigDecimal nav() {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> sum(tx.run(NAV_CYPHER).list(r -> r.get("v").asString())));
        }
    }

    /** Sum of market values for positions in the given asset-class codes (traverses IN_ASSET_CLASS). */
    public BigDecimal marketValueInAssetClasses(List<String> codes) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> sum(tx.run(MV_IN_CLASSES_CYPHER,
                    Map.of("codes", codes)).list(r -> r.get("v").asString())));
        }
    }

    /**
     * Sum of market values for fallen-angel positions (a {@code downgraded_from} is present) whose
     * asset class is NOT already in {@code alreadyCounted}. Used by Firm B's non-IG convention so a
     * downgraded IG-corporate counts toward non-IG without double-counting the high-yield bucket.
     */
    public BigDecimal fallenAngelMarketValueOutside(List<String> alreadyCounted) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> sum(tx.run(FALLEN_ANGEL_CYPHER,
                    Map.of("codes", alreadyCounted)).list(r -> r.get("v").asString())));
        }
    }

    /** Per-issuer market-value totals for a given issuer type (e.g. {@code corporate}, {@code GRE}). */
    public Map<String, BigDecimal> sumByIssuer(String issuerType) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> grouped(tx.run(SUM_BY_ISSUER_CYPHER, Map.of("t", issuerType))));
        }
    }

    /** Per-parent-issuer totals for GRE positions (traverses ISSUED_BY then ROLLS_UP_TO). */
    public Map<String, BigDecimal> sumByParentIssuer(String issuerType) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> grouped(tx.run(SUM_BY_PARENT_CYPHER, Map.of("t", issuerType))));
        }
    }

    /** Σ(market_value × modified_duration) over all positions — numerator for duration and DV01. */
    public BigDecimal durationWeightedSum() {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> {
                BigDecimal total = BigDecimal.ZERO;
                var rows = tx.run(DURATION_WEIGHTED_CYPHER).list();
                for (var row : rows) {
                    BigDecimal mv = new BigDecimal(row.get("mv").asString());
                    BigDecimal dur = new BigDecimal(row.get("dur").asString());
                    total = total.add(mv.multiply(dur));
                }
                return total;
            });
        }
    }

    // --- Query text for the report (exact traversal that produced each input, params inlined) -----

    public String navCypher() {
        return NAV_CYPHER;
    }

    public String marketValueInAssetClassesCypher(List<String> codes) {
        return MV_IN_CLASSES_CYPHER.replace("$codes", renderList(codes));
    }

    public String fallenAngelCypher(List<String> alreadyCounted) {
        return FALLEN_ANGEL_CYPHER.replace("$codes", renderList(alreadyCounted));
    }

    public String sumByIssuerCypher(String issuerType) {
        return SUM_BY_ISSUER_CYPHER.replace("$t", "'" + issuerType + "'");
    }

    public String sumByParentIssuerCypher(String issuerType) {
        return SUM_BY_PARENT_CYPHER.replace("$t", "'" + issuerType + "'");
    }

    public String durationWeightedSumCypher() {
        return DURATION_WEIGHTED_CYPHER;
    }

    private static String renderList(List<String> codes) {
        return codes.stream().map(c -> "'" + c + "'").collect(Collectors.joining(", ", "[", "]"));
    }

    private static BigDecimal sum(List<String> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (String v : values) {
            total = total.add(new BigDecimal(v));
        }
        return total;
    }

    private static Map<String, BigDecimal> grouped(org.neo4j.driver.Result result) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (var row : result.list()) {
            String k = row.get("k").asString();
            map.merge(k, new BigDecimal(row.get("v").asString()), BigDecimal::add);
        }
        return map;
    }
}
