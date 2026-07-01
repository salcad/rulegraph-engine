package com.interopera.rulegraph.graph;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in how a limit-type node's stored bound is rendered onto its trace-graph label, so clicking
 * a Limit reveals its value and not just its code. The bound is read from the node's own properties
 * (never computed), so this is a pure display concern testable without Neo4j.
 */
class GraphNodeLabelTest {

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void allocationLimitShowsMinMaxBand() {
        assertThat(GraphQueryService.limitAnnotation("Limit", props("min", 20.0, "max", 60.0, "unit", "PERCENT")))
                .isEqualTo("20–60%");
    }

    @Test
    void zeroFloorAllocationShowsUpperBoundOnly() {
        // A "0%" minimum constrains nothing, so it reads as a plain cap rather than a 0–40 band.
        assertThat(GraphQueryService.limitAnnotation("Limit", props("min", 0.0, "max", 40.0, "unit", "PERCENT")))
                .isEqualTo("≤ 40%");
    }

    @Test
    void exposureAndConcentrationCapsAreUpperBounds() {
        assertThat(GraphQueryService.limitAnnotation("Aggregate", props("cap", 15.0, "unit", "PERCENT")))
                .isEqualTo("≤ 15%");
        assertThat(GraphQueryService.limitAnnotation("ConcentrationLimit", props("cap", 5.0, "unit", "PERCENT")))
                .isEqualTo("≤ 5%");
    }

    @Test
    void liquidityFloorIsALowerBound() {
        assertThat(GraphQueryService.limitAnnotation("LiquidityFloor", props("floor", 10.0, "unit", "PERCENT")))
                .isEqualTo("≥ 10%");
    }

    @Test
    void riskThresholdsCarryTheirUnit() {
        assertThat(GraphQueryService.limitAnnotation("Threshold", props("max", 5.0, "unit", "YEARS")))
                .isEqualTo("≤ 5 yrs");
        assertThat(GraphQueryService.limitAnnotation("Threshold", props("max", 12000.0, "unit", "SGD_PER_BP")))
                .isEqualTo("≤ 12,000 SGD/bp");
    }

    @Test
    void nonLimitNodeTypesGetNoAnnotation() {
        assertThat(GraphQueryService.limitAnnotation("AssetClass", props("code", "high_yield"))).isNull();
        assertThat(GraphQueryService.limitAnnotation("Position", props("market_value_sgd", 1000.0))).isNull();
    }
}
