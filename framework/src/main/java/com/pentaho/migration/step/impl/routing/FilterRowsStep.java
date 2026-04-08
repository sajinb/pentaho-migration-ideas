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
 *   <li>{@code column}     — 0-based column index to test
 *   <li>{@code value}      — value to compare against (equality)
 *   <li>{@code trueStep}   — downstream step ID for matching rows (required)
 *   <li>{@code falseStep}  — downstream step ID for non-matching rows (required)
 * </ul>
 *
 * <p>The route() method materializes both lists in memory. For large datasets,
 * consider splitting into two separate source paths with BroadcastBuffer instead.
 */
public class FilterRowsStep implements RoutingStep {

    private int    column;
    private String value;
    private String trueStep;
    private String falseStep;

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.getOrDefault("column", "0"));
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
            Row    row     = upstream.next();
            String fieldVal = column < row.fieldCount() ? row.getString(column) : null;
            boolean matches = Objects.equals(fieldVal, value);
            (matches ? trueRows : falseRows).add(row);
        }

        Map<String, Iterator<Row>> routes = new HashMap<>();
        if (trueStep  != null) routes.put(trueStep,  trueRows.iterator());
        if (falseStep != null) routes.put(falseStep, falseRows.iterator());
        return routes;
    }
}
