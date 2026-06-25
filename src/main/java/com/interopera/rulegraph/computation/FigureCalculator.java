package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.FigureResult;
import com.interopera.rulegraph.domain.FormulaKey;

/**
 * A trusted, deterministic calculator for one {@link FormulaKey}. Implementations are the <em>only</em>
 * code permitted to produce a reported number (constraint 3). Each computes a figure by summing the
 * positions a graph traversal returns, and emits a fully-formed {@link FigureResult} including the
 * graph path it used and the citation it traced to.
 */
public interface FigureCalculator {

    /** The formula key this calculator implements. */
    FormulaKey key();

    /** Compute the figure for a resolved rule. */
    FigureResult compute(ResolvedRule rule, ComputationContext ctx);
}
