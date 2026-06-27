package com.interopera.rulegraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.interopera.rulegraph.audit.AppendOnlyAuditLog;
import com.interopera.rulegraph.audit.AuditEventType;
import com.interopera.rulegraph.computation.FigureComputationService;
import com.interopera.rulegraph.computation.dsl.FormulaLibrary;
import com.interopera.rulegraph.config.LlmProperties;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.evaluation.TraceabilityChecker;
import com.interopera.rulegraph.export.ReportBundle;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.firmconfig.FirmConfigLoader;
import com.interopera.rulegraph.graph.extraction.ExtractorMode;
import com.interopera.rulegraph.ingestion.IngestionService;
import com.interopera.rulegraph.ingestion.IngestionService.IngestionResult;
import com.interopera.rulegraph.narrative.NarrativeFirewall;
import com.interopera.rulegraph.narrative.NarrativeGenerator;
import com.interopera.rulegraph.reconciliation.AnswerKeyProvider;
import com.interopera.rulegraph.reconciliation.ReconciliationService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs the full report pipeline for one firm and returns a complete {@link ReportBundle}: ingest into
 * the graph, compute the figures deterministically, reconcile against the answer key, check
 * traceability, generate narrative and run the firewall, and record every stage to the append-only
 * audit log. The same method backs both the command-line entry point and the web API, so they always
 * produce identical results.
 */
@Service
public class ReportService {

    private final IngestionService ingestionService;
    private final FigureComputationService computationService;
    private final FirmConfigLoader firmConfigLoader;
    private final AnswerKeyProvider answerKeyProvider;
    private final ReconciliationService reconciliationService;
    private final TraceabilityChecker traceabilityChecker;
    private final NarrativeGenerator narrativeGenerator;
    private final NarrativeFirewall narrativeFirewall;
    private final AppendOnlyAuditLog auditLog;
    private final FormulaLibrary formulaLibrary;
    private final LlmProperties llmProperties;

    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ReportService(IngestionService ingestionService,
                         FigureComputationService computationService,
                         FirmConfigLoader firmConfigLoader,
                         AnswerKeyProvider answerKeyProvider,
                         ReconciliationService reconciliationService,
                         TraceabilityChecker traceabilityChecker,
                         NarrativeGenerator narrativeGenerator,
                         NarrativeFirewall narrativeFirewall,
                         AppendOnlyAuditLog auditLog,
                         FormulaLibrary formulaLibrary,
                         LlmProperties llmProperties) {
        this.ingestionService = ingestionService;
        this.computationService = computationService;
        this.firmConfigLoader = firmConfigLoader;
        this.answerKeyProvider = answerKeyProvider;
        this.reconciliationService = reconciliationService;
        this.traceabilityChecker = traceabilityChecker;
        this.narrativeGenerator = narrativeGenerator;
        this.narrativeFirewall = narrativeFirewall;
        this.auditLog = auditLog;
        this.formulaLibrary = formulaLibrary;
        this.llmProperties = llmProperties;
    }

    /**
     * Recomputes the figures for an ad-hoc {@link FirmConfig} against the <em>current</em> graph,
     * without rebuilding it — the cheap path behind the firm-method live preview. If the graph has
     * not been built yet, it is ingested once. No reconciliation, narrative, or audit write happens:
     * this is a draft "what would these conventions produce" view, not a committed run.
     */
    public synchronized List<FigureResult> previewFigures(FirmConfig firm) {
        List<FigureResult> figures = computationService.computeAll(firm);
        if (figures.isEmpty()) {
            ingestionService.ingest();
            figures = computationService.computeAll(firm);
        }
        return figures;
    }

    /** Runs the pipeline for a firm using the configured default extractor. */
    public ReportBundle run(String firmId) {
        return run(firmId, null);
    }

    /** Runs the pipeline for a firm and returns the bundle. Synchronised so concurrent API calls do
     *  not interleave a graph rebuild. The {@code extractorMode} chooses the rule extractor for this
     *  run (hardcoded vs LLM); a {@code null} mode follows the configured default. */
    public synchronized ReportBundle run(String firmId, ExtractorMode extractorMode) {
        String runId = firmId + "-" + System.currentTimeMillis();
        auditLog.append(runId, AuditEventType.RUN_STARTED, Map.of("firm", firmId));

        FirmConfig firm = firmConfigLoader.load(firmId);
        auditLog.append(runId, AuditEventType.CONFIG_CHANGED, Map.of(
                "firm", firm.firmId(),
                "include_fallen_angels", firm.includeFallenAngels(),
                "gre_group_by", firm.greGroupBy().name(),
                "utilization_format", firm.utilizationFormat().name()));

        IngestionResult ingest = ingestionService.ingest(extractorMode);
        auditLog.append(runId, AuditEventType.GRAPH_CONSTRUCTED, Map.of(
                "rule_extractor", ingest.extractor().name(),
                "graph_nodes", ingest.graphNodes(), "graph_edges", ingest.graphEdges(),
                "chunks", ingest.chunks(), "positions", ingest.positions()));

        List<FigureResult> figures = computationService.computeAll(firm);
        // Record which arithmetic produced the numbers: the verbatim formulas and a hash over them,
        // so an examiner can confirm the figures came from these expressions and that the formula
        // registry was not altered between runs (it sits alongside the deterministic engine, never
        // the LLM — constraint 3).
        auditLog.append(runId, AuditEventType.FIGURES_COMPUTED, Map.of(
                "count", figures.size(),
                "formulas_sha256", formulaLibrary.sha256(),
                "formulas", formulaLibrary.expressions()));

        ReconciliationService.Report reconciliation =
                reconciliationService.reconcile(figures, answerKeyProvider.forFirm(firmId));
        auditLog.append(runId, AuditEventType.RECONCILED, Map.of(
                "firm", firmId, "passed", reconciliation.passed(),
                "total", reconciliation.total(), "all_pass", reconciliation.allPass()));

        TraceabilityChecker.Report traceability = traceabilityChecker.check(figures);
        auditLog.append(runId, AuditEventType.TRACEABILITY_CHECKED, Map.of(
                "passed", traceability.passed(), "total", traceability.total(),
                "all_pass", traceability.allPass()));

        String narrative = narrativeGenerator.generate(figures);
        NarrativeFirewall.Report firewall = narrativeFirewall.check(narrative, figures);
        auditLog.append(runId, AuditEventType.FIREWALL_CHECKED, Map.of(
                "numbers_in_narrative", firewall.numbersInNarrative(),
                "violations", firewall.violations(), "pass", firewall.pass()));

        auditLog.append(runId, AuditEventType.REPORT_EXPORTED,
                Map.of("path", "artifacts/exports/report-" + firmId + ".json"));

        // Only surface the LLM exchange when this run actually performed the extraction; on a cache
        // reuse (e.g. a firm switch) the LLM did not run, so the viewer should not pop the dialog.
        ReportBundle bundle = new ReportBundle(firm, figures, reconciliation, traceability,
                new ReportBundle.Firewall(narrative, firewall), auditLog.readAll(runId),
                llmProperties.model(),
                ingest.freshlyBuilt() ? ingest.llmExchange() : null);
        writeBundle(firmId, bundle);
        return bundle;
    }

    private void writeBundle(String firmId, ReportBundle bundle) {
        try {
            Path out = Path.of("artifacts", "exports", "report-" + firmId + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, json.writeValueAsString(bundle), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write report bundle for " + firmId, e);
        }
    }
}
