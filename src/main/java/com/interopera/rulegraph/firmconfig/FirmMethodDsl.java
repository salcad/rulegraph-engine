package com.interopera.rulegraph.firmconfig;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tiny, line-oriented domain-specific language for expressing a firm's method, compiled to a
 * {@link FirmConfig}. It is a friendlier alternative to the per-firm YAML for the same three house
 * conventions — and, crucially, it is still pure configuration: compiling DSL never edits a
 * calculator (constraint 5).
 *
 * <p>Grammar (case-insensitive, one directive per line, {@code #} starts a comment):
 * <pre>
 *   firm           &lt;id&gt;
 *   fallen_angels  include | exclude
 *   gre by         issuer  | parent
 *   utilization    percent | bps
 * </pre>
 * Any directive that is omitted falls back to the Firm A default (exclude / issuer / percent).
 * Unknown directives or values are reported as per-line errors, so a live editor can show exactly
 * where and why a draft is invalid while still rendering the best-effort compiled config.
 */
@Component
public class FirmMethodDsl {

    /** A single problem found while parsing, anchored to the 1-based editor line. */
    public record DslError(int line, String message) {
    }

    /**
     * The outcome of compiling a DSL document.
     *
     * @param config      the compiled config (best-effort: omitted directives use Firm A defaults)
     * @param valid       true when there are no errors
     * @param errors      per-line problems, in document order
     * @param explanation plain-English description of the resolved method, for the preview pane
     */
    public record DslResult(FirmConfig config, boolean valid, List<DslError> errors,
                            List<String> explanation) {
    }

    public DslResult parse(String dsl) {
        String firmId = "custom";
        Boolean fallenAngels = null;
        FirmConfig.GreGroupBy gre = null;
        FirmConfig.UtilizationFormat util = null;
        List<DslError> errors = new ArrayList<>();

        String[] lines = dsl == null ? new String[0] : dsl.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String content = stripComment(lines[i]).trim();
            if (content.isEmpty()) {
                continue;
            }
            String[] tok = content.split("\\s+");
            String key = tok[0].toLowerCase(Locale.ROOT);
            switch (key) {
                case "firm" -> {
                    if (tok.length < 2) {
                        errors.add(new DslError(lineNo, "firm needs an id, e.g. 'firm acme_capital'"));
                    } else {
                        firmId = slug(join(tok, 1));
                    }
                }
                case "fallen_angels", "fallen-angels", "fallenangels" -> {
                    Boolean b = tok.length < 2 ? null : toInclude(tok[1]);
                    if (b == null) {
                        errors.add(new DslError(lineNo,
                                "fallen_angels expects 'include' or 'exclude'"));
                    } else {
                        fallenAngels = b;
                    }
                }
                case "gre" -> {
                    FirmConfig.GreGroupBy g = greOf(lastToken(tok));
                    if (g == null) {
                        errors.add(new DslError(lineNo,
                                "gre expects 'by issuer' or 'by parent'"));
                    } else {
                        gre = g;
                    }
                }
                case "utilization", "utilisation" -> {
                    FirmConfig.UtilizationFormat u = tok.length < 2 ? null : utilOf(lastToken(tok));
                    if (u == null) {
                        errors.add(new DslError(lineNo,
                                "utilization expects 'percent' or 'bps'"));
                    } else {
                        util = u;
                    }
                }
                default -> errors.add(new DslError(lineNo, "unknown directive '" + tok[0]
                        + "' (expected firm, fallen_angels, gre, or utilization)"));
            }
        }

        boolean fa = Boolean.TRUE.equals(fallenAngels);
        FirmConfig.GreGroupBy greBy = gre == null ? FirmConfig.GreGroupBy.ISSUER : gre;
        FirmConfig.UtilizationFormat fmt = util == null
                ? FirmConfig.UtilizationFormat.PERCENT_1DP : util;
        FirmConfig config = new FirmConfig(firmId, fa, greBy, fmt);

        List<String> explanation = explain(config, fallenAngels != null, gre != null, util != null);
        return new DslResult(config, errors.isEmpty(), errors, explanation);
    }

    /** Renders a {@link FirmConfig} back to canonical DSL — used to seed the editor from a firm. */
    public String toDsl(FirmConfig c) {
        return String.join("\n",
                "# " + c.firmId() + " method",
                "firm " + c.firmId(),
                "fallen_angels " + (c.includeFallenAngels() ? "include" : "exclude"),
                "gre by " + (c.greGroupBy() == FirmConfig.GreGroupBy.PARENT_ISSUER ? "parent" : "issuer"),
                "utilization " + (c.utilizationFormat() == FirmConfig.UtilizationFormat.TRUNCATED_BPS
                        ? "bps" : "percent")) + "\n";
    }

    // --- helpers -------------------------------------------------------------------------------

    private List<String> explain(FirmConfig c, boolean faSet, boolean greSet, boolean utilSet) {
        List<String> out = new ArrayList<>();
        out.add("Firm id: " + c.firmId());
        out.add("Non-IG aggregate: fallen angels are "
                + (c.includeFallenAngels() ? "counted toward" : "excluded from")
                + " the non-investment-grade total" + defaultNote(faSet));
        out.add("GRE concentration: measured per "
                + (c.greGroupBy() == FirmConfig.GreGroupBy.PARENT_ISSUER
                        ? "parent issuer (entities sharing a parent are aggregated)"
                        : "individual issuer")
                + defaultNote(greSet));
        out.add("Utilization: reported as "
                + (c.utilizationFormat() == FirmConfig.UtilizationFormat.TRUNCATED_BPS
                        ? "truncated basis points (e.g. 58.333% -> 5833 bps)"
                        : "a percentage to one decimal place")
                + defaultNote(utilSet));
        return out;
    }

    private static String defaultNote(boolean explicitlySet) {
        return explicitlySet ? "" : " (default)";
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String lastToken(String[] tok) {
        return tok.length == 0 ? "" : tok[tok.length - 1].toLowerCase(Locale.ROOT);
    }

    private static String join(String[] tok, int from) {
        return String.join("_", List.of(tok).subList(from, tok.length));
    }

    private static Boolean toInclude(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "include", "included", "yes", "true", "on" -> Boolean.TRUE;
            case "exclude", "excluded", "no", "false", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static FirmConfig.GreGroupBy greOf(String v) {
        return switch (v) {
            case "issuer" -> FirmConfig.GreGroupBy.ISSUER;
            case "parent", "parent_issuer", "parentissuer" -> FirmConfig.GreGroupBy.PARENT_ISSUER;
            default -> null;
        };
    }

    private static FirmConfig.UtilizationFormat utilOf(String v) {
        return switch (v) {
            case "percent", "percent_1dp", "pct", "percentage" -> FirmConfig.UtilizationFormat.PERCENT_1DP;
            case "bps", "truncated_bps", "basis_points" -> FirmConfig.UtilizationFormat.TRUNCATED_BPS;
            default -> null;
        };
    }

    private static String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "custom" : slug;
    }
}
