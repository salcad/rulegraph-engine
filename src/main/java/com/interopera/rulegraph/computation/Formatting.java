package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.firmconfig.FirmConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic value formatting. All rounding is explicit (no implicit float formatting), so the
 * rendered figures are byte-identical across runs. Utilization rendering is the one firm-switchable
 * piece (Firm B uses truncated basis points) and is driven by {@link FirmConfig}.
 */
public final class Formatting {

    /** Scale used for intermediate divisions before final rounding. */
    public static final int DIV_SCALE = 12;

    private Formatting() {
    }

    /** part / total * 100, as an exact-enough BigDecimal (e.g. 35000000/100000000 -> 35.0). */
    public static BigDecimal percentOf(BigDecimal part, BigDecimal total) {
        return part.divide(total, DIV_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** A percentage value rendered to 1 decimal place, e.g. {@code "58.3%"}. */
    public static String percent1dp(BigDecimal pct) {
        return pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /**
     * Utilization of a limit: {@code value / limit}, rendered per the firm's house style.
     * Both arguments are percentages (e.g. value 35, limit 60).
     */
    public static String utilization(BigDecimal value, BigDecimal limit, FirmConfig firm) {
        BigDecimal ratio = value.divide(limit, DIV_SCALE, RoundingMode.HALF_UP);
        return switch (firm.utilizationFormat()) {
            case PERCENT_1DP -> ratio.multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
            // Truncated (floored) basis points, e.g. 58.333% -> 5833 bps.
            case TRUNCATED_BPS -> ratio.multiply(BigDecimal.valueOf(10000))
                    .setScale(0, RoundingMode.DOWN).toPlainString() + " bps";
        };
    }

    /** A percent limit bound without trailing zeros, e.g. {@code 20 -> "20"}, {@code 6.5 -> "6.5"}. */
    public static String percentBound(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    /** A duration bound to 1 decimal, e.g. {@code 2.0 -> "2.0"}. */
    public static String yearsBound(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** A thousands-grouped integer, e.g. {@code 38790 -> "38,790"}. */
    public static String grouped(BigDecimal v) {
        return String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
