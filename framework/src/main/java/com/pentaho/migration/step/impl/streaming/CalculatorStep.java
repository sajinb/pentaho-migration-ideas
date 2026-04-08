package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Evaluates arithmetic expressions on numeric fields and appends the result.
 * Equivalent to Pentaho's Calculator step (Phase 1: basic arithmetic only).
 *
 * <p>Params:
 * <ul>
 *   <li>{@code colA}      — 0-based index of first operand
 *   <li>{@code colB}      — 0-based index of second operand
 *   <li>{@code operation} — "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MODULO"
 * </ul>
 *
 * <p>Appends one new field (the result) to each row.
 */
public class CalculatorStep extends AbstractStreamingStep {

    private int    colA;
    private int    colB;
    private String operation;

    @Override
    public void configure(Map<String, String> params) {
        colA      = Integer.parseInt(params.get("colA"));
        colB      = Integer.parseInt(params.get("colB"));
        operation = params.getOrDefault("operation", "ADD").toUpperCase();
    }

    @Override
    protected Row transform(Row row) {
        String resultStr;
        try {
            double a = Double.parseDouble(row.getString(colA));
            double b = Double.parseDouble(row.getString(colB));
            double r = switch (operation) {
                case "ADD"      -> a + b;
                case "SUBTRACT" -> a - b;
                case "MULTIPLY" -> a * b;
                case "DIVIDE"   -> a / b;
                case "MODULO"   -> a % b;
                default         -> throw new IllegalArgumentException("Unknown operation: " + operation);
            };
            resultStr = String.valueOf(r);
        } catch (NumberFormatException e) {
            resultStr = null;
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = resultStr;
        return new Row(nw);
    }
}
