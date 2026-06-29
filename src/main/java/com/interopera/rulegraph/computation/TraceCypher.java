package com.interopera.rulegraph.computation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds runnable Cypher for a figure's trace, as the executable companion to the human-readable
 * graph path on {@link com.interopera.rulegraph.domain.FigureResult}.
 *
 * <p>The display path (e.g. {@code (AssetClass:singapore_government_securities)}) overloads the
 * {@code :label} slot to carry a <em>property value</em>, which is not valid Cypher — pasting it
 * into the Neo4j browser fails to parse. This turns the same traversal into a
 * {@code MATCH ... RETURN} that copy-pastes and runs. The label -> property-key mapping lives here,
 * once, mirroring the keys {@link com.interopera.rulegraph.graph.GraphBuilderService} writes.
 *
 * <p>Usage is fluent; each {@link #match()} starts a pattern, and multiple patterns (the {@code ,}
 * parallel hops and {@code ||} disjoint subgraphs of the display path) are emitted comma-separated
 * in one {@code MATCH}. A node given a value is matched by its keyed property; an unkeyed node (e.g.
 * {@code Position} in the display path) is matched by label alone. The same node — same label and
 * value — reuses one variable so the patterns join into a connected graph.
 */
public final class TraceCypher {

    /** label -> the property {@code GraphBuilderService} keys that node on. */
    private static final Map<String, String> KEY = Map.ofEntries(
            Map.entry("Position", "instrument_id"),
            Map.entry("AssetClass", "code"),
            Map.entry("Limit", "code"),
            Map.entry("Aggregate", "code"),
            Map.entry("ConcentrationLimit", "code"),
            Map.entry("LiquidityFloor", "code"),
            Map.entry("RiskMetric", "code"),
            Map.entry("Threshold", "code"),
            Map.entry("GuidelineChunk", "chunk_id"),
            Map.entry("Issuer", "name"),
            Map.entry("ParentIssuer", "name"));

    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, String> varByNode = new HashMap<>(); // "Label|value" -> variable
    private final Map<String, Integer> baseSeq = new HashMap<>();   // variable base -> count assigned
    private final List<String> returnOrder = new ArrayList<>();      // distinct vars, first-seen order

    private TraceCypher() {
    }

    public static TraceCypher trace() {
        return new TraceCypher();
    }

    /** Start a new pattern (a {@code ,}/{@code ||} segment of the display path). */
    public Segment match() {
        Segment s = new Segment();
        segments.add(s);
        return s;
    }

    /** A single connected pattern: alternating nodes and relationship types. */
    public final class Segment {
        private final List<Node> nodes = new ArrayList<>();
        private final List<String> rels = new ArrayList<>(); // rels.get(i) connects nodes i and i+1

        /** Unkeyed node, matched by label alone (e.g. every {@code Position}). */
        public Segment node(String label) {
            return node(label, null);
        }

        /** Node matched by its keyed property; reuses a variable if the same node recurs. */
        public Segment node(String label, String value) {
            nodes.add(resolve(label, value));
            return this;
        }

        public Segment rel(String type) {
            rels.add(type);
            return this;
        }

        /** Finish this pattern and return to the trace to add more patterns or build. */
        public TraceCypher end() {
            return TraceCypher.this;
        }
    }

    private record Node(String label, String value, String var) {
    }

    private Node resolve(String label, String value) {
        if (value != null) {
            String id = label + "|" + value;
            String existing = varByNode.get(id);
            if (existing != null) {
                return new Node(label, value, existing); // same node -> same variable, joins patterns
            }
            String var = nextVar(label);
            varByNode.put(id, var);
            returnOrder.add(var);
            return new Node(label, value, var);
        }
        String var = nextVar(label);
        returnOrder.add(var);
        return new Node(label, value, var);
    }

    /** A short, readable variable from the label's capitals (AssetClass -> ac, GuidelineChunk -> gc). */
    private String nextVar(String label) {
        String base = label.chars()
                .filter(Character::isUpperCase)
                .collect(StringBuilder::new, (sb, c) -> sb.append((char) Character.toLowerCase(c)), StringBuilder::append)
                .toString();
        if (base.isEmpty()) {
            base = label.toLowerCase();
        }
        int n = baseSeq.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + n;
    }

    /** Render the {@code MATCH ... RETURN ...;} statement. */
    public String build() {
        Set<String> declared = new HashSet<>(); // first textual mention of a var carries label + props
        String patterns = segments.stream()
                .map(seg -> render(seg, declared))
                .collect(Collectors.joining(",\n      "));
        return "MATCH " + patterns + "\nRETURN " + String.join(", ", returnOrder) + ";";
    }

    private String render(Segment seg, Set<String> declared) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.nodes.size(); i++) {
            sb.append(renderNode(seg.nodes.get(i), declared));
            if (i < seg.rels.size()) {
                sb.append("-[:").append(seg.rels.get(i)).append("]->");
            }
        }
        return sb.toString();
    }

    private String renderNode(Node node, Set<String> declared) {
        if (!declared.add(node.var())) {
            return "(" + node.var() + ")"; // already introduced; reference by variable only
        }
        if (node.value() == null) {
            return "(" + node.var() + ":" + node.label() + ")";
        }
        String key = KEY.get(node.label());
        if (key == null) {
            throw new IllegalArgumentException("No property key mapped for label: " + node.label());
        }
        return "(" + node.var() + ":" + node.label() + " {" + key + ": '" + escape(node.value()) + "'})";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
