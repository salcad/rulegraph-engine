package com.interopera.rulegraph.computation;

import com.interopera.rulegraph.domain.FormulaKey;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed allow-list binding each {@link FormulaKey} to its trusted calculator. This is the
 * enforcement surface for constraint 3: an extracted rule may only name a key that resolves here;
 * a key with no registered calculator is rejected rather than executed. The registry is assembled
 * from the Spring-managed calculators at startup, so adding a calculator is an explicit code change,
 * never something an LLM can do at runtime.
 */
@Component
public class FormulaRegistry {

    private final Map<FormulaKey, FigureCalculator> calculators = new EnumMap<>(FormulaKey.class);

    public FormulaRegistry(List<FigureCalculator> all) {
        for (FigureCalculator calc : all) {
            calculators.put(calc.key(), calc);
        }
    }

    public boolean isRegistered(FormulaKey key) {
        return calculators.containsKey(key);
    }

    public FigureCalculator get(FormulaKey key) {
        FigureCalculator calc = calculators.get(key);
        if (calc == null) {
            throw new IllegalStateException("No registered calculator for formula key: " + key);
        }
        return calc;
    }
}
