package com.pentaho.migration.step.impl.routing;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.RoutingStep;

import java.util.*;

/**
 * Routes rows to two named branches: those matching a condition ("true" branch)
 * and those that don't ("false" branch). Equivalent to Pentaho's FilterRows step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}    — 0-based column index to test (resolved from name by KtrParser)</li>
 *   <li>{@code operator}  — comparison operator: EQ (default), NEQ, GT, LT, GTE, LTE, CONTAINS</li>
 *   <li>{@code value}     — value to compare against</li>
 *   <li>{@code trueStep}  — downstream step ID for matching rows</li>
 *   <li>{@code falseStep} — downstream step ID for non-matching rows</li>
 * </ul>
 */
public class FilterRowsStep implements RoutingStep {

    private int    column;
    private String operator;
    private String value;
    private String trueStep;
    private String falseStep;

    @Override
    public void configure(Map<String, String> params) {
        String col = params.getOrDefault("column", "0");
        try {
            column = Integer.parseInt(col);
        } catch (NumberFormatException e) {
            // Column name was not resolved to an index by the parser — default to 0
            column = 0;
        }
        operator  = params.getOrDefault("operator", "EQ").toUpperCase();
        value     = params.get("value");
        trueStep  = params.get("trueStep");
        falseStep = params.get("falseStep");
    }

    @Override
    public Map<String, Iterator<Row>> route(List<Iterator<Row>> inputs) {
        List<Row> trueRows  = new ArrayList<>();
        List<Row> falseRows = new ArrayList<>();

        Iterator<Row> upstream = inputs.get(0);
        while (upstream.hasNext()) {
            Row    row      = upstream.next();
            String fieldVal = column < row.fieldCount() ? row.getString(column) : null;
            (evaluate(fieldVal) ? trueRows : falseRows).add(row);
        }

        Map<String, Iterator<Row>> routes = new HashMap<>();
        if (trueStep  != null) routes.put(trueStep,  trueRows.iterator());
        if (falseStep != null) routes.put(falseStep, falseRows.iterator());
        return routes;
    }

    private boolean evaluate(String fieldVal) {
        if (fieldVal == null) return false;
        return switch (operator) {
            case "NEQ", "NOT_EQUAL", "NE" -> !Objects.equals(fieldVal, value);
            case "GT"                      -> compareNumOrStr(fieldVal, value) > 0;
            case "LT"                      -> compareNumOrStr(fieldVal, value) < 0;
            case "GTE", "GE", "GREATER_EQUAL" -> compareNumOrStr(fieldVal, value) >= 0;
            case "LTE", "LE", "LESS_EQUAL"    -> compareNumOrStr(fieldVal, value) <= 0;
            case "CONTAINS"                -> fieldVal.contains(value != null ? value : "");
            case "STARTS_WITH"             -> fieldVal.startsWith(value != null ? value : "");
            case "ENDS_WITH"               -> fieldVal.endsWith(value != null ? value : "");
            default /* EQ, EQUAL */        -> Objects.equals(fieldVal, value);
        };
    }

    /** Numeric comparison when both sides parse as doubles; string comparison otherwise. */
    private static int compareNumOrStr(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b != null ? b : "0"));
        } catch (NumberFormatException e) {
            return a.compareTo(b != null ? b : "");
        }
    }
}
