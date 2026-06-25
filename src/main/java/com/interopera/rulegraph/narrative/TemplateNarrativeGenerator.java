package com.interopera.rulegraph.narrative;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic baseline narrative. It restates computed figures in prose without introducing any
 * number of its own: every number it mentions is copied verbatim from a figure's value or limit. This
 * keeps the system runnable offline and gives the firewall a clean input to verify. A model-backed
 * generator can replace this behind {@link NarrativeGenerator}; the firewall then guards its output
 * the same way.
 */
@Component
public class TemplateNarrativeGenerator implements NarrativeGenerator {

    @Override
    public String generate(List<FigureResult> figures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Portfolio compliance commentary.\n\n");

        boolean anyException = false;
        for (FigureResult f : figures) {
            if (f.status() == FigureStatus.BREACH || f.status() == FigureStatus.AT_LIMIT) {
                anyException = true;
                String verb = f.status() == FigureStatus.BREACH ? "is in breach at" : "sits at its limit of";
                sb.append("The ").append(readable(f.figure()))
                        .append(' ').append(verb).append(' ').append(f.value())
                        .append(" against a limit of ").append(f.limit()).append(".\n");
            }
        }
        if (!anyException) {
            sb.append("All monitored figures are within their limits.\n");
        }
        sb.append("\nThe figures above are produced by the calculation engine; this commentary only "
                + "describes them.");
        return sb.toString();
    }

    private String readable(String code) {
        return code.replace('_', ' ');
    }
}
