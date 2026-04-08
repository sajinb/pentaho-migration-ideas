package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Replaces null values in one or all fields with a default value.
 * Equivalent to Pentaho's IfNull step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}       — 0-based column index; -1 (default) = all columns
 *   <li>{@code defaultValue} — value to use instead of null
 * </ul>
 */
public class IfNullStep extends AbstractStreamingStep {

    private int    column = -1;
    private String defaultValue;

    @Override
    public void configure(Map<String, String> params) {
        column       = Integer.parseInt(params.getOrDefault("column", "-1"));
        defaultValue = params.getOrDefault("defaultValue", "");
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column >= 0) {
            if (column < values.length && values[column] == null) values[column] = defaultValue;
        } else {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) values[i] = defaultValue;
            }
        }
        return new Row(values);
    }
}
