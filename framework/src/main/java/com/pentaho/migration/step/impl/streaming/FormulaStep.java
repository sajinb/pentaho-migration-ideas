package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Evaluates a formula expression (OpenFormula-compatible) and appends the result.
 * Equivalent to Pentaho's Formula step.
 *
 * <p><b>Phase 3 stub</b> — requires Janino or a formula parser library.
 * Throws {@link UnsupportedOperationException} until implemented.
 */
public class FormulaStep extends AbstractStreamingStep {

    @Override
    public void configure(Map<String, String> params) {
        // Phase 3: compile the formula expression
    }

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "FormulaStep requires Phase 3 (Janino expression compiler) — not yet implemented.");
    }
}
