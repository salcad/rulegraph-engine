package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.firmconfig.FirmConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic formatting — the rendering rules behind reproducible figures and firm switching. */
class FormattingTest {

    @Test
    void percentOfAndRender() {
        BigDecimal pct = Formatting.percentOf(new BigDecimal("35000000"), new BigDecimal("100000000"));
        assertThat(Formatting.percent1dp(pct)).isEqualTo("35.0%");
    }

    @Test
    void firmAUtilizationIsOneDecimalPercent() {
        // 35 / 60 = 58.33% -> "58.3%"
        String u = Formatting.utilization(new BigDecimal("35"), new BigDecimal("60"), FirmConfig.firmA());
        assertThat(u).isEqualTo("58.3%");
    }

    @Test
    void firmBUtilizationIsTruncatedBasisPoints() {
        // 35 / 60 = 58.333% -> 5833 bps (floored, not rounded)
        FirmConfig firmB = new FirmConfig("firm_B", true,
                FirmConfig.GreGroupBy.PARENT_ISSUER, FirmConfig.UtilizationFormat.TRUNCATED_BPS);
        String u = Formatting.utilization(new BigDecimal("35"), new BigDecimal("60"), firmB);
        assertThat(u).isEqualTo("5833 bps");
    }

    @Test
    void groupedThousands() {
        assertThat(Formatting.grouped(new BigDecimal("38790"))).isEqualTo("38,790");
    }

    @Test
    void boundsRenderCleanly() {
        assertThat(Formatting.percentBound(new BigDecimal("20"))).isEqualTo("20");
        assertThat(Formatting.percentBound(new BigDecimal("6.5"))).isEqualTo("6.5");
        assertThat(Formatting.yearsBound(new BigDecimal("2.0"))).isEqualTo("2.0");
    }
}
