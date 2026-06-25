package com.interopera.rulegraph.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.interopera.rulegraph.computation.FigureComputationService;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.firmconfig.FirmConfigLoader;
import com.interopera.rulegraph.ingestion.IngestionService;
import com.interopera.rulegraph.reconciliation.ExpectedFigures;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Report entry point. Started with {@code report}, it runs the full pipeline (ingest into the graph,
 * then compute every figure deterministically by graph traversal), prints the figures as JSON, and
 * reconciles them against the selected firm's answer key.
 *
 * <p>The firm is chosen at run time and changes nothing in the engine. Both of these produce a valid
 * report from the same build:
 * <pre>
 *   java -jar rulegraph-engine.jar report                  # Firm A (default)
 *   java -jar rulegraph-engine.jar report --firm=firm_B    # Firm B, by configuration only
 * </pre>
 */
@Component
@Order(10)
public class ReportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportRunner.class);

    private final IngestionService ingestionService;
    private final FigureComputationService computationService;
    private final FirmConfigLoader firmConfigLoader;
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ReportRunner(IngestionService ingestionService,
                        FigureComputationService computationService,
                        FirmConfigLoader firmConfigLoader) {
        this.ingestionService = ingestionService;
        this.computationService = computationService;
        this.firmConfigLoader = firmConfigLoader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("report")) {
            return;
        }

        String firmId = resolveFirmId(args);
        FirmConfig firm = firmConfigLoader.load(firmId);
        log.info("Running report for {}", firm.firmId());

        ingestionService.ingest();
        List<FigureResult> figures = computationService.computeAll(firm);

        log.info("=== Computed figures ({}) ===", firm.firmId());
        System.out.println(json.writeValueAsString(figures));

        reconcile(firm.firmId(), figures);
    }

    /** Firm id from {@code --firm=...}, else a bare argument after {@code report}, else firm_A. */
    private String resolveFirmId(ApplicationArguments args) {
        List<String> opt = args.getOptionValues("firm");
        if (opt != null && !opt.isEmpty()) {
            return opt.getFirst();
        }
        return args.getNonOptionArgs().stream()
                .filter(a -> !a.equals("report"))
                .findFirst()
                .orElse("firm_A");
    }

    /** Per-figure reconciliation vs the firm's answer key on value, status, and utilisation. */
    private void reconcile(String firmId, List<FigureResult> figures) {
        Map<String, Expected> expected = ExpectedFigures.forFirm(firmId);
        log.info("=== Reconciliation vs {} answer key ===", firmId);
        if (expected.isEmpty()) {
            log.warn("No answer key on file for {} - skipping reconciliation", firmId);
            return;
        }

        int pass = 0;
        for (FigureResult f : figures) {
            Expected e = expected.get(f.figure());
            if (e == null) {
                System.out.printf("  ?    %-34s (no expected entry)%n", f.figure());
                continue;
            }
            boolean ok = e.value().equals(f.value())
                    && e.status() == f.status()
                    && e.utilization().equals(f.utilization());
            if (ok) {
                pass++;
            }
            System.out.printf("  %-4s %-34s value=%-16s status=%-9s util=%-9s%s%n",
                    ok ? "PASS" : "FAIL", f.figure(), f.value(), f.status(), f.utilization(),
                    ok ? "" : "  EXPECTED value=" + e.value() + " status=" + e.status()
                            + " util=" + e.utilization());
        }
        System.out.printf("%n  %d/%d figures reconcile to %s%n", pass, expected.size(), firmId);
    }
}
