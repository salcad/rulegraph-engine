package com.interopera.rulegraph.firmconfig;

/**
 * A firm's house conventions, expressed as data the shared calculators read at runtime. Switching
 * firms means supplying a different {@code FirmConfig} — never editing a calculator (constraint 5).
 *
 * <p>The three flags are exactly the points on which Firm B differs from Firm A (per
 * {@code firm_B_brief.md}). Phase 4 loads these from per-firm YAML; Phase 3 uses {@link #firmA()}.
 *
 * @param firmId              identifier, e.g. {@code firm_A}
 * @param includeFallenAngels whether below-IG "fallen angels" count toward the non-IG aggregate
 * @param greGroupBy          whether GRE concentration is measured per issuer or per parent issuer
 * @param utilizationFormat   how utilization is rendered
 */
public record FirmConfig(
        String firmId,
        boolean includeFallenAngels,
        GreGroupBy greGroupBy,
        UtilizationFormat utilizationFormat
) {
    public enum GreGroupBy {ISSUER, PARENT_ISSUER}

    public enum UtilizationFormat {PERCENT_1DP, TRUNCATED_BPS}

    /** Firm A's default reading: no fallen angels, GRE per issuer, utilization as 1-dp percent. */
    public static FirmConfig firmA() {
        return new FirmConfig("firm_A", false, GreGroupBy.ISSUER, UtilizationFormat.PERCENT_1DP);
    }
}
