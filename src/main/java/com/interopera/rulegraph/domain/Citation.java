package com.interopera.rulegraph.domain;

/**
 * The source a figure traces back to — the terminus of {@code figure -> graph path -> source}.
 *
 * @param sourceDoc      originating document
 * @param page           page number in that document
 * @param chunkId        the {@code GuidelineChunk} id the rule was defined by
 * @param passageSummary short human-readable description of the passage
 */
public record Citation(
        String sourceDoc,
        Integer page,
        String chunkId,
        String passageSummary
) {
}
