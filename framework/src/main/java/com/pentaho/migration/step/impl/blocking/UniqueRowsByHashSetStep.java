package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.*;

/**
 * Removes duplicate rows using an in-memory HashSet (no sort required).
 * Equivalent to Pentaho's UniqueRowsByHashSet step.
 * Use only when the full set of unique keys fits in heap.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns} — comma-separated 0-based key column indices; omit = all fields
 * </ul>
 */
public class UniqueRowsByHashSetStep extends AbstractBlockingStep {

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
    protected Iterator<Row> process(List<Row> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            String key = buildKey(row);
            if (seen.add(key)) result.add(row);
        }
        return result.iterator();
    }

    private String buildKey(Row row) {
        if (columns == null) return Arrays.toString(row.getValues());
        StringBuilder sb = new StringBuilder();
        for (int c : columns) sb.append('|').append(c < row.fieldCount() ? row.getString(c) : "");
        return sb.toString();
    }
}
