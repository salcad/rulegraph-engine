package com.interopera.rulegraph.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.interopera.rulegraph.audit.AppendOnlyAuditLog;
import com.interopera.rulegraph.audit.AuditEventType;
import com.interopera.rulegraph.computation.FigureComputationService;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.evaluation.TraceabilityChecker;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.firmconfig.FirmConfigLoader;
import com.interopera.rulegraph.ingestion.IngestionService;
import com.interopera.rulegraph.ingestion.IngestionService.IngestionResult;
import com.interopera.rulegraph.narrative.NarrativeFirewall;
import com.interopera.rulegraph.narrative.NarrativeGenerator;
import com.interopera.rulegraph.reconciliation.AnswerKeyProvider;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;
import com.interopera.rulegraph.reconciliation.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Report and evaluation entry point. Started with {@code report}, it runs the full pipeline for a
 * chosen firm and records every stage to the append-only audit log:
 * <ol>
 *   <li>ingest the materials into the graph,</li>
 *   <li>compute every figure deterministically by graph traversal and print it as JSON,</li>
 *   <li>reconcile the figures against the firm's answer key (pass/fail and delta per figure),</li>
 *   <li>check that every figure resolves figure to graph path to source,</li>
 *   <li>generate narrative commentary and run the firewall that proves it introduced no number
 *       absent from the computed output,</li>
 *   <li>export the figures.</li>
 * </ol>
 *
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
    private final AnswerKeyProvider answerKeyProvider;
    private final ReconciliationService reconciliationService;
    private final TraceabilityChecker traceabilityChecker;
    private final NarrativeGenerator narrativeGenerator;
    private final NarrativeFirewall narrativeFirewall;
    private final AppendOnlyAuditLog auditLog;

    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ReportRunner(IngestionService ingestionService,
                        FigureComputationService computationService,
                        FirmConfigLoader firmConfigLoader,
                        AnswerKeyProvider answerKeyProvider,
                        ReconciliationService reconciliationService,
                        TraceabilityChecker traceabilityChecker,
                        NarrativeGenerator narrativeGenerator,
                        NarrativeFirewall narrativeFirewall,
                        AppendOnlyAuditLog auditLog) {
        this.ingestionService = ingestionService;
        this.computationService = computationService;
        this.firmConfigLoader = firmConfigLoader;
        this.answerKeyProvider = answerKeyProvider;
        this.reconciliationService = reconciliationService;
        this.traceabilityChecker = traceabilityChecker;
        this.narrativeGenerator = narrativeGenerator;
        this.narrativeFirewall = narrativeFirewall;
        this.auditLog = auditLog;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("report")) {
            return;
        }

        String firmId = resolveFirmId(args);
        String runId = firmId + "-" + System.currentTimeMillis();
        auditLog.append(runId, AuditEventType.RUN_STARTED, Map.of("firm", firmId));

        FirmConfig firm = firmConfigLoader.load(firmId);
        auditLog.append(runId, AuditEventType.CONFIG_CHANGED, Map.of(
                "firm", firm.firmId(),
                "include_fallen_angels", firm.includeFallenAngels(),
                "gre_group_by", firm.greGroupBy().name(),
                "utilization_format", firm.utilizationFormat().name()));
        log.info("Running report for {}", firm.firmId());

        IngestionResult ingest = ingestionService.ingest();
        auditLog.append(runId, AuditEventType.GRAPH_CONSTRUCTED, Map.of(
                "graph_nodes", ingest.graphNodes(), "graph_edges", ingest.graphEdges(),
                "chunks", ingest.chunks(), "positions", ingest.positions()));

        List<FigureResult> figures = computationService.computeAll(firm);
        auditLog.append(runId, AuditEventType.FIGURES_COMPUTED, Map.of("count", figures.size()));
        log.info("=== Computed figures ({}) ===", firm.firmId());
        System.out.println(json.writeValueAsString(figures));

        reconcile(runId, firmId, figures);
        traceability(runId, figures);
        firewall(runId, figures);
        export(runId, firmId, figures);

        System.out.printf("%nAudit log: %s (%d events)%n",
                auditLog.file(runId), auditLog.readAll(runId).size());
    }

    private void reconcile(String runId, String firmId, List<FigureResult> figures) {
        Map<String, Expected> key = answerKeyProvider.forFirm(firmId);
        ReconciliationService.Report report = reconciliationService.reconcile(figures, key);
        System.out.printf("%n=== Reconciliation vs %s answer key ===%n", firmId);
        for (ReconciliationService.Line l : report.lines()) {
            System.out.printf("  %-4s %-34s computed=%-16s expected=%-16s delta=%s%n",
                    l.pass() ? "PASS" : "FAIL", l.figure(), l.computedValue(),
                    l.expectedValue() == null ? "(none)" : l.expectedValue(),
                    l.delta() == null ? "n/a" : l.delta().toPlainString());
        }
        System.out.printf("  %d/%d figures reconcile to %s%n", report.passed(), report.total(), firmId);
        auditLog.append(runId, AuditEventType.RECONCILED, Map.of(
                "firm", firmId, "passed", report.passed(), "total", report.total(),
                "all_pass", report.allPass()));
    }

    private void traceability(String runId, List<FigureResult> figures) {
        TraceabilityChecker.Report report = traceabilityChecker.check(figures);
        System.out.printf("%n=== Traceability (figure -> graph path -> source) ===%n");
        for (TraceabilityChecker.Line l : report.lines()) {
            System.out.printf("  %-4s %-34s chunk=%-12s path=%s chunkExists=%s%n",
                    l.pass() ? "PASS" : "FAIL", l.figure(),
                    l.chunkId() == null ? "(none)" : l.chunkId(), l.hasGraphPath(), l.chunkExists());
        }
        System.out.printf("  %d/%d figures fully traceable%n", report.passed(), report.total());
        auditLog.append(runId, AuditEventType.TRACEABILITY_CHECKED, Map.of(
                "passed", report.passed(), "total", report.total(), "all_pass", report.allPass()));
    }

    private void firewall(String runId, List<FigureResult> figures) {
        String narrative = narrativeGenerator.generate(figures);
        NarrativeFirewall.Report report = narrativeFirewall.check(narrative, figures);
        System.out.printf("%n=== Narrative ===%n%s%n", narrative);
        System.out.printf("%n=== Firewall (no model-introduced numbers) ===%n");
        System.out.printf("  numbers in narrative: %d, all present in computed output: %s%n",
                report.numbersInNarrative(), report.pass());
        if (!report.pass()) {
            System.out.printf("  VIOLATIONS: %s%n", report.violations());
        }
        auditLog.append(runId, AuditEventType.FIREWALL_CHECKED, Map.of(
                "numbers_in_narrative", report.numbersInNarrative(),
                "violations", report.violations(), "pass", report.pass()));
    }

    private void export(String runId, String firmId, List<FigureResult> figures) throws Exception {
        Path out = Path.of("artifacts", "exports", "figures-" + firmId + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.writeValueAsString(figures), StandardCharsets.UTF_8);
        auditLog.append(runId, AuditEventType.REPORT_EXPORTED, Map.of("path", out.toString()));
        System.out.printf("%nExported figures to %s%n", out);
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
}
