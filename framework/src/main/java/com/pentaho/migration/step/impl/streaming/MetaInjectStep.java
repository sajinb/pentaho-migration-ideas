package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Dynamically injects metadata into a target transformation at runtime.
 * Equivalent to Pentaho's MetaInject step.
 *
 * <p><b>Phase 3 stub</b> — requires deep engine integration (runtime YAML rewriting).
 * Throws {@link UnsupportedOperationException} until implemented.
 */
public class MetaInjectStep extends AbstractStreamingStep {

    @Override
    public void configure(Map<String, String> params) {}

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "MetaInjectStep requires Phase 3 (dynamic pipeline rewriting) — not yet implemented.");
    }
}
