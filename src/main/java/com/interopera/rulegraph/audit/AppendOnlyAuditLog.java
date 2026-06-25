package com.interopera.rulegraph.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A persistent, append-only audit log. Each event is written as one JSON line to a per-run file.
 *
 * <p>Append-only is enforced structurally: this class exposes only {@link #append} and
 * {@link #readAll}. There is no update or delete method, and the file is opened with
 * {@link StandardOpenOption#APPEND} only, so no code path can rewrite or remove a record once it is
 * written. This is the record an examiner replays to reconstruct how a report was produced; for a
 * production deployment the same interface would sit in front of an immutable store with the writes
 * hash-chained for tamper evidence.
 */
@Component
public class AppendOnlyAuditLog {

    private static final Logger log = LoggerFactory.getLogger(AppendOnlyAuditLog.class);

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong(0);
    private final Path dir = Path.of("artifacts", "audit");

    /** Append one event and return it. The only write path in the class. */
    public AuditEvent append(String runId, AuditEventType type, Map<String, Object> data) {
        AuditEvent event = new AuditEvent(
                seq.incrementAndGet(), runId, type, Instant.now().toString(),
                data == null ? Map.of() : data);
        try {
            Files.createDirectories(dir);
            String line = json.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(file(runId), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append audit event", e);
        }
        return event;
    }

    /** Read back every event recorded for a run, in order. Read-only. */
    public List<AuditEvent> readAll(String runId) {
        Path file = file(runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AuditEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(json.readValue(line, AuditEvent.class));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read audit log", e);
        }
    }

    public Path file(String runId) {
        return dir.resolve("run-" + runId + ".jsonl");
    }
}
