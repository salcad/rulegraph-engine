package com.interopera.rulegraph.reconciliation;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciles computed figures against a firm's answer key, reporting per-figure pass/fail and a
 * numeric delta. A figure passes when its value, status, and utilisation all match the answer key.
 */
@Service
public class ReconciliationService {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    public Report reconcile(List<FigureResult> figures, Map<String, Expected> answerKey) {
        List<Line> lines = new ArrayList<>();
        for (FigureResult f : figures) {
            Expected e = answerKey.get(f.figure());
            if (e == null) {
                lines.add(new Line(f.figure(), false, f.value(), null, null,
                        false, false, false));
                continue;
            }
            boolean valueMatch = e.value().equals(f.value());
            boolean statusMatch = e.status() == f.status();
            boolean utilMatch = e.utilization().equals(f.utilization());
            BigDecimal delta = delta(f, e);
            lines.add(new Line(f.figure(), valueMatch && statusMatch && utilMatch,
                    f.value(), e.value(), delta, valueMatch, statusMatch, utilMatch));
        }
        long passed = lines.stream().filter(Line::pass).count();
        return new Report(lines, (int) passed, lines.size());
    }

    /** computed minus expected, when both carry a parseable number; otherwise null. */
    private BigDecimal delta(FigureResult f, Expected e) {
        BigDecimal computed = f.numericValue() != null ? f.numericValue() : firstNumber(f.value());
        BigDecimal expected = firstNumber(e.value());
        return (computed == null || expected == null) ? null : computed.subtract(expected);
    }

    private BigDecimal firstNumber(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(s);
        return m.find() ? new BigDecimal(m.group().replace(",", "")) : null;
    }

    /** One reconciled figure. */
    public record Line(
            String figure,
            boolean pass,
            String computedValue,
            String expectedValue,
            BigDecimal delta,
            boolean valueMatch,
            boolean statusMatch,
            boolean utilizationMatch
    ) {
    }

    /** The full reconciliation outcome. */
    public record Report(List<Line> lines, int passed, int total) {
        public boolean allPass() {
            return passed == total && total > 0;
        }
    }
}
