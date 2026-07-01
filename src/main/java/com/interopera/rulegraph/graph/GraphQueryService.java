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

    /** True when the graph has not been built yet (no nodes). */
    public boolean isEmpty() {
        try (Session session = driver.session()) {
            return session.executeRead(tx ->
                    tx.run("MATCH (n) RETURN count(n) AS c").single().get("c").asLong() == 0);
        }
    }

    /**
     * The connected knowledge graph for visualisation: every edge and the nodes it joins. Uncited
     * guideline chunks are excluded because they take part in no relationship.
     */
    public GraphView graphView() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> buildView(tx, """
                    MATCH (a)-[r]->(b)
                    RETURN elementId(a) AS aid, labels(a)[0] AS al, properties(a) AS ap,
                           type(r) AS rt,
                           elementId(b) AS bid, labels(b)[0] AS bl, properties(b) AS bp
                    """, Map.of()));
        }
    }

    /**
     * The trace subgraph for one figure: exactly the nodes and edges that figure was computed along,
     * from the contributing positions through the rule to the guideline chunk that defines it. This is
     * the live result of the traversal the engine actually used, so the picture cannot drift from how
     * the figure was produced.
     */
    public GraphView figureSubgraph(String figureCode) {
        String cypher = TRACE_QUERIES.get(figureCode);
        if (cypher == null) {
            // Allocation figures share one query, keyed by asset-class code.
            cypher = ALLOCATION_TRACE;
        }
        String finalCypher = cypher;
        try (Session session = driver.session()) {
            return session.executeRead(tx -> buildView(tx, finalCypher, Map.of("code", figureCode)));
        }
    }

    /** Runs a query whose rows expose start/relationship/end triples and assembles a {@link GraphView}. */
    private GraphView buildView(org.neo4j.driver.TransactionContext tx, String cypher,
                                Map<String, Object> params) {
        Map<String, GraphView.Node> nodes = new java.util.LinkedHashMap<>();
        List<GraphView.Edge> edges = new java.util.ArrayList<>();
        for (var row : tx.run(cypher, params).list()) {
            String aid = row.get("aid").asString();
            String bid = row.get("bid").asString();
            nodes.computeIfAbsent(aid, k -> node(k, row.get("al").asString(), row.get("ap").asMap()));
            nodes.computeIfAbsent(bid, k -> node(k, row.get("bl").asString(), row.get("bp").asMap()));
            edges.add(new GraphView.Edge(aid, bid, row.get("rt").asString()));
        }
        return new GraphView(List.copyOf(nodes.values()), edges);
    }

    private GraphView.Node node(String id, String type, Map<String, Object> props) {
        return new GraphView.Node(id, type, displayLabel(type, props));
    }

    /** The triple-returning tail shared by every trace query. */
    private static final String TRIPLE_RETURN = """
            UNWIND rels AS r
            RETURN elementId(startNode(r)) AS aid, labels(startNode(r))[0] AS al,
                   properties(startNode(r)) AS ap, type(r) AS rt,
                   elementId(endNode(r)) AS bid, labels(endNode(r))[0] AS bl,
                   properties(endNode(r)) AS bp
            """;

    /** Allocation: positions in the class, the class, its limit, and the defining chunk. */
    private static final String ALLOCATION_TRACE = """
            MATCH (ac:AssetClass {code:$code})-[hl:HAS_LIMIT]->(lim:Limit)-[d:DEFINED_BY]->(:GuidelineChunk)
            OPTIONAL MATCH (p:Position)-[ic:IN_ASSET_CLASS]->(ac)
            WITH collect(DISTINCT hl) + collect(DISTINCT d) + collect(DISTINCT ic) AS rels
            """ + TRIPLE_RETURN;

    /** Per-figure trace queries for the non-allocation figures. */
    private static final Map<String, String> TRACE_QUERIES = Map.of(
            "aggregate_non_ig_exposure", """
                    MATCH (agg:Aggregate {code:$code})-[d:DEFINED_BY]->(:GuidelineChunk)
                    MATCH (ac:AssetClass)-[ct:CONTRIBUTES_TO]->(agg)
                    OPTIONAL MATCH (p:Position)-[ic:IN_ASSET_CLASS]->(ac)
                    WITH collect(DISTINCT d) + collect(DISTINCT ct) + collect(DISTINCT ic) AS rels
                    """ + TRIPLE_RETURN,
            "liquid_assets_ratio", """
                    MATCH (lf:LiquidityFloor {code:$code})-[d:DEFINED_BY]->(:GuidelineChunk)
                    MATCH (ac:AssetClass)-[ct:CONTRIBUTES_TO]->(lf)
                    OPTIONAL MATCH (p:Position)-[ic:IN_ASSET_CLASS]->(ac)
                    WITH collect(DISTINCT d) + collect(DISTINCT ct) + collect(DISTINCT ic) AS rels
                    """ + TRIPLE_RETURN,
            "single_corporate_issuer", """
                    MATCH (cl:ConcentrationLimit {code:$code})-[d:DEFINED_BY]->(:GuidelineChunk)
                    OPTIONAL MATCH (p:Position)-[ib:ISSUED_BY]->(:Issuer {issuer_type:'corporate'})
                    WITH collect(DISTINCT d) + collect(DISTINCT ib) AS rels
                    """ + TRIPLE_RETURN,
            "gre_issuer", """
                    MATCH (cl:ConcentrationLimit {code:$code})-[d:DEFINED_BY]->(:GuidelineChunk)
                    OPTIONAL MATCH (p:Position)-[ib:ISSUED_BY]->(i:Issuer {issuer_type:'GRE'})
                    OPTIONAL MATCH (i)-[ru:ROLLS_UP_TO]->(:ParentIssuer)
                    WITH collect(DISTINCT d) + collect(DISTINCT ib) + collect(DISTINCT ru) AS rels
                    """ + TRIPLE_RETURN,
            "modified_duration", """
                    MATCH (m:RiskMetric {code:$code})-[ht:HAS_THRESHOLD]->(t:Threshold)-[d:DEFINED_BY]->(:GuidelineChunk)
                    OPTIONAL MATCH (t)-[ob:ON_BREACH]->(a:BreachAction)-[ow:OWNED_BY]->(:Owner)
                    WITH collect(DISTINCT ht) + collect(DISTINCT d) + collect(DISTINCT ob) + collect(DISTINCT ow) AS rels
                    """ + TRIPLE_RETURN,
            "portfolio_dv01", """
                    MATCH (m:RiskMetric {code:$code})-[ht:HAS_THRESHOLD]->(t:Threshold)-[d:DEFINED_BY]->(:GuidelineChunk)
                    OPTIONAL MATCH (t)-[ob:ON_BREACH]->(a:BreachAction)-[ow:OWNED_BY]->(:Owner)
                    WITH collect(DISTINCT ht) + collect(DISTINCT d) + collect(DISTINCT ob) + collect(DISTINCT ow) AS rels
                    """ + TRIPLE_RETURN);

    /** Picks a sensible display name for a node from its properties, by type. */
    private String displayLabel(String type, Map<String, Object> props) {
        String base = baseLabel(type, props);
        // A Position carries the market value the figures sum, so we show it on the node itself: this
        // is where the audience sees each contribution that adds up to a figure's input, right on the
        // trace graph rather than only in the formula's input list.
        if ("Position".equals(type)) {
            Object mv = props.get("market_value_sgd");
            if (mv != null) {
                return base + " · " + formatSgd(mv.toString());
            }
        }
        // A BreachAction's code is the risk-metric it hangs off (e.g. "modified_duration"), so by code
        // alone it is indistinguishable from the RiskMetric and Threshold nodes for that same metric -
        // the red node hides among identically-labelled purple ones. Show its description (the actual
        // action, e.g. "PM notification within 1h"), which is unique and matches the legend.
        if ("BreachAction".equals(type)) {
            Object desc = props.get("description");
            if (desc != null) {
                return desc.toString();
            }
        }
        // A limit-type node (allocation band, exposure/concentration cap, liquidity floor, risk
        // threshold) carries the bound it enforces as a stored property. Show it on the node so
        // clicking a Limit reveals its value on the trace graph, not just its code. The number is
        // read from the graph, never computed here, so this stays a pure display concern.
        String bound = limitAnnotation(type, props);
        if (bound != null) {
            return base + " · " + bound;
        }
        return base;
    }

    /**
     * The bound a limit-type node enforces, rendered from that node's own stored properties. Returns
     * {@code null} for node types that carry no bound (e.g. {@code AssetClass}, {@code Issuer}), so
     * the caller leaves their label untouched.
     */
    static String limitAnnotation(String type, Map<String, Object> props) {
        Object unit = props.get("unit");
        switch (type) {
            // Allocation limits and risk thresholds carry a min/max band.
            case "Limit", "Threshold" -> {
                return band(props.get("min"), props.get("max"), unit);
            }
            // Exposure and concentration caps are one-sided upper bounds.
            case "Aggregate", "ConcentrationLimit" -> {
                Object cap = props.get("cap");
                return cap == null ? null : "≤ " + formatBound(cap, unit);
            }
            // A liquidity floor is a one-sided lower bound.
            case "LiquidityFloor" -> {
                Object floor = props.get("floor");
                return floor == null ? null : "≥ " + formatBound(floor, unit);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * A two-sided band when both a positive lower bound and an upper bound constrain the value,
     * otherwise the single {@code ≤}/{@code ≥} bound that applies. A zero (or absent) lower
     * bound is treated as no floor, since a "0%" minimum constrains nothing.
     */
    private static String band(Object min, Object max, Object unit) {
        boolean hasMin = isPositive(min);
        boolean hasMax = max != null;
        if (hasMin && hasMax) {
            return formatBound(min, null) + "–" + formatBound(max, unit);
        }
        if (hasMax) {
            return "≤ " + formatBound(max, unit);
        }
        if (min != null) {
            return "≥ " + formatBound(min, unit);
        }
        return null;
    }

    /** A bound value with its unit suffix, grouped and trimmed the way the figures read it. */
    private static String formatBound(Object value, Object unit) {
        String num;
        try {
            num = new java.text.DecimalFormat("#,##0.####")
                    .format(new java.math.BigDecimal(value.toString()));
        } catch (NumberFormatException e) {
            num = value.toString();
        }
        return num + unitSuffix(unit);
    }

    /** Turns a stored unit token into the suffix an auditor reads (percent, years, SGD-per-bp). */
    private static String unitSuffix(Object unit) {
        if (unit == null) {
            return "";
        }
        return switch (unit.toString()) {
            case "PERCENT" -> "%";
            case "YEARS" -> " yrs";
            case "SGD_PER_BP" -> " SGD/bp";
            default -> " " + unit;
        };
    }

    /** True when a bound is a real, positive constraint (a null or zero floor constrains nothing). */
    private static boolean isPositive(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return new java.math.BigDecimal(value.toString()).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The node's name from its identifying property, before any type-specific annotation. */
    private String baseLabel(String type, Map<String, Object> props) {
        for (String key : List.of("code", "name", "role", "instrument_id", "chunk_id")) {
            Object v = props.get(key);
            if (v != null) {
                return v.toString();
            }
        }
        return type;
    }

    /** Renders a market value the way the figures read it: grouped thousands, "S$" prefix. */
    private static String formatSgd(String raw) {
        try {
            return "S$" + new java.text.DecimalFormat("#,##0.####")
                    .format(new java.math.BigDecimal(raw));
        } catch (NumberFormatException e) {
            return raw;
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
