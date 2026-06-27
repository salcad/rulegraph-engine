package com.interopera.rulegraph.api;

import com.interopera.rulegraph.export.ReportBundle;
import com.interopera.rulegraph.firmconfig.FirmConfigException;
import com.interopera.rulegraph.graph.extraction.ExtractorMode;
import com.interopera.rulegraph.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Web API for the report viewer. The viewer calls {@code /rulegraph-api/report?firm=...} to run the pipeline
 * for a firm and receive the complete bundle (figures, reconciliation, traceability, firewall, audit).
 */
@RestController
@RequestMapping("/rulegraph-api")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** The firms that can be reported, for the viewer's firm switch. */
    @GetMapping("/firms")
    public List<String> firms() {
        return List.of("firm_A", "firm_B");
    }

    /**
     * Runs the full pipeline for a firm and returns the report bundle. The optional {@code extractor}
     * parameter ({@code seed} or {@code llm}) chooses the rule extractor for this run; when omitted or
     * unrecognised the configured default is used.
     */
    @GetMapping("/report")
    public ReportBundle report(@RequestParam(defaultValue = "firm_A") String firm,
                               @RequestParam(required = false) String extractor) {
        ExtractorMode mode = ExtractorMode.parse(extractor, null);
        return reportService.run(firm, mode);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(FirmConfigException.class)
    public Map<String, String> badFirm(FirmConfigException e) {
        return Map.of("error", e.getMessage());
    }
}
