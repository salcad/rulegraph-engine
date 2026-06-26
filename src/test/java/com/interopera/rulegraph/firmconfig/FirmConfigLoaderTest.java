package com.interopera.rulegraph.firmconfig;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the two firms load from their bundled YAML with the conventions that distinguish them,
 * and that a firm saved to the writable directory round-trips and is listed. This is what makes
 * switching — and adding — a firm a configuration change rather than a code change.
 */
class FirmConfigLoaderTest {

    private FirmConfigLoader loader() {
        return new FirmConfigLoader(new RuleGraphProperties(null, null, null, null, null));
    }

    private FirmConfigLoader loader(Path firmsDir) {
        return new FirmConfigLoader(
                new RuleGraphProperties(null, null, null, firmsDir.toString(), null));
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

    @Test
    void savedFirmRoundTripsAndIsListed(@TempDir Path firmsDir) {
        FirmConfigLoader loader = loader(firmsDir);
        FirmConfig saved = new FirmConfig("acme_capital", true,
                FirmConfig.GreGroupBy.PARENT_ISSUER, FirmConfig.UtilizationFormat.TRUNCATED_BPS);

        loader.save(saved);

        assertThat(loader.load("acme_capital")).isEqualTo(saved);
        // Bundled firms remain, with the saved firm appended after them.
        assertThat(loader.listFirms()).containsExactly("firm_A", "firm_B", "acme_capital");
    }
}
