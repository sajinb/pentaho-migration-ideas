package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.*;

/**
 * Groups rows by key columns using an in-memory map (no sort required).
 * Equivalent to Pentaho's MemoryGroupBy step.
 * Use only when the number of distinct groups fits in heap.
 *
 * <p>Params: same as {@link GroupByStep}.
 */
public class MemoryGroupByStep extends AbstractBlockingStep {

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
        LinkedHashMap<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = buildKeyString(row);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Row> result = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : groups.entrySet()) {
            String[] key = buildKeyArray(e.getValue().get(0));
            result.add(aggregate(key, e.getValue()));
        }
        return result.iterator();
    }

    private String buildKeyString(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int c : groupCols) sb.append('|').append(c < row.fieldCount() ? row.getString(c) : "");
        return sb.toString();
    }

    private String[] buildKeyArray(Row row) {
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
            values[groupCols.length + i] = computeAgg(group, col, fn);
        }
        return new Row(values);
    }

    private String computeAgg(List<Row> group, int col, String fn) {
        if ("COUNT".equals(fn)) return String.valueOf(group.size());
        if ("FIRST".equals(fn)) return col < group.get(0).fieldCount() ? group.get(0).getString(col) : null;
        if ("LAST".equals(fn))  { Row last = group.get(group.size()-1); return col < last.fieldCount() ? last.getString(col) : null; }
        double sum = 0; double min = Double.MAX_VALUE; double max = -Double.MAX_VALUE; int cnt = 0;
        for (Row r : group) {
            if (col >= r.fieldCount() || r.getString(col) == null) continue;
            try { double v = Double.parseDouble(r.getString(col)); sum += v; min = Math.min(min,v); max = Math.max(max,v); cnt++; }
            catch (NumberFormatException ignored) {}
        }
        if (cnt == 0) return null;
        return switch (fn) { case "SUM" -> String.valueOf(sum); case "MIN" -> String.valueOf(min); case "MAX" -> String.valueOf(max); case "AVG" -> String.valueOf(sum/cnt); default -> null; };
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] p = s.split(","); int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
        return a;
    }
}
