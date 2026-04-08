package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Categorizes a numeric field into named ranges and appends the range label.
 * Equivalent to Pentaho's NumberRange step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}         — 0-based column index of the numeric field
 *   <li>{@code lowerBound.0}, {@code upperBound.0}, {@code label.0}, … — range definitions
 *   <li>{@code defaultLabel}   — label when no range matches
 * </ul>
 */
public class NumberRangeStep extends AbstractStreamingStep {

    private int    column;
    private String defaultLabel = "";

    private record Range(double lower, double upper, String label) {}
    private final List<Range> ranges = new ArrayList<>();

    @Override
    public void configure(Map<String, String> params) {
        column       = Integer.parseInt(params.get("column"));
        defaultLabel = params.getOrDefault("defaultLabel", "");
        int i = 0;
        while (params.containsKey("lowerBound." + i)) {
            double lo = Double.parseDouble(params.get("lowerBound." + i));
            double hi = Double.parseDouble(params.get("upperBound." + i));
            String lb = params.getOrDefault("label." + i, "");
            ranges.add(new Range(lo, hi, lb));
            i++;
        }
    }

    @Override
    protected Row transform(Row row) {
        String label = defaultLabel;
        if (column < row.fieldCount() && row.getString(column) != null) {
            try {
                double v = Double.parseDouble(row.getString(column));
                for (Range r : ranges) {
                    if (v >= r.lower() && v <= r.upper()) { label = r.label(); break; }
                }
            } catch (NumberFormatException ignored) {}
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = label;
        return new Row(nw);
    }
}
