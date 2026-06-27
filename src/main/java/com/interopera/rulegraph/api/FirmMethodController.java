package com.interopera.rulegraph.api;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.firmconfig.FirmConfig;
import com.interopera.rulegraph.firmconfig.FirmConfigException;
import com.interopera.rulegraph.firmconfig.FirmConfigLoader;
import com.interopera.rulegraph.firmconfig.FirmMethodDsl;
import com.interopera.rulegraph.firmconfig.FirmMethodDsl.DslError;
import com.interopera.rulegraph.firmconfig.FirmMethodDsl.DslResult;
import com.interopera.rulegraph.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web API for the firm-method mini-DSL and its live preview. The viewer's "Method DSL" tab posts a
 * draft DSL document here on each edit; the response carries the compiled {@link FirmConfig}, a
 * plain-English explanation, any per-line errors, and (best-effort) the figures those conventions
 * would produce against the current graph — so an analyst sees the effect of a method change live.
 * Compilation is pure configuration; no calculator changes.
 */
@RestController
@RequestMapping("/rulegraph-api/firm-method")
public class FirmMethodController {

    private static final Logger log = LoggerFactory.getLogger(FirmMethodController.class);

    private final FirmMethodDsl dsl;
    private final ReportService reportService;
    private final FirmConfigLoader firmConfigLoader;

    public FirmMethodController(FirmMethodDsl dsl, ReportService reportService,
                                FirmConfigLoader firmConfigLoader) {
        this.dsl = dsl;
        this.reportService = reportService;
        this.firmConfigLoader = firmConfigLoader;
    }

    /** Draft DSL submitted from the editor; {@code run} toggles the figures effect (default true). */
    public record PreviewRequest(String dsl, Boolean run) {
    }

    /** Live-preview response: compiled method, validation, and best-effort figures effect. */
    public record PreviewResponse(FirmConfig config, boolean valid, List<DslError> errors,
                                  List<String> explanation, List<FigureResult> figures,
                                  String figuresNote) {
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest req) {
        DslResult result = dsl.parse(req == null ? "" : req.dsl());

        List<FigureResult> figures = null;
        String note = null;
        boolean wantFigures = req == null || req.run() == null || req.run();
        if (!result.valid()) {
            note = "Fix the errors above to preview figures.";
        } else if (wantFigures) {
            try {
                figures = reportService.previewFigures(result.config());
            } catch (Exception e) {
                // The graph/Neo4j may not be reachable; still return the compiled method.
                log.warn("Figures preview unavailable: {}", e.getMessage());
                note = "Figures preview unavailable (graph not reachable): " + e.getMessage();
            }
        }
        return new PreviewResponse(result.config(), result.valid(), result.errors(),
                result.explanation(), figures, note);
    }

    /** Returns the canonical DSL for a known firm, so the editor can start from a real template. */
    @GetMapping("/dsl")
    public PreviewRequest template(@RequestParam(defaultValue = "firm_A") String firm) {
        FirmConfig config;
        try {
            config = firmConfigLoader.load(firm);
        } catch (FirmConfigException e) {
            config = FirmConfig.firmA();
        }
        return new PreviewRequest(dsl.toDsl(config), true);
    }
}
