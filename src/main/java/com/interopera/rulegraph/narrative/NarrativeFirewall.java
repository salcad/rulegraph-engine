package com.interopera.rulegraph.narrative;

import com.interopera.rulegraph.domain.FigureResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the no-language-model-numbers requirement rather than merely asserting it. It scans the
 * narrative for every numeric token and confirms each one already appears in the computed figures
 * (their values, utilisations, and limits). Any number in the narrative that is not present in the
 * computed output is a violation and fails the check.
 *
 * <p>This makes the firewall an observable control: if a model-written commentary ever introduced a
 * figure of its own, this check would catch it.
 */
@Service
public class NarrativeFirewall {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    public Report check(String narrative, List<FigureResult> figures) {
        Set<String> allowed = allowedNumbers(figures);
        Set<String> found = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();

        Matcher m = NUMBER.matcher(narrative);
        while (m.find()) {
            String normalized = normalize(m.group());
            found.add(normalized);
            if (!allowed.contains(normalized)) {
                violations.add(m.group());
            }
        }
        return new Report(allowed.size(), found.size(), violations, violations.isEmpty());
    }

    /** Every number that legitimately appears in the computed output. */
    private Set<String> allowedNumbers(List<FigureResult> figures) {
        Set<String> allowed = new LinkedHashSet<>();
        for (FigureResult f : figures) {
            collect(allowed, f.value());
            collect(allowed, f.utilization());
            collect(allowed, f.limit());
        }
        return allowed;
    }

    private void collect(Set<String> into, String text) {
        if (text == null) {
            return;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            into.add(normalize(m.group()));
        }
    }

    /** Normalise so 15, 15.0 and 15.00 compare equal and thousands separators are ignored. */
    private String normalize(String raw) {
        return new BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString();
    }

    /** Firewall outcome. {@code pass} is true when the narrative introduced no new number. */
    public record Report(int allowedNumbers, int numbersInNarrative,
                         List<String> violations, boolean pass) {
    }
}
