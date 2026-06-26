package com.interopera.rulegraph.firmconfig;

import com.interopera.rulegraph.firmconfig.FirmMethodDsl.DslResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FirmMethodDslTest {

    private final FirmMethodDsl dsl = new FirmMethodDsl();

    @Test
    void compilesFirmBConventions() {
        DslResult r = dsl.parse("""
                firm acme_capital
                fallen_angels include
                gre by parent
                utilization bps
                """);

        assertThat(r.valid()).isTrue();
        assertThat(r.errors()).isEmpty();
        assertThat(r.config().firmId()).isEqualTo("acme_capital");
        assertThat(r.config().includeFallenAngels()).isTrue();
        assertThat(r.config().greGroupBy()).isEqualTo(FirmConfig.GreGroupBy.PARENT_ISSUER);
        assertThat(r.config().utilizationFormat()).isEqualTo(FirmConfig.UtilizationFormat.TRUNCATED_BPS);
    }

    @Test
    void omittedDirectivesUseFirmADefaults() {
        DslResult r = dsl.parse("firm solo\n");

        assertThat(r.valid()).isTrue();
        assertThat(r.config().includeFallenAngels()).isFalse();
        assertThat(r.config().greGroupBy()).isEqualTo(FirmConfig.GreGroupBy.ISSUER);
        assertThat(r.config().utilizationFormat()).isEqualTo(FirmConfig.UtilizationFormat.PERCENT_1DP);
    }

    @Test
    void commentsAndBlankLinesAreIgnored_andCaseInsensitive() {
        DslResult r = dsl.parse("""
                # acme method
                FIRM acme

                Utilization PERCENT  # report as percent
                """);

        assertThat(r.valid()).isTrue();
        assertThat(r.config().firmId()).isEqualTo("acme");
        assertThat(r.config().utilizationFormat()).isEqualTo(FirmConfig.UtilizationFormat.PERCENT_1DP);
    }

    @Test
    void reportsPerLineErrorsForUnknownDirectiveAndValue() {
        DslResult r = dsl.parse("""
                firm acme
                gre by cousin
                liquidity floor 25
                """);

        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).hasSize(2);
        assertThat(r.errors().get(0).line()).isEqualTo(2);
        assertThat(r.errors().get(0).message()).contains("gre");
        assertThat(r.errors().get(1).line()).isEqualTo(3);
        assertThat(r.errors().get(1).message()).contains("unknown directive");
    }

    @Test
    void roundTripsThroughToDsl() {
        FirmConfig b = new FirmConfig("firm_b", true,
                FirmConfig.GreGroupBy.PARENT_ISSUER, FirmConfig.UtilizationFormat.TRUNCATED_BPS);

        DslResult r = dsl.parse(dsl.toDsl(b));

        assertThat(r.valid()).isTrue();
        assertThat(r.config()).isEqualTo(b);
    }
}
