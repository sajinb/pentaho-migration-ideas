package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Converts a specific value in a field to null.
 * Equivalent to Pentaho's NullIf step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column} — 0-based column index
 *   <li>{@code value}  — the value to convert to null
 * </ul>
 */
public class NullIfStep extends AbstractStreamingStep {

    private int    column;
    private String value;

    @Override
    public void configure(Map<String, String> params) {
        column = Integer.parseInt(params.get("column"));
        value  = params.get("value");
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length && value != null && value.equals(values[column])) {
            values[column] = null;
        }
        return new Row(values);
    }
}
