package com.interopera.rulegraph.firmconfig;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the two firms load from their bundled YAML with the conventions that distinguish them.
 * This is what makes switching firms a configuration change rather than a code change.
 */
class FirmConfigLoaderTest {

    private FirmConfigLoader loader() {
        return new FirmConfigLoader(new RuleGraphProperties(null, null, null, null, null));
    }

    @Test
    void firmAUsesDefaultConventions() {
        FirmConfig a = loader().load("firm_A");
        assertThat(a.firmId()).isEqualTo("firm_A");
        assertThat(a.includeFallenAngels()).isFalse();
        assertThat(a.greGroupBy()).isEqualTo(FirmConfig.GreGroupBy.ISSUER);
        assertThat(a.utilizationFormat()).isEqualTo(FirmConfig.UtilizationFormat.PERCENT_1DP);
    }

    @Test
    void firmBOverridesTheThreeConventions() {
        FirmConfig b = loader().load("firm_B");
        assertThat(b.firmId()).isEqualTo("firm_B");
        assertThat(b.includeFallenAngels()).isTrue();
        assertThat(b.greGroupBy()).isEqualTo(FirmConfig.GreGroupBy.PARENT_ISSUER);
        assertThat(b.utilizationFormat()).isEqualTo(FirmConfig.UtilizationFormat.TRUNCATED_BPS);
    }
}
