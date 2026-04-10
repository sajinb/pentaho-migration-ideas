package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Evaluates arithmetic expressions on numeric fields and appends the result.
 * Equivalent to Pentaho's Calculator step (basic arithmetic operations).
 *
 * <p>Params:
 * <ul>
 *   <li>{@code colA}      — 0-based index of the first operand field (required)</li>
 *   <li>{@code colB}      — 0-based index of the second operand field
 *                           (<em>mutually exclusive with {@code valueB}</em>)</li>
 *   <li>{@code valueB}    — literal constant for the second operand
 *                           (used when second operand is not a row field)</li>
 *   <li>{@code operation} — ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO (default ADD)</li>
 * </ul>
 *
 * <p>Appends one new field (the result) to each row.
 */
public class CalculatorStep extends AbstractStreamingStep {

    private int    colA;
    private int    colB   = -1;   // -1 means "use valueB instead"
    private double valueB = 0.0;  // constant when colB == -1
    private String operation;

    @Override
    public void configure(Map<String, String> params) {
        String colAStr = params.get("colA");
        if (colAStr == null) throw new IllegalArgumentException(
                "CalculatorStep: 'colA' param is required (0-based index of first operand). " +
                "Ensure the upstream CsvInput/TextFileInput step has a <fields> section so the " +
                "converter can resolve field names to column indices.");
        colA = Integer.parseInt(colAStr);

        String colBStr = params.get("colB");
        if (colBStr != null) {
            colB   = Integer.parseInt(colBStr);
        } else {
            colB   = -1;
            String vb = params.get("valueB");
            valueB = vb != null ? Double.parseDouble(vb) : 0.0;
        }
        operation = params.getOrDefault("operation", "ADD").toUpperCase();
    }

    @Override
    protected Row transform(Row row) {
        String resultStr;
        try {
            double a = Double.parseDouble(row.getString(colA));
            double b = (colB >= 0) ? Double.parseDouble(row.getString(colB)) : valueB;
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
