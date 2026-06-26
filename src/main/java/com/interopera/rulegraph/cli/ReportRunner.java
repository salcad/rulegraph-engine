package com.interopera.rulegraph.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.interopera.rulegraph.evaluation.TraceabilityChecker;
import com.interopera.rulegraph.export.ReportBundle;
import com.interopera.rulegraph.reconciliation.ReconciliationService;
import com.interopera.rulegraph.service.ReportService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Command-line entry for producing a report. Started with {@code report} it runs the full pipeline
 * for a firm, prints the figures and the evaluation results, and exits. Without the {@code report}
 * argument it does nothing, leaving the web API server running.
 *
 * <pre>
 *   java -jar rulegraph-engine.jar report                  # Firm A (default)
 *   java -jar rulegraph-engine.jar report --firm=firm_B    # Firm B, by configuration only
 * </pre>
 */
@Component
@Order(10)
public class ReportRunner implements ApplicationRunner {

    private final ReportService reportService;
    private final ApplicationContext context;
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ReportRunner(ReportService reportService, ApplicationContext context) {
        this.reportService = reportService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("report")) {
            return;
        }
        String firmId = resolveFirmId(args);
        ReportBundle bundle = reportService.run(firmId);
        print(firmId, bundle);
        // The command-line report is a one-shot; exit so the web server does not stay up.
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void print(String firmId, ReportBundle bundle) throws Exception {
        System.out.printf("=== Computed figures (%s) ===%n", firmId);
        System.out.println(json.writeValueAsString(bundle.figures()));

        ReconciliationService.Report rec = bundle.reconciliation();
        System.out.printf("%n=== Reconciliation vs %s answer key ===%n", firmId);
        for (ReconciliationService.Line l : rec.lines()) {
            System.out.printf("  %-4s %-34s computed=%-16s expected=%-16s delta=%s%n",
                    l.pass() ? "PASS" : "FAIL", l.figure(), l.computedValue(),
                    l.expectedValue() == null ? "(none)" : l.expectedValue(),
                    l.delta() == null ? "n/a" : l.delta().toPlainString());
        }
        System.out.printf("  %d/%d figures reconcile to %s%n", rec.passed(), rec.total(), firmId);

        TraceabilityChecker.Report trace = bundle.traceability();
        System.out.printf("%n=== Traceability (figure -> graph path -> source) ===%n");
        for (TraceabilityChecker.Line l : trace.lines()) {
            System.out.printf("  %-4s %-34s chunk=%-12s path=%s chunkExists=%s%n",
                    l.pass() ? "PASS" : "FAIL", l.figure(),
                    l.chunkId() == null ? "(none)" : l.chunkId(), l.hasGraphPath(), l.chunkExists());
        }
        System.out.printf("  %d/%d figures fully traceable%n", trace.passed(), trace.total());

        System.out.printf("%n=== Narrative ===%n%s%n", bundle.firewall().narrative());
        var fw = bundle.firewall().check();
        System.out.printf("%n=== Firewall (no model-introduced numbers) ===%n");
        System.out.printf("  numbers in narrative: %d, all present in computed output: %s%n",
                fw.numbersInNarrative(), fw.pass());
        if (!fw.pass()) {
            System.out.printf("  VIOLATIONS: %s%n", fw.violations());
        }

        System.out.printf("%nExported report bundle to artifacts/exports/report-%s.json%n", firmId);
        System.out.printf("Audit events recorded: %d%n", bundle.audit().size());
    }

    /** Firm id from {@code --firm=...}, else a bare argument after {@code report}, else firm_A. */
    private String resolveFirmId(ApplicationArguments args) {
        List<String> opt = args.getOptionValues("firm");
        if (opt != null && !opt.isEmpty()) {
            return opt.getFirst();
        }
        return args.getNonOptionArgs().stream()
                .filter(a -> !a.equals("report"))
                .findFirst()
                .orElse("firm_A");
    }
}
