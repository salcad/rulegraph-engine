package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.FigureStatus;

import java.math.BigDecimal;

/** Deterministic status determination against limit bounds. */
public final class Statuses {

    private Statuses() {
    }

    /** Upper-bound rule (cap): over -> BREACH, exactly at -> AT_LIMIT, under -> OK. */
    public static FigureStatus againstMax(BigDecimal value, BigDecimal max) {
        int cmp = value.compareTo(max);
        if (cmp > 0) return FigureStatus.BREACH;
        if (cmp == 0) return FigureStatus.AT_LIMIT;
        return FigureStatus.OK;
    }

    /** Lower-bound rule (floor): below -> BREACH, else OK. */
    public static FigureStatus againstMin(BigDecimal value, BigDecimal min) {
        return value.compareTo(min) < 0 ? FigureStatus.BREACH : FigureStatus.OK;
    }

    /** Band rule (min..max): outside -> BREACH, at the max edge -> AT_LIMIT, else OK. */
    public static FigureStatus againstBand(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) return FigureStatus.BREACH;
        if (value.compareTo(max) == 0) return FigureStatus.AT_LIMIT;
        return FigureStatus.OK;
    }
}
