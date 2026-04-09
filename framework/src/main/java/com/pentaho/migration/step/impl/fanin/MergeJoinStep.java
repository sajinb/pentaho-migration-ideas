package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Performs a sort-merge join of two pre-sorted streams.
 * Equivalent to Pentaho's MergeJoin step.
 *
 * <p>Requires inputs.get(0) = left stream, inputs.get(1) = right stream,
 * both sorted on their respective key columns.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code leftColumns}  — comma-separated 0-based key column indices in left stream
 *   <li>{@code rightColumns} — comma-separated 0-based key column indices in right stream
 *   <li>{@code joinType}     — "INNER" (default), "LEFT OUTER", "RIGHT OUTER", "FULL OUTER"
 * </ul>
 *
 * <p>Output: left fields followed by right fields (nulls for missing side).
 */
public class MergeJoinStep implements Step {

    private int[]  leftCols;
    private int[]  rightCols;
    private String joinType = "INNER";

    @Override
    public void configure(Map<String, String> params) {
        leftCols  = parseIntArray(params.get("leftColumns"));
        rightCols = parseIntArray(params.get("rightColumns"));
        joinType  = params.getOrDefault("joinType", "INNER").toUpperCase();
        if (leftCols.length == 0 || rightCols.length == 0) {
            throw new IllegalArgumentException(
                "MergeJoin: leftColumns/rightColumns are missing or not resolved to numeric indices. " +
                "Add a <fields> section to the upstream CsvInput/TextFileInput steps in your KTR " +
                "so the converter can resolve key column names to 0-based indices, then re-convert.");
        }
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        // Materialize both sides (required for FULL OUTER; acceptable for sorted-merge join)
        List<Row> left  = drain(inputs.get(0));
        List<Row> right = inputs.size() > 1 ? drain(inputs.get(1)) : List.of();

        List<Row> result = new ArrayList<>();
        int li = 0, ri = 0;

        while (li < left.size() && ri < right.size()) {
            int cmp = compareKeys(left.get(li), right.get(ri));
            if (cmp == 0) {
                // Advance right past all rows matching the current left key.
                int rStart = ri;
                while (ri < right.size() && compareKeys(left.get(li), right.get(ri)) == 0) ri++;
                // Cross-product of all left rows sharing this key × all matching right rows.
                for (int lj = li; lj < left.size() && compareKeys(left.get(lj), left.get(li)) == 0; lj++) {
                    for (int rj = rStart; rj < ri; rj++) {
                        result.add(merge(left.get(lj), right.get(rj)));
                    }
                }
                // Advance left past all rows sharing the same key.
                String[] matchKey = getKey(left.get(li), leftCols);
                while (li < left.size() && Arrays.equals(getKey(left.get(li), leftCols), matchKey)) li++;
            } else if (cmp < 0) {
                if (!joinType.equals("INNER") && !joinType.equals("RIGHT OUTER"))
                    result.add(merge(left.get(li), null));
                li++;
            } else {
                if (!joinType.equals("INNER") && !joinType.equals("LEFT OUTER"))
                    result.add(merge(null, right.get(ri)));
                ri++;
            }
        }
        // Trailing unmatched rows for OUTER joins.
        if (!joinType.equals("INNER") && !joinType.equals("RIGHT OUTER"))
            while (li < left.size())  { result.add(merge(left.get(li++),  null)); }
        if (!joinType.equals("INNER") && !joinType.equals("LEFT OUTER"))
            while (ri < right.size()) { result.add(merge(null, right.get(ri++))); }

        return result.iterator();
    }

    private int compareKeys(Row l, Row r) {
        for (int i = 0; i < leftCols.length && i < rightCols.length; i++) {
            String lv = leftCols[i]  < l.fieldCount() ? l.getString(leftCols[i])  : null;
            String rv = rightCols[i] < r.fieldCount() ? r.getString(rightCols[i]) : null;
            int c = Comparator.<String>nullsLast(Comparator.naturalOrder()).compare(lv, rv);
            if (c != 0) return c;
        }
        return 0;
    }

    private String[] getKey(Row row, int[] cols) {
        String[] key = new String[cols.length];
        for (int i = 0; i < cols.length; i++) key[i] = cols[i] < row.fieldCount() ? row.getString(cols[i]) : null;
        return key;
    }

    private Row merge(Row left, Row right) {
        int lLen = left  != null ? left.fieldCount()  : 0;
        int rLen = right != null ? right.fieldCount() : 0;
        // Estimate right field count from first available right row
        String[] values = new String[lLen + rLen];
        for (int i = 0; i < lLen; i++) values[i] = left  != null ? left.getString(i)  : null;
        for (int i = 0; i < rLen; i++) values[lLen + i] = right != null ? right.getString(i) : null;
        return new Row(values);
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
