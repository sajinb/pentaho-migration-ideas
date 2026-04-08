package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

/**
 * Pass-through no-op step. Equivalent to Pentaho's Dummy step.
 * Useful as a placeholder or synchronization point in a pipeline.
 */
public class DummyStep extends AbstractStreamingStep {
    @Override
    protected Row transform(Row row) { return row; }
}
