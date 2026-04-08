package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.*;

/**
 * Groups rows by key columns and computes aggregates per group.
 * Requires input to be sorted by the group keys (use SortRowsStep before this).
 * Equivalent to Pentaho's GroupBy step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code groupColumns} — comma-separated 0-based key column indices
 *   <li>{@code aggColumns}   — comma-separated 0-based aggregate column indices
 *   <li>{@code aggFunctions} — comma-separated functions: COUNT, SUM, MIN, MAX, AVG, FIRST, LAST
 * </ul>
 *
 * <p>Output: [groupCol0, groupCol1, ..., agg0, agg1, ...]
 */
public class GroupByStep extends AbstractBlockingStep {

    private int[]    groupCols;
    private int[]    aggCols;
    private String[] aggFunctions;

    @Override
    public void configure(Map<String, String> params) {
        groupCols    = parseIntArray(params.get("groupColumns"));
        aggCols      = parseIntArray(params.getOrDefault("aggColumns", ""));
        aggFunctions = params.getOrDefault("aggFunctions", "").split(",");
    }

    @Override
    protected Iterator<Row> process(List<Row> rows) {
        if (rows.isEmpty()) return Collections.emptyIterator();

        List<Row> result = new ArrayList<>();
        String[] currentKey = buildKey(rows.get(0));
        List<Row> group = new ArrayList<>();

        for (Row row : rows) {
            String[] key = buildKey(row);
            if (Arrays.equals(key, currentKey)) {
                group.add(row);
            } else {
                result.add(aggregate(currentKey, group));
                currentKey = key;
                group = new ArrayList<>();
                group.add(row);
            }
        }
        if (!group.isEmpty()) result.add(aggregate(currentKey, group));
        return result.iterator();
    }

    private String[] buildKey(Row row) {
        String[] key = new String[groupCols.length];
        for (int i = 0; i < groupCols.length; i++)
            key[i] = groupCols[i] < row.fieldCount() ? row.getString(groupCols[i]) : null;
        return key;
    }

    private Row aggregate(String[] key, List<Row> group) {
        String[] values = new String[groupCols.length + aggCols.length];
        System.arraycopy(key, 0, values, 0, key.length);
        for (int i = 0; i < aggCols.length; i++) {
            int    col = aggCols[i];
            String fn  = i < aggFunctions.length ? aggFunctions[i].trim().toUpperCase() : "COUNT";
            values[groupCols.length + i] = compute(group, col, fn);
        }
        return new Row(values);
    }

    private String compute(List<Row> group, int col, String fn) {
        return switch (fn) {
            case "COUNT" -> String.valueOf(group.size());
            case "SUM"   -> numericOp(group, col, fn);
            case "MIN"   -> numericOp(group, col, fn);
            case "MAX"   -> numericOp(group, col, fn);
            case "AVG"   -> numericOp(group, col, fn);
            case "FIRST" -> col < group.get(0).fieldCount() ? group.get(0).getString(col) : null;
            case "LAST"  -> { Row last = group.get(group.size()-1); yield col < last.fieldCount() ? last.getString(col) : null; }
            default      -> String.valueOf(group.size());
        };
    }

    private String numericOp(List<Row> group, int col, String fn) {
        double sum = 0; double min = Double.MAX_VALUE; double max = -Double.MAX_VALUE;
        int count = 0;
        for (Row r : group) {
            if (col >= r.fieldCount() || r.getString(col) == null) continue;
            try {
                double v = Double.parseDouble(r.getString(col));
                sum += v; min = Math.min(min, v); max = Math.max(max, v); count++;
            } catch (NumberFormatException ignored) {}
        }
        if (count == 0) return null;
        return switch (fn) {
            case "SUM" -> String.valueOf(sum);
            case "MIN" -> String.valueOf(min);
            case "MAX" -> String.valueOf(max);
            case "AVG" -> String.valueOf(sum / count);
            default    -> null;
        };
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] parts = s.split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) arr[i] = Integer.parseInt(parts[i].trim());
        return arr;
    }
}
