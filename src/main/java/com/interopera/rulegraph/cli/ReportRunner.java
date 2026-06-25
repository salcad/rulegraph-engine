package com.interopera.rulegraph.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.interopera.rulegraph.computation.FigureComputationService;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Phase 3 entry point. Started with {@code report}, it runs the full pipeline — ingest into the
 * graph, then compute every figure deterministically by graph traversal — prints the figures as
 * JSON in the brief's shape, and reconciles them against Firm A's answer key.
 *
 * <pre>  java -jar rulegraph-engine.jar report  </pre>
 */
@Component
@Order(10)
public class ReportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportRunner.class);

    private final IngestionService ingestionService;
    private final FigureComputationService computationService;
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ReportRunner(IngestionService ingestionService, FigureComputationService computationService) {
        this.ingestionService = ingestionService;
        this.computationService = computationService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("report")) {
            return;
        }

        ingestionService.ingest();
        List<FigureResult> figures = computationService.computeAll(FirmConfig.firmA());

        log.info("=== Computed figures (Firm A) ===");
        System.out.println(json.writeValueAsString(figures));

        reconcile(figures);
    }

    /** Per-figure reconciliation vs Firm A's answer key on value, status, and utilization. */
    private void reconcile(List<FigureResult> figures) {
        log.info("=== Reconciliation vs firm_A_answer_key ===");
        int pass = 0;
        for (FigureResult f : figures) {
            Expected e = FIRM_A.get(f.figure());
            if (e == null) {
                System.out.printf("  ?  %-34s (no expected entry)%n", f.figure());
                continue;
            }
            boolean ok = e.value().equals(f.value())
                    && e.status() == f.status()
                    && e.utilization().equals(f.utilization());
            if (ok) {
                pass++;
            }
            System.out.printf("  %s %-34s value=%-16s status=%-9s util=%-9s%s%n",
                    ok ? "PASS" : "FAIL", f.figure(), f.value(), f.status(), f.utilization(),
                    ok ? "" : "  EXPECTED value=" + e.value() + " status=" + e.status()
                            + " util=" + e.utilization());
        }
        System.out.printf("%n  %d/%d figures reconcile to Firm A%n", pass, FIRM_A.size());
    }

    private record Expected(String value, FigureStatus status, String utilization) {
    }

    /** Firm A ground truth transcribed from firm_A_answer_key.xlsx (Phase 5 reads the xlsx directly). */
    private static final Map<String, Expected> FIRM_A = Map.ofEntries(
            Map.entry("singapore_government_securities", new Expected("35.0%", FigureStatus.OK, "58.3%")),
            Map.entry("mas_bills", new Expected("8.0%", FigureStatus.OK, "20.0%")),
            Map.entry("investment_grade_corporate_bonds", new Expected("33.0%", FigureStatus.OK, "66.0%")),
            Map.entry("high_yield", new Expected("9.0%", FigureStatus.OK, "60.0%")),
            Map.entry("foreign_currency_bonds", new Expected("5.0%", FigureStatus.OK, "25.0%")),
            Map.entry("structured_credit", new Expected("6.0%", FigureStatus.OK, "60.0%")),
            Map.entry("cash", new Expected("4.0%", FigureStatus.BREACH, "n/a")),
            Map.entry("aggregate_non_ig_exposure", new Expected("15.0%", FigureStatus.OK, "75.0%")),
            Map.entry("single_corporate_issuer", new Expected("8.0%", FigureStatus.AT_LIMIT, "100.0%")),
            Map.entry("gre_issuer", new Expected("7.0%", FigureStatus.OK, "58.3%")),
            Map.entry("liquid_assets_ratio", new Expected("47.0%", FigureStatus.OK, "188.0%")),
            Map.entry("modified_duration", new Expected("3.88 yrs", FigureStatus.OK, "n/a")),
            Map.entry("portfolio_dv01", new Expected("SGD 38,790 / bp", FigureStatus.OK, "45.6%")));
}
