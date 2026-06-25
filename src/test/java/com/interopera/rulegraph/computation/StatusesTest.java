package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.FigureStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Status determination against caps, floors, and bands — the breach logic the report turns on. */
class StatusesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void capExactlyAtLimitIsAtLimit() {
        // single corporate issuer 8% vs max 8% -> AT_LIMIT
        assertThat(Statuses.againstMax(d("8"), d("8"))).isEqualTo(FigureStatus.AT_LIMIT);
        assertThat(Statuses.againstMax(d("15"), d("20"))).isEqualTo(FigureStatus.OK);
        assertThat(Statuses.againstMax(d("21"), d("20"))).isEqualTo(FigureStatus.BREACH);
    }

    @Test
    void floorBelowIsBreach() {
        // cash 4% vs min 5% -> BREACH; liquidity 47% vs min 25% -> OK
        assertThat(Statuses.againstMin(d("4"), d("5"))).isEqualTo(FigureStatus.BREACH);
        assertThat(Statuses.againstMin(d("47"), d("25"))).isEqualTo(FigureStatus.OK);
    }

    @Test
    void bandInsideIsOk() {
        // duration 3.88 within 2.0–6.5 -> OK; outside -> BREACH
        assertThat(Statuses.againstBand(d("3.88"), d("2.0"), d("6.5"))).isEqualTo(FigureStatus.OK);
        assertThat(Statuses.againstBand(d("1.5"), d("2.0"), d("6.5"))).isEqualTo(FigureStatus.BREACH);
    }
}
