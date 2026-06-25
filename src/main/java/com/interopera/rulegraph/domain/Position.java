package com.interopera.rulegraph.domain;

import java.math.BigDecimal;

/**
 * One portfolio holding from {@code sample_holdings.csv}. This is the authoritative data the
 * deterministic engine computes over. Monetary and duration values are {@link BigDecimal} so the
 * numeric path never touches floating point (constraint 1: byte-identical reruns).
 *
 * @param instrumentId    e.g. {@code SGS-01}
 * @param instrumentName  e.g. {@code SGS 2.5% 2030}
 * @param assetClass      raw asset-class label from the CSV
 * @param issuerName      issuing entity
 * @param issuerType      {@code government|corporate|GRE|spv|cash}
 * @param parentIssuer    parent for issuer rollups (may be blank)
 * @param creditRating    current rating (may be blank for cash)
 * @param downgradedFrom  prior rating if a "fallen angel"; blank otherwise (Firm B convention 1)
 * @param marketValueSgd  market value in SGD
 * @param modifiedDuration modified duration in years
 * @param provenance      where this row came from
 */
public record Position(
        String instrumentId,
        String instrumentName,
        String assetClass,
        String issuerName,
        String issuerType,
        String parentIssuer,
        String creditRating,
        String downgradedFrom,
        BigDecimal marketValueSgd,
        BigDecimal modifiedDuration,
        Provenance provenance
) {
    public boolean hasParent() {
        return parentIssuer != null && !parentIssuer.isBlank();
    }

    public boolean isFallenAngel() {
        return downgradedFrom != null && !downgradedFrom.isBlank();
    }
}
