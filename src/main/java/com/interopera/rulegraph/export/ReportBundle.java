package com.interopera.rulegraph.export;

import com.interopera.rulegraph.audit.AuditEvent;
import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.evaluation.TraceabilityChecker;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.graph.extraction.LlmRuleExtractor;
import com.interopera.rulegraph.narrative.NarrativeFirewall;
import com.interopera.rulegraph.reconciliation.ReconciliationService;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A complete, self-describing record of one report run, written to {@code artifacts/exports/} as
 * JSON. It bundles the firm configuration, the computed figures, the reconciliation, the traceability
 * result, the narrative with its firewall outcome, and the audit events. The web view reads this file
 * directly, so the engine remains the single source of truth and the front end needs no live service.
 */
public record ReportBundle(
        FirmConfig firm,
        List<FigureResult> figures,
        ReconciliationService.Report reconciliation,
        TraceabilityChecker.Report traceability,
        Firewall firewall,
        List<AuditEvent> audit,
        // The configured LLM model id (e.g. "anthropic/claude-sonnet-4.6"), always present so the
        // viewer can label the LLM extractor with the model it would use, even before any LLM run.
        String llmModel,
        // The LLM prompt/reply exchange, present only for an LLM run; omitted for a seed run so the
        // viewer shows the exchange popup only when the operator actually picked the LLM extractor.
        @JsonInclude(JsonInclude.Include.NON_NULL) LlmRuleExtractor.LlmExchange llmExchange
) {
    /** The generated commentary together with the firewall check over it. */
    public record Firewall(String narrative, NarrativeFirewall.Report check) {
    }
}
