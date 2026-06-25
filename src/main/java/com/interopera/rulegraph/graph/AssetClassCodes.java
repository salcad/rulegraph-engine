package com.interopera.rulegraph.graph;

import java.util.Locale;

/**
 * Maps the raw asset-class labels in the holdings CSV to the canonical codes used by the rule
 * intents and the graph, so a {@code Position} and the {@code AssetClass} its limit is attached to
 * resolve to the same node. The mapping is deterministic and total over the sample data.
 */
public final class AssetClassCodes {

    private AssetClassCodes() {
    }

    public static String toCode(String rawAssetClass) {
        String s = rawAssetClass == null ? "" : rawAssetClass.toLowerCase(Locale.ROOT);
        if (s.contains("singapore government")) return "singapore_government_securities";
        if (s.contains("mas bill")) return "mas_bills";
        if (s.contains("investment grade")) return "investment_grade_corporate_bonds";
        if (s.contains("high yield")) return "high_yield";
        if (s.contains("foreign currency")) return "foreign_currency_bonds";
        if (s.contains("structured credit")) return "structured_credit";
        if (s.contains("cash")) return "cash";
        return "unmapped_" + s.replaceAll("[^a-z0-9]+", "_");
    }
}
