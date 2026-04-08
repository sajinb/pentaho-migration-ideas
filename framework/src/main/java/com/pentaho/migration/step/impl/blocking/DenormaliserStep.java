package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.*;

/**
 * Pivots rows: multiple rows with the same key become one wide row.
 * Equivalent to Pentaho's Denormaliser step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code keyColumns}   — comma-separated 0-based indices of the grouping key
 *   <li>{@code pivotColumn}  — 0-based index of the column whose values become column names
 *   <li>{@code valueColumn}  — 0-based index of the column whose values fill the pivoted columns
 *   <li>{@code pivotValues}  — comma-separated expected pivot values (defines output column order)
 * </ul>
 *
 * <p>Output: [keyCol0, ..., pivotVal0, pivotVal1, ...]
 */
public class DenormaliserStep extends AbstractBlockingStep {

    private int[]    keyCols;
    private int      pivotCol;
    private int      valueCol;
    private String[] pivotValues;

    @Override
    public void configure(Map<String, String> params) {
        keyCols     = parseIntArray(params.get("keyColumns"));
        pivotCol    = Integer.parseInt(params.get("pivotColumn"));
        valueCol    = Integer.parseInt(params.get("valueColumn"));
        pivotValues = params.get("pivotValues").split(",");
        for (int i = 0; i < pivotValues.length; i++) pivotValues[i] = pivotValues[i].trim();
    }

    @Override
    protected Iterator<Row> process(List<Row> rows) {
        LinkedHashMap<String, Map<String, String>> groups = new LinkedHashMap<>();
        Map<String, String[]> keyArrays = new LinkedHashMap<>();

        for (Row row : rows) {
            String keyStr = buildKeyStr(row);
            groups.computeIfAbsent(keyStr, k -> new HashMap<>());
            if (!keyArrays.containsKey(keyStr)) keyArrays.put(keyStr, buildKeyArray(row));
            String pivotKey = pivotCol < row.fieldCount() ? row.getString(pivotCol) : null;
            String value    = valueCol < row.fieldCount() ? row.getString(valueCol) : null;
            if (pivotKey != null) groups.get(keyStr).put(pivotKey, value);
        }

        List<Row> result = new ArrayList<>();
        for (String keyStr : groups.keySet()) {
            String[] keyVals = keyArrays.get(keyStr);
            Map<String, String> pivotMap = groups.get(keyStr);
            String[] values = new String[keyCols.length + pivotValues.length];
            System.arraycopy(keyVals, 0, values, 0, keyCols.length);
            for (int i = 0; i < pivotValues.length; i++)
                values[keyCols.length + i] = pivotMap.get(pivotValues[i]);
            result.add(new Row(values));
        }
        return result.iterator();
    }

    private String buildKeyStr(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int c : keyCols) sb.append('|').append(c < row.fieldCount() ? row.getString(c) : "");
        return sb.toString();
    }

    private String[] buildKeyArray(Row row) {
        String[] key = new String[keyCols.length];
        for (int i = 0; i < keyCols.length; i++)
            key[i] = keyCols[i] < row.fieldCount() ? row.getString(keyCols[i]) : null;
        return key;
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] p = s.split(","); int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
        return a;
    }
}
