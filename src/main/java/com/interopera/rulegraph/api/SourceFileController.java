package com.interopera.rulegraph.api;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Streams the raw source files the engine actually computes over, so the report viewer can show them
 * unmodified next to a figure (the holdings the figure sums, the formula registry that combined them)
 * with no copy to drift out of sync. These are the very same files the pipeline reads: the holdings
 * CSV from the configured {@code sample_docs} path and the formula registry Resource that
 * {@link com.interopera.rulegraph.computation.dsl.FormulaLibrary} loads. There is no second copy.
 */
@RestController
@RequestMapping("/rulegraph-api")
public class SourceFileController {

    private final RuleGraphProperties props;
    private final Resource formulasResource;

    public SourceFileController(
            RuleGraphProperties props,
            @Value("${rulegraph.formulas-resource:classpath:formulas.yaml}") Resource formulasResource) {
        this.props = props;
        this.formulasResource = formulasResource;
    }

    /**
     * Returns one of the named source files as inline text. {@code holdings} streams the holdings CSV
     * the engine ingests; {@code formulas} streams the formula registry the evaluator loads. An
     * unknown name is a 404, a missing/unreadable file a 404 as well (the file is configuration, not
     * user input).
     */
    @GetMapping("/source/{name}")
    public ResponseEntity<Resource> source(@PathVariable String name) {
        Resource resource;
        MediaType type;
        switch (name) {
            case "holdings" -> {
                resource = new FileSystemResource(props.holdingsCsvPath());
                type = MediaType.parseMediaType("text/csv; charset=UTF-8");
            }
            case "formulas" -> {
                resource = formulasResource;
                type = MediaType.parseMediaType("text/yaml; charset=UTF-8");
            }
            default -> {
                return ResponseEntity.notFound().build();
            }
        }
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(type)
                // Inline so "open in a new tab" renders the file rather than downloading it.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }

    // The columns the engine's HoldingsCsvParser reads; an edited file must keep all of them.
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "instrument_id", "instrument_name", "asset_class", "issuer_name", "issuer_type",
            "parent_issuer", "credit_rating", "downgraded_from", "market_value_sgd",
            "modified_duration");

    /**
     * Overwrites the holdings CSV with an edited version, after validating it parses the way the
     * pipeline expects. This is the perturbation hook for the reconciliation demo: change a holding,
     * re-run, and watch the figures move away from the answer key. The first edit snapshots the
     * original to {@code <file>.orig} so {@link #restoreHoldings()} can put it back; the on-disk file
     * is the very same one the pipeline reads, so the next run picks up the change with no caching.
     */
    @PutMapping(value = "/source/holdings", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public Map<String, Object> writeHoldings(@RequestBody String body) {
        validateHoldings(body);
        Path path = Path.of(props.holdingsCsvPath());
        try {
            Path snapshot = Path.of(props.holdingsCsvPath() + ".orig");
            if (!Files.exists(snapshot) && Files.exists(path)) {
                Files.copy(path, snapshot, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.writeString(path, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write holdings CSV: " + e.getMessage());
        }
        return Map.of("saved", true);
    }

    /**
     * Restores the holdings CSV from the snapshot taken on the first edit. A no-op (and {@code
     * restored:false}) when nothing was ever edited, so the original is already in place.
     */
    @PostMapping("/source/holdings/restore")
    public Map<String, Object> restoreHoldings() {
        Path path = Path.of(props.holdingsCsvPath());
        Path snapshot = Path.of(props.holdingsCsvPath() + ".orig");
        if (!Files.exists(snapshot)) {
            return Map.of("restored", false);
        }
        try {
            Files.copy(snapshot, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to restore holdings CSV: " + e.getMessage());
        }
        return Map.of("restored", true);
    }

    /** Rejects an edit (400) that the parser could not turn into positions, with a readable reason. */
    private void validateHoldings(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Holdings CSV is empty.");
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setTrim(true).build();
        try (CSVParser parser = format.parse(new StringReader(body))) {
            for (String col : REQUIRED_COLUMNS) {
                if (!parser.getHeaderMap().containsKey(col)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Missing required column: " + col);
                }
            }
            int rows = 0;
            for (CSVRecord r : parser) {
                rows++;
                parseDecimal(r, "market_value_sgd");
                parseDecimal(r, "modified_duration");
            }
            if (rows == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Holdings CSV has a header but no rows.");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not parse holdings CSV: " + e.getMessage());
        }
    }

    private void parseDecimal(CSVRecord r, String column) {
        String value = r.get(column);
        try {
            new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Row " + r.getRecordNumber() + ": " + column + " is not a number: \"" + value + "\"");
        }
    }
}
