package com.interopera.rulegraph.domain;

/**
 * A chunk of text extracted from the guidelines PDF. Every rule node in the graph terminates its
 * trace at one of these via a {@code DEFINED_BY} edge — this is the "source" in
 * {@code figure -> graph path -> source}.
 *
 * @param chunkId         deterministic id, e.g. {@code chunk_9c1a} (stable across runs)
 * @param page            1-based page number in the source document
 * @param text            the raw chunk text
 * @param passageSummary  short human-readable label, e.g. "Section 2 — allocation limits"
 * @param provenance      source document + ingestion metadata
 */
public record GuidelineChunk(
        String chunkId,
        int page,
        String text,
        String passageSummary,
        Provenance provenance
) {
}
