package com.interopera.rulegraph.domain;

/**
 * The fixed allow-list of trusted, deterministic computation methods.
 *
 * <p>This enum is the enforcement point for constraint 3: an extractor (eventually an LLM) may
 * <em>name</em> a formula key when it interprets a guideline passage, but it can never define one.
 * Any extracted key that does not resolve to a member of this enum is rejected at gate G1 and never
 * reaches a calculator. The engine — not the model — owns the implementation behind each key.
 */
public enum FormulaKey {
    ALLOCATION_PERCENT,
    AGGREGATE_EXPOSURE_PERCENT,
    ISSUER_CONCENTRATION,
    GROUP_CONCENTRATION,
    LIQUIDITY_RATIO,
    PORTFOLIO_DURATION,
    PORTFOLIO_DV01
}
