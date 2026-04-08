package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSinkStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects all rows and makes them available for retrieval by a calling job.
 * Equivalent to Pentaho's RowsToResult step.
 *
 * <p>After the transformation runs, call {@link #getResult()} to retrieve
 * the accumulated rows (for use by {@code RowsFromResultStep} in the next step).
 */
public class RowsToResultStep extends AbstractSinkStep {

    private final List<Row> result = new ArrayList<>();

    @Override protected void open() {}
    @Override protected void writeRow(Row row) { result.add(row); }
    @Override protected void close() {}

    /** Returns the rows accumulated during this step's execution. */
    public List<Row> getResult() {
        return Collections.unmodifiableList(result);
    }
}
