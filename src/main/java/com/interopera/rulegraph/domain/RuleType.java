package com.interopera.rulegraph.domain;

/** The kind of rule a guideline passage expresses. Drives how the rule is modeled in the graph. */
public enum RuleType {
    /** Per-asset-class min/max allocation band (Section 2). */
    ALLOCATION_LIMIT,
    /** Aggregate exposure cap across several asset classes (e.g. non-IG, Section 2 note). */
    EXPOSURE_LIMIT,
    /** Single-issuer or group concentration cap (Section 3.2). */
    CONCENTRATION_LIMIT,
    /** Liquidity floor (Section 3.3). */
    LIQUIDITY_FLOOR,
    /** Market-risk metric with a threshold and a breach action (Section 3.1). */
    RISK_METRIC
}
