package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Removes consecutive duplicate rows (requires sorted input on the key columns).
 * Equivalent to Pentaho's Unique step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns} — comma-separated 0-based key column indices; omit = compare all fields
 * </ul>
 */
public class UniqueStep implements Step {

    private int[] columns = null;

    @Override
    public void configure(Map<String, String> params) {
        if (params.containsKey("columns")) {
            String[] parts = params.get("columns").split(",");
            columns = new int[parts.length];
            for (int i = 0; i < parts.length; i++) columns[i] = Integer.parseInt(parts[i].trim());
        }
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        final int[] cols = columns;
        return new Iterator<Row>() {
            Row prev = null;
            Row next = advance();

            Row advance() {
                while (upstream.hasNext()) {
                    Row candidate = upstream.next();
                    if (prev == null || !keyEquals(prev, candidate, cols)) {
                        prev = candidate;
                        return candidate;
                    }
                }
                return null;
            }

            @Override public boolean hasNext() { return next != null; }
            @Override public Row next() {
                Row result = next;
                next = advance();
                return result;
            }
        };
    }

    private static boolean keyEquals(Row a, Row b, int[] cols) {
        if (cols == null) {
            if (a.fieldCount() != b.fieldCount()) return false;
            for (int i = 0; i < a.fieldCount(); i++)
                if (!Objects.equals(a.getString(i), b.getString(i))) return false;
            return true;
        }
        for (int c : cols)
            if (!Objects.equals(
                    c < a.fieldCount() ? a.getString(c) : null,
                    c < b.fieldCount() ? b.getString(c) : null)) return false;
        return true;
    }
}
