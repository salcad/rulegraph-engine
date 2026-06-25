package com.interopera.rulegraph.computation.calculator;

import java.math.BigDecimal;
import java.util.Map;

/** Shared helper for concentration calculators: deterministic "largest group" selection. */
final class Concentrations {

    private Concentrations() {
    }

    record Top(String key, BigDecimal value) {
    }

    /**
     * Largest entry by value; ties broken by key name so the result is deterministic. Returns a zero
     * entry if the map is empty (which surfaces as a 0% figure rather than a crash).
     */
    static Top largest(Map<String, BigDecimal> totals) {
        String bestKey = null;
        BigDecimal bestVal = BigDecimal.valueOf(-1);
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            int cmp = e.getValue().compareTo(bestVal);
            if (cmp > 0 || (cmp == 0 && (bestKey == null || e.getKey().compareTo(bestKey) < 0))) {
                bestKey = e.getKey();
                bestVal = e.getValue();
            }
        }
        return bestKey == null ? new Top("none", BigDecimal.ZERO) : new Top(bestKey, bestVal);
    }
}
