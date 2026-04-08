package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Applies string operations (trim, upper, lower, pad) to a field.
 * Equivalent to Pentaho's StringOperations step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}    — 0-based column index
 *   <li>{@code operation} — "trim", "ltrim", "rtrim", "upper", "lower", "initcap"
 * </ul>
 */
public class StringOperationsStep extends AbstractStreamingStep {

    private int    column;
    private String operation;

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.get("column"));
        operation = params.getOrDefault("operation", "trim").toLowerCase();
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length && values[column] != null) {
            String v = values[column];
            values[column] = switch (operation) {
                case "trim"    -> v.trim();
                case "ltrim"   -> v.stripLeading();
                case "rtrim"   -> v.stripTrailing();
                case "upper"   -> v.toUpperCase();
                case "lower"   -> v.toLowerCase();
                case "initcap" -> initCap(v);
                default        -> v.trim();
            };
        }
        return new Row(values);
    }

    private static String initCap(String s) {
        if (s.isEmpty()) return s;
        String[] words = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
