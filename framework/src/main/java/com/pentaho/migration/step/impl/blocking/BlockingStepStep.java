package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.Iterator;
import java.util.List;

/**
 * Accumulates all input rows and only starts emitting them once all have been received.
 * Equivalent to Pentaho's BlockingStep step.
 * Useful as a synchronization barrier in pipelines.
 */
public class BlockingStepStep extends AbstractBlockingStep {
    @Override
    protected Iterator<Row> process(List<Row> rows) {
        return rows.iterator();
    }
}
