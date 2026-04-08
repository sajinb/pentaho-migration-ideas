package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractBlockingStep;

import java.util.*;

/**
 * Unpivots rows: one wide row becomes multiple narrow rows.
 * Equivalent to Pentaho's Normaliser step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code keyColumns}    — comma-separated 0-based indices of columns to keep in each output row
 *   <li>{@code valueColumns}  — comma-separated 0-based indices of columns to unpivot
 *   <li>{@code valueNames}    — comma-separated labels for each valueColumn (become type field value)
 *   <li>{@code typeFieldName} — name of the new "type" field (informational only, not used by engine)
 * </ul>
 *
 * <p>Output per input row: N rows (one per valueColumn): [keyCol0, ..., typeLabel, value]
 */
public class NormaliserStep extends AbstractBlockingStep {

    private int[]    keyCols;
    private int[]    valueCols;
    private String[] valueNames;

    @Override
    public void configure(Map<String, String> params) {
        keyCols    = parseIntArray(params.get("keyColumns"));
        valueCols  = parseIntArray(params.get("valueColumns"));
        valueNames = params.get("valueNames").split(",");
        for (int i = 0; i < valueNames.length; i++) valueNames[i] = valueNames[i].trim();
    }

    @Override
    protected Iterator<Row> process(List<Row> rows) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            String[] keyVals = new String[keyCols.length];
            for (int i = 0; i < keyCols.length; i++)
                keyVals[i] = keyCols[i] < row.fieldCount() ? row.getString(keyCols[i]) : null;

            for (int i = 0; i < valueCols.length; i++) {
                String label = i < valueNames.length ? valueNames[i] : String.valueOf(i);
                String value = valueCols[i] < row.fieldCount() ? row.getString(valueCols[i]) : null;
                String[] nw  = new String[keyCols.length + 2];
                System.arraycopy(keyVals, 0, nw, 0, keyCols.length);
                nw[keyCols.length]     = label;
                nw[keyCols.length + 1] = value;
                result.add(new Row(nw));
            }
        }
        return result.iterator();
    }

    private static int[] parseIntArray(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] p = s.split(","); int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
        return a;
    }
}
