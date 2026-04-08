package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Selects, reorders, or renames fields. Equivalent to Pentaho's SelectValues step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns} — comma-separated 0-based column indices to keep (in output order)
 * </ul>
 */
public class SelectValuesStep extends AbstractStreamingStep {

    private int[] columns;

    @Override
    public void configure(Map<String, String> params) {
        String[] parts = params.get("columns").split(",");
        columns = new int[parts.length];
        for (int i = 0; i < parts.length; i++) columns[i] = Integer.parseInt(parts[i].trim());
    }

    @Override
    protected Row transform(Row row) {
        String[] values = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            int col = columns[i];
            values[i] = col < row.fieldCount() ? row.getString(col) : null;
        }
        return new Row(values);
    }
}
