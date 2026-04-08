package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Concatenates multiple fields into a new field appended to the row.
 * Equivalent to Pentaho's ConcatFields step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns}   — comma-separated 0-based column indices to concatenate
 *   <li>{@code separator} — string between values, default ""
 * </ul>
 */
public class ConcatFieldsStep extends AbstractStreamingStep {

    private int[]  columns;
    private String separator = "";

    @Override
    public void configure(Map<String, String> params) {
        String[] parts = params.get("columns").split(",");
        columns = new int[parts.length];
        for (int i = 0; i < parts.length; i++) columns[i] = Integer.parseInt(parts[i].trim());
        separator = params.getOrDefault("separator", "");
    }

    @Override
    protected Row transform(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) sb.append(separator);
            String v = columns[i] < row.fieldCount() ? row.getString(columns[i]) : null;
            if (v != null) sb.append(v);
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = sb.toString();
        return new Row(nw);
    }
}
