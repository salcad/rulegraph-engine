package com.interopera.rulegraph.audit;

import java.util.Map;

/**
 * One immutable audit record.
 *
 * @param seq       monotonically increasing sequence number within a run
 * @param runId     identifier of the report run this event belongs to
 * @param type      what happened
 * @param timestamp ISO-8601 instant the event was recorded
 * @param data      event-specific details captured for the examiner
 */
public record AuditEvent(
        long seq,
        String runId,
        AuditEventType type,
        String timestamp,
        Map<String, Object> data
) {
}
