package com.interopera.rulegraph.domain;

/** Compliance status of a computed figure against its limit. */
public enum FigureStatus {
    OK,
    AT_LIMIT,
    BREACH,
    /** The figure could not be fully traced to a source — returned as an error, never a silent value. */
    ERROR
}
