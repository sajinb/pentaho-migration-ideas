package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Merges N pre-sorted streams into a single sorted stream using a k-way PriorityQueue.
 * Equivalent to Pentaho's SortedMerge step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns}   — comma-separated 0-based column indices for sort key
 *   <li>{@code ascending} — comma-separated "true"/"false" per column
 * </ul>
 */
public class SortedMergeStep implements Step {

    private int[]     columns;
    private boolean[] ascending;

    @Override
    public void configure(Map<String, String> params) {
        String[] colParts = params.get("columns").split(",");
        columns   = new int[colParts.length];
        ascending = new boolean[colParts.length];
        Arrays.fill(ascending, true);
        for (int i = 0; i < colParts.length; i++) columns[i] = Integer.parseInt(colParts[i].trim());
        if (params.containsKey("ascending")) {
            String[] ascParts = params.get("ascending").split(",");
            for (int i = 0; i < Math.min(ascParts.length, ascending.length); i++)
                ascending[i] = !"false".equalsIgnoreCase(ascParts[i].trim());
        }
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Comparator<String> nat = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<Row> cmp = buildComparator(nat);

        record Cursor(Row row, int source) {}
        Comparator<Cursor> cursorCmp = (a, b) -> cmp.compare(a.row(), b.row());
        PriorityQueue<Cursor> heap = new PriorityQueue<>(Math.max(1, inputs.size()), cursorCmp);

        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).hasNext()) heap.add(new Cursor(inputs.get(i).next(), i));
        }

        return new Iterator<Row>() {
            @Override public boolean hasNext() { return !heap.isEmpty(); }
            @Override public Row next() {
                Cursor c = heap.poll();
                if (inputs.get(c.source()).hasNext())
                    heap.add(new Cursor(inputs.get(c.source()).next(), c.source()));
                return c.row();
            }
        };
    }

    private Comparator<Row> buildComparator(Comparator<String> nat) {
        Comparator<Row> cmp = null;
        for (int i = 0; i < columns.length; i++) {
            final int col = columns[i]; final boolean asc = ascending[i];
            Comparator<Row> part = Comparator.comparing(r -> r.getString(col), nat);
            if (!asc) part = part.reversed();
            cmp = (cmp == null) ? part : cmp.thenComparing(part);
        }
        return cmp != null ? cmp : (a, b) -> 0;
    }
}
