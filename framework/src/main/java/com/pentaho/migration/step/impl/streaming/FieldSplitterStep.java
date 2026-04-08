package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Arrays;
import java.util.Map;

/**
 * Splits one delimited field into multiple new fields (side-by-side).
 * Equivalent to Pentaho's FieldSplitter step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}    — 0-based column index to split
 *   <li>{@code separator} — delimiter, default ","
 *   <li>{@code outputCount} — number of output fields; if -1 split into as many as needed
 * </ul>
 *
 * <p>The original field is replaced by the split fields.
 */
public class FieldSplitterStep extends AbstractStreamingStep {

    private int    column;
    private String separator    = ",";
    private int    outputCount  = -1;

    @Override
    public void configure(Map<String, String> params) {
        column      = Integer.parseInt(params.get("column"));
        separator   = params.getOrDefault("separator", ",");
        outputCount = Integer.parseInt(params.getOrDefault("outputCount", "-1"));
    }

    @Override
    protected Row transform(Row row) {
        String value = column < row.fieldCount() ? row.getString(column) : null;
        String[] parts;
        if (value == null) {
            parts = outputCount > 0 ? new String[outputCount] : new String[]{null};
        } else {
            parts = value.split(java.util.regex.Pattern.quote(separator), -1);
            if (outputCount > 0 && parts.length < outputCount) {
                parts = Arrays.copyOf(parts, outputCount);
            } else if (outputCount > 0 && parts.length > outputCount) {
                parts = Arrays.copyOf(parts, outputCount);
            }
        }

        String[] old   = row.getValues();
        String[] nw    = new String[old.length - 1 + parts.length];
        System.arraycopy(old,   0, nw, 0,              column);
        System.arraycopy(parts, 0, nw, column,          parts.length);
        System.arraycopy(old,   column + 1, nw, column + parts.length, old.length - column - 1);
        return new Row(nw);
    }
}
