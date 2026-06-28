package com.interopera.rulegraph.api;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
