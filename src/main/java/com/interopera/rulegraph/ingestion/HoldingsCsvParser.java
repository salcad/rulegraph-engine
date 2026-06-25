package com.interopera.rulegraph.ingestion;

import com.interopera.rulegraph.config.RuleGraphProperties;
import com.interopera.rulegraph.domain.Position;
import com.interopera.rulegraph.domain.Provenance;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code sample_holdings.csv} into {@link Position} records. This is a fully deterministic
 * parse — no LLM, no inference — so every position carries provenance with confidence 1.0. The
 * holdings snapshot is the authoritative data the engine computes over.
 */
@Component
public class HoldingsCsvParser {

    private final RuleGraphProperties props;

    public HoldingsCsvParser(RuleGraphProperties props) {
        this.props = props;
    }

    public List<Position> parse() {
        Path path = Path.of(props.holdingsCsvPath());
        List<Position> positions = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (CSVRecord r : parser) {
                String instrumentId = r.get("instrument_id");
                Provenance prov = Provenance.deterministic(
                        props.holdingsCsv(), "holdings:" + instrumentId);
                positions.add(new Position(
                        instrumentId,
                        r.get("instrument_name"),
                        r.get("asset_class"),
                        r.get("issuer_name"),
                        r.get("issuer_type"),
                        r.get("parent_issuer"),
                        r.get("credit_rating"),
                        r.get("downgraded_from"),
                        new BigDecimal(r.get("market_value_sgd")),
                        new BigDecimal(r.get("modified_duration")),
                        prov));
            }
        } catch (IOException e) {
            throw new IngestionException("Failed to read holdings CSV: " + path, e);
        }
        return positions;
    }
}
