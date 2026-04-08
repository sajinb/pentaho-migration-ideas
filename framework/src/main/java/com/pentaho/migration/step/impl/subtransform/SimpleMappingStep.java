package com.pentaho.migration.step.impl.subtransform;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;
import com.pentaho.migration.step.StepRegistry;

import java.util.*;

/**
 * Simplified mapping step — delegates to {@link MappingStep}.
 * Equivalent to Pentaho's SimpleMapping step (single input/output, no named ports).
 *
 * <p>Params: same as {@link MappingStep}.
 */
public class SimpleMappingStep implements Step {

    private final MappingStep delegate;

    public SimpleMappingStep() { delegate = new MappingStep(); }
    public SimpleMappingStep(StepRegistry registry) { delegate = new MappingStep(registry); }

    @Override
    public void configure(Map<String, String> params) { delegate.configure(params); }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        return delegate.apply(inputs);
    }
}
