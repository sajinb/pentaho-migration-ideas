package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Extracts a substring from a field by start/end index.
 * Equivalent to Pentaho's StringCut step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}    — 0-based column index
 *   <li>{@code fromIndex} — 0-based start index (inclusive), default 0
 *   <li>{@code toIndex}   — 0-based end index (exclusive); -1 (default) = end of string
 * </ul>
 */
public class StringCutStep extends AbstractStreamingStep {

    private int column;
    private int fromIndex = 0;
    private int toIndex   = -1;

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.get("column"));
        fromIndex = Integer.parseInt(params.getOrDefault("fromIndex", "0"));
        toIndex   = Integer.parseInt(params.getOrDefault("toIndex", "-1"));
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length && values[column] != null) {
            String v   = values[column];
            int    from = Math.min(fromIndex, v.length());
            int    to   = toIndex < 0 ? v.length() : Math.min(toIndex, v.length());
            values[column] = v.substring(from, Math.max(from, to));
        }
        return new Row(values);
    }
}
