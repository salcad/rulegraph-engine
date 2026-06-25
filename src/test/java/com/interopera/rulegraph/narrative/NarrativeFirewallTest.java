package com.interopera.rulegraph.narrative;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FigureStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The firewall must accept commentary that only restates computed numbers and reject anything new. */
class NarrativeFirewallTest {

    private final NarrativeFirewall firewall = new NarrativeFirewall();

    private List<FigureResult> figures() {
        return List.of(
                new FigureResult("cash", "4.0%", FigureStatus.BREACH, "5-25%", "n/a",
                        "path", null, new BigDecimal("4.0")),
                new FigureResult("aggregate_non_ig_exposure", "15.0%", FigureStatus.OK, "max 20%",
                        "75.0%", "path", null, new BigDecimal("15.0")));
    }

    @Test
    void passesWhenNarrativeOnlyRestatesComputedNumbers() {
        String narrative = "Cash stands at 4.0% against its 5-25% band. Non-IG exposure is 15.0% "
                + "against a limit of max 20%, with utilisation of 75.0%.";
        NarrativeFirewall.Report report = firewall.check(narrative, figures());
        assertThat(report.pass()).isTrue();
        assertThat(report.violations()).isEmpty();
    }

    @Test
    void failsWhenNarrativeIntroducesANewNumber() {
        // 42 never appears in any computed figure: the model is putting a number into the report.
        String narrative = "Cash stands at 4.0%. The portfolio holds 42 instruments.";
        NarrativeFirewall.Report report = firewall.check(narrative, figures());
        assertThat(report.pass()).isFalse();
        assertThat(report.violations()).contains("42");
    }
}
