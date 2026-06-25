package com.interopera.rulegraph.narrative;

import com.interopera.rulegraph.domain.FigureResult;

import java.util.List;

/**
 * Produces the human-readable commentary that accompanies a report. This is the one place a language
 * model may contribute text in a later iteration. It writes prose only; it must never originate a
 * number. Whatever it produces is subject to the {@link NarrativeFirewall} before being trusted.
 */
public interface NarrativeGenerator {

    String generate(List<FigureResult> figures);
}
