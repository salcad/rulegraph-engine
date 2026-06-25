package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.firmconfig.FirmConfig;

import java.math.BigDecimal;

/**
 * Per-run inputs shared by all calculators: the graph-backed portfolio queries, the precomputed NAV,
 * and the firm configuration whose conventions the calculators honour.
 */
public record ComputationContext(
        GraphPortfolioQueries portfolio,
        BigDecimal nav,
        FirmConfig firm
) {
}
