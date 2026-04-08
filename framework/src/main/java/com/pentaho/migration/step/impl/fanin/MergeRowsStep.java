package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Compares two sorted streams and emits rows flagged as NEW, CHANGED, DELETED, or IDENTICAL.
 * Equivalent to Pentaho's MergeRows step.
 *
 * <p>inputs.get(0) = reference (old) stream, inputs.get(1) = compare (new) stream.
 * Both must be sorted on the same key columns.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code keyColumns}  — comma-separated 0-based key column indices (join key)
 * </ul>
 *
 * <p>Output: compare-stream fields + a "flagField" appended: "new", "changed", "deleted", "identical"
 */
public class MergeRowsStep implements Step {

    private int[] keyCols;

    @Override
    public void configure(Map<String, String> params) {
        keyCols = parseIntArray(params.getOrDefault("keyColumns", "0"));
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        List<Row> reference = drain(inputs.get(0));
        List<Row> compare   = inputs.size() > 1 ? drain(inputs.get(1)) : List.of();

        Map<String, Row> refMap = new LinkedHashMap<>();
        for (Row r : reference) refMap.put(buildKey(r), r);

        List<Row> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Row cRow : compare) {
            String key = buildKey(cRow);
            seen.add(key);
            Row refRow = refMap.get(key);
            String flag;
            if (refRow == null) {
                flag = "new";
            } else if (rowEquals(refRow, cRow)) {
                flag = "identical";
            } else {
                flag = "changed";
            }
            result.add(appendFlag(cRow, flag));
        }
        for (Row refRow : reference) {
            if (!seen.contains(buildKey(refRow))) result.add(appendFlag(refRow, "deleted"));
        }
        return result.iterator();
    }

    private String buildKey(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int c : keyCols) sb.append('|').append(c < row.fieldCount() ? row.getString(c) : "");
        return sb.toString();
    }

    private boolean rowEquals(Row a, Row b) {
        if (a.fieldCount() != b.fieldCount()) return false;
        for (int i = 0; i < a.fieldCount(); i++)
            if (!Objects.equals(a.getString(i), b.getString(i))) return false;
        return true;
    }

    private Row appendFlag(Row row, String flag) {
        String[] old = row.getValues();
        String[] nw  = Arrays.copyOf(old, old.length + 1);
        nw[old.length] = flag;
        return new Row(nw);
    }

    private List<Row> drain(Iterator<Row> it) {
        List<Row> rows = new ArrayList<>();
        while (it.hasNext()) rows.add(it.next());
        return rows;
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] p = s.split(","); int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
        return a;
    }
}
