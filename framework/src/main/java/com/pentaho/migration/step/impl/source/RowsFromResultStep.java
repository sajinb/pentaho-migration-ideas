package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.util.*;

/**
 * Consumes rows that were passed from a calling job via {@code RowsToResultStep}.
 * Equivalent to Pentaho's RowsFromResult step.
 *
 * <p>Rows are injected at configure time via the {@code "rows"} param key using
 * {@link #setRows(List)}, or they may be provided via the job execution context.
 */
public class RowsFromResultStep extends AbstractSourceStep {

    private List<Row> rows = Collections.emptyList();

    /** Called by JobExecutor / RunTransformationEntry to inject the result rows. */
    public void setRows(List<Row> rows) {
        this.rows = rows;
    }

    @Override
    protected Iterator<Row> readRows() {
        return new ArrayList<>(rows).iterator();
    }
}
