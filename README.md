# rulegraph-engine

Backend for **RuleGraph** — audit-grade portfolio compliance reporting with graph-traced,
deterministic figures. Spring Boot (Java 21) + Neo4j.

Design docs: [`../rulegraph-docs/claude/`](../rulegraph-docs/claude/) (solution overview, flow &
audit events, architecture, RFC).

## Status

| Phase | Scope | State |
|-------|-------|-------|
| 2 | Ingest guidelines PDF + holdings CSV into one Neo4j graph with provenance; multi-hop queryable | ✅ working |
| 3 | Deterministic computation engine + formula registry + traceable figures | next |
| 4 | Firm A / Firm B reconfiguration by config | planned |
| 5 | Reconciliation + LLM-firewall evaluation; append-only audit log | planned |

## Requirements

- JDK 21
- Docker (for Neo4j)
- Maven 3.6+

## Run Phase 2 (ingestion → graph)

```bash
# 1. Start Neo4j
docker compose up -d

# 2. Build
mvn -DskipTests package

# 3. Ingest the sample materials into the graph and run the multi-hop demo
NEO4J_PASSWORD=password123 java -jar target/rulegraph-engine-0.1.0.jar ingest
```

The `ingest` run parses the PDF + CSV, extracts rule intents, builds the graph, and prints two
multi-hop traversals (duration breach action + owner; non-IG aggregate contributors + source chunk).
Without the `ingest` argument the app starts normally and does nothing else (no DB calls).

Browse the graph at <http://localhost:7474> (user `neo4j`, password `password123`).

### Configuration

`src/main/resources/application.yml` (override via env):

| Property | Env | Default |
|----------|-----|---------|
| `rulegraph.sample-docs-path` | `RULEGRAPH_SAMPLE_DOCS` | `…/rulegraph-docs/sample_docs/sample_docs` |
| `spring.neo4j.uri` | `NEO4J_URI` | `bolt://localhost:7687` |
| `spring.neo4j.authentication.password` | `NEO4J_PASSWORD` | `password123` |

## What Phase 2 produces

- **Provenance on every node and edge** — `source_doc, page, chunk_id, ingested_at, confidence`.
- **Line-level chunking** so each rule cites a tight passage (e.g. the non-IG cap cites the exact
  "Note: Aggregate exposure to non-investment-grade instruments…" line on page 2).
- **The trace terminus**: every `Limit`/`Aggregate`/`Threshold`/`ConcentrationLimit`/`LiquidityFloor`
  has a `DEFINED_BY` edge to the `GuidelineChunk` it came from.
- **Multi-hop traversals** answering questions without re-reading the document.

### The LLM seam

`graph.extraction.RuleExtractor` is the interface where an LLM plugs in to interpret guideline prose
into rule intents. The shipped `SeedRuleExtractor` is a deterministic baseline (the post-approval
rule set), so the system runs offline; an LLM implementation can replace it with no downstream
change. Either way, an extractor only *names* a trusted `FormulaKey` — it never produces a figure.

## Graph model

```
(AssetClass)-[:HAS_LIMIT]->(Limit)-[:DEFINED_BY]->(GuidelineChunk)
(AssetClass)-[:CONTRIBUTES_TO]->(Aggregate)-[:DEFINED_BY]->(GuidelineChunk)
(RiskMetric)-[:HAS_THRESHOLD]->(Threshold)-[:ON_BREACH]->(BreachAction)-[:OWNED_BY]->(Owner)
(ConcentrationLimit)-[:DEFINED_BY]->(GuidelineChunk)
(LiquidityFloor)-[:DEFINED_BY]->(GuidelineChunk)
(Position)-[:IN_ASSET_CLASS]->(AssetClass)   (Position)-[:ISSUED_BY]->(Issuer)-[:ROLLS_UP_TO]->(ParentIssuer)
```

## Tests

```bash
mvn test          # deterministic ingestion tests (no Neo4j needed)
```
