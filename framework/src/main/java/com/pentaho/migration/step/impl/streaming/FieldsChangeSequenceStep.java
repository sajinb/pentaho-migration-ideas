package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Appends a counter that increments each time monitored fields change value.
 * Equivalent to Pentaho's FieldsChangeSequence step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns} — comma-separated 0-based column indices to monitor
 *   <li>{@code start}   — starting counter value (default 1)
 * </ul>
 */
public class FieldsChangeSequenceStep implements Step {

    private int[]  columns;
    private long   start = 1;

    @Override
    public void configure(Map<String, String> params) {
        String[] parts = params.get("columns").split(",");
        columns = new int[parts.length];
        for (int i = 0; i < parts.length; i++) columns[i] = Integer.parseInt(parts[i].trim());
        start = Long.parseLong(params.getOrDefault("start", "1"));
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        long[] counter  = {start};
        String[] prevKey = {null};

        return new Iterator<Row>() {
            @Override public boolean hasNext() { return upstream.hasNext(); }
            @Override public Row next() {
                Row    row = upstream.next();
                String key = buildKey(row);
                if (!Objects.equals(key, prevKey[0]) && prevKey[0] != null) counter[0]++;
                prevKey[0] = key;
                String[] old = row.getValues();
                String[] nw  = new String[old.length + 1];
                System.arraycopy(old, 0, nw, 0, old.length);
                nw[old.length] = String.valueOf(counter[0]);
                return new Row(nw);
            }
            private String buildKey(Row r) {
                StringBuilder sb = new StringBuilder();
                for (int c : columns) { sb.append('|').append(c < r.fieldCount() ? r.getString(c) : ""); }
                return sb.toString();
            }
        };
    }
}
