package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Splits a delimited field into multiple rows — one row per token.
 * The split field is replaced by the token value in each emitted row.
 * Equivalent to Pentaho's SplitFieldToRows step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}    — 0-based column index to split
 *   <li>{@code separator} — delimiter, default ","
 * </ul>
 */
public class SplitFieldToRowsStep implements Step {

    private int    column    = 0;
    private String separator = ",";

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.getOrDefault("column", "0"));
        separator = params.getOrDefault("separator", ",");
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        final int     col      = column;
        final String  sep      = separator;

        return new Iterator<Row>() {
            final Queue<Row> pending = new ArrayDeque<>();

            private void fillPending() {
                while (pending.isEmpty() && upstream.hasNext()) {
                    Row    row    = upstream.next();
                    String value  = col < row.fieldCount() ? row.getString(col) : null;
                    String[] old  = row.getValues();
                    if (value == null) {
                        pending.add(row);
                    } else {
                        for (String token : value.split(java.util.regex.Pattern.quote(sep), -1)) {
                            String[] nw = old.clone();
                            nw[col] = token;
                            pending.add(new Row(nw));
                        }
                    }
                }
            }

            @Override public boolean hasNext() { fillPending(); return !pending.isEmpty(); }
            @Override public Row next() { fillPending(); return pending.poll(); }
        };
    }
}
