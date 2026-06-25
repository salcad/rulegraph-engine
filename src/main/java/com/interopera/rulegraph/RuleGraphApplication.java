package com.interopera.rulegraph;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * RuleGraph — audit-grade portfolio compliance reporting.
 *
 * <p>Phase 2 scope: ingest the guidelines PDF and holdings CSV into a single Neo4j knowledge graph,
 * with provenance on every node and edge, queryable across multiple hops.
 */
@SpringBootApplication
@EnableConfigurationProperties(RuleGraphProperties.class)
public class RuleGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleGraphApplication.class, args);
    }
}
