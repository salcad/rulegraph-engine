package com.interopera.rulegraph.reconciliation;

import com.interopera.rulegraph.domain.FigureStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The expected figures for each firm, transcribed from the provided answer keys. Used to reconcile
 * the computed report. Firm B differs from Firm A on exactly three things: the non-investment-grade
 * aggregate (fallen angels pull it into breach), the GRE concentration (grouped at the parent issuer,
 * also into breach), and how every utilisation is rendered (truncated basis points). All other values
 * and statuses are identical between the two firms.
 *
 * <p>Phase 5 will read these directly from the answer-key spreadsheet; holding them here keeps the
 * Phase 4 reconciliation self-contained.
 */
public final class ExpectedFigures {

    /** One expected figure: the value, status, and utilisation a firm's answer key reports. */
    public record Expected(String value, FigureStatus status, String utilization) {
    }

    private ExpectedFigures() {
    }

    public static Map<String, Expected> forFirm(String firmId) {
        return switch (firmId) {
            case "firm_A" -> FIRM_A;
            case "firm_B" -> FIRM_B;
            default -> Map.of();
        };
    }

    private static final Map<String, Expected> FIRM_A = new LinkedHashMap<>();
    private static final Map<String, Expected> FIRM_B = new LinkedHashMap<>();

    static {
        // Firm A: utilisation as one-decimal percent.
        FIRM_A.put("singapore_government_securities", e("35.0%", FigureStatus.OK, "58.3%"));
        FIRM_A.put("mas_bills", e("8.0%", FigureStatus.OK, "20.0%"));
        FIRM_A.put("investment_grade_corporate_bonds", e("33.0%", FigureStatus.OK, "66.0%"));
        FIRM_A.put("high_yield", e("9.0%", FigureStatus.OK, "60.0%"));
        FIRM_A.put("foreign_currency_bonds", e("5.0%", FigureStatus.OK, "25.0%"));
        FIRM_A.put("structured_credit", e("6.0%", FigureStatus.OK, "60.0%"));
        FIRM_A.put("cash", e("4.0%", FigureStatus.BREACH, "n/a"));
        FIRM_A.put("aggregate_non_ig_exposure", e("15.0%", FigureStatus.OK, "75.0%"));
        FIRM_A.put("single_corporate_issuer", e("8.0%", FigureStatus.AT_LIMIT, "100.0%"));
        FIRM_A.put("gre_issuer", e("7.0%", FigureStatus.OK, "58.3%"));
        FIRM_A.put("liquid_assets_ratio", e("47.0%", FigureStatus.OK, "188.0%"));
        FIRM_A.put("modified_duration", e("3.88 yrs", FigureStatus.OK, "n/a"));
        FIRM_A.put("portfolio_dv01", e("SGD 38,790 / bp", FigureStatus.OK, "45.6%"));

        // Firm B: same values except non-IG and GRE; utilisation as truncated basis points throughout.
        FIRM_B.put("singapore_government_securities", e("35.0%", FigureStatus.OK, "5833 bps"));
        FIRM_B.put("mas_bills", e("8.0%", FigureStatus.OK, "2000 bps"));
        FIRM_B.put("investment_grade_corporate_bonds", e("33.0%", FigureStatus.OK, "6600 bps"));
        FIRM_B.put("high_yield", e("9.0%", FigureStatus.OK, "6000 bps"));
        FIRM_B.put("foreign_currency_bonds", e("5.0%", FigureStatus.OK, "2500 bps"));
        FIRM_B.put("structured_credit", e("6.0%", FigureStatus.OK, "6000 bps"));
        FIRM_B.put("cash", e("4.0%", FigureStatus.BREACH, "n/a"));
        FIRM_B.put("aggregate_non_ig_exposure", e("21.0%", FigureStatus.BREACH, "10500 bps"));
        FIRM_B.put("single_corporate_issuer", e("8.0%", FigureStatus.AT_LIMIT, "10000 bps"));
        FIRM_B.put("gre_issuer", e("13.0%", FigureStatus.BREACH, "10833 bps"));
        FIRM_B.put("liquid_assets_ratio", e("47.0%", FigureStatus.OK, "18800 bps"));
        FIRM_B.put("modified_duration", e("3.88 yrs", FigureStatus.OK, "n/a"));
        FIRM_B.put("portfolio_dv01", e("SGD 38,790 / bp", FigureStatus.OK, "4563 bps"));
    }

    private static Expected e(String value, FigureStatus status, String utilization) {
        return new Expected(value, status, utilization);
    }
}
