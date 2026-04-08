package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Aborts the pipeline when a condition is met on a field.
 * Equivalent to Pentaho's Abort step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}         — 0-based column index to check
 *   <li>{@code abortCondition} — "NULL", "NOT_NULL", "EMPTY", "NOT_EMPTY"
 *   <li>{@code message}        — exception message on abort
 * </ul>
 */
public class AbortStep extends AbstractStreamingStep {

    private int    column;
    private String abortCondition = "NOT_NULL";
    private String message        = "Pipeline aborted by AbortStep";

    @Override
    public void configure(Map<String, String> params) {
        column         = Integer.parseInt(params.getOrDefault("column", "0"));
        abortCondition = params.getOrDefault("abortCondition", "NOT_NULL").toUpperCase();
        message        = params.getOrDefault("message", message);
    }

    @Override
    protected Row transform(Row row) {
        String value = column < row.fieldCount() ? row.getString(column) : null;
        boolean abort = switch (abortCondition) {
            case "NULL"      -> value == null;
            case "NOT_NULL"  -> value != null;
            case "EMPTY"     -> value == null || value.isEmpty();
            case "NOT_EMPTY" -> value != null && !value.isEmpty();
            default          -> false;
        };
        if (abort) throw new RuntimeException(message + " [value=" + value + "]");
        return row;
    }
}
