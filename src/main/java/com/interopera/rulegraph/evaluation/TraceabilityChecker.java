package com.interopera.rulegraph.evaluation;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies that every figure resolves all the way along {@code figure -> graph path -> source}. For
 * each figure it checks there is a graph path and a citation, and confirms the cited
 * {@code GuidelineChunk} actually exists in the graph. This is a real resolution check rather than a
 * presence check: a citation that points at a chunk the graph does not contain fails.
 */
@Service
public class TraceabilityChecker {

    private final Driver driver;

    public TraceabilityChecker(Driver driver) {
        this.driver = driver;
    }

    public Report check(List<FigureResult> figures) {
        List<Line> lines = new ArrayList<>();
        try (Session session = driver.session()) {
            for (FigureResult f : figures) {
                boolean isError = f.status() == FigureStatus.ERROR;
                boolean hasPath = f.graphPath() != null && !f.graphPath().isBlank();
                boolean hasCitation = f.citation() != null && f.citation().chunkId() != null;
                boolean chunkExists = hasCitation && chunkExists(session, f.citation().chunkId());
                boolean pass = !isError && hasPath && hasCitation && chunkExists;
                lines.add(new Line(f.figure(),
                        hasCitation ? f.citation().chunkId() : null, hasPath, chunkExists, pass));
            }
        }
        long passed = lines.stream().filter(Line::pass).count();
        return new Report(lines, (int) passed, lines.size());
    }

    private boolean chunkExists(Session session, String chunkId) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (g:GuidelineChunk {chunk_id: $id}) RETURN count(g) AS c",
                        Map.of("id", chunkId))
                .single().get("c").asLong() > 0);
    }

    /** Traceability outcome for one figure. */
    public record Line(String figure, String chunkId, boolean hasGraphPath,
                       boolean chunkExists, boolean pass) {
    }

    /** The full traceability outcome. */
    public record Report(List<Line> lines, int passed, int total) {
        public boolean allPass() {
            return passed == total && total > 0;
        }
    }
}
