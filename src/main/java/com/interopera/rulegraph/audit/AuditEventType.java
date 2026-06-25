package com.interopera.rulegraph.audit;

/**
 * The kinds of events recorded to the append-only audit log. At minimum the log covers graph
 * construction, figure computation, reconciliation, configuration change, and export, so an examiner
 * can replay exactly how a report was produced.
 */
public enum AuditEventType {
    RUN_STARTED,
    CONFIG_CHANGED,
    GRAPH_CONSTRUCTED,
    FIGURES_COMPUTED,
    RECONCILED,
    TRACEABILITY_CHECKED,
    FIREWALL_CHECKED,
    REPORT_EXPORTED
}
