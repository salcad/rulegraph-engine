package com.interopera.rulegraph.domain;

import java.time.Instant;

/**
 * Provenance metadata carried by every graph node and edge.
 *
 * <p>This is the backbone of constraint 2 (traceability): every figure must resolve
 * {@code figure -> graph path -> source chunk}, and that is only possible if every element in the
 * chain records where it came from. {@code page} is nullable (CSV rows have no page).
 *
 * @param sourceDoc             originating document, e.g. {@code sample_fund_guidelines.pdf}
 * @param page                  1-based page number where applicable, else {@code null}
 * @param chunkId               deterministic id of the source chunk / row this element came from
 * @param ingestedAt            wall-clock time of ingestion (metadata only — never a reported figure)
 * @param extractionConfidence  0.0–1.0; deterministic parses are 1.0, LLM extractions vary (drives gate G1)
 */
public record Provenance(
        String sourceDoc,
        Integer page,
        String chunkId,
        Instant ingestedAt,
        double extractionConfidence
) {
    /** Provenance for a deterministically-parsed source (CSV row): full confidence, no page. */
    public static Provenance deterministic(String sourceDoc, String chunkId) {
        return new Provenance(sourceDoc, null, chunkId, Instant.now(), 1.0);
    }
}
