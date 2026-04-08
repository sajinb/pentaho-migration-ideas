package com.pentaho.migration.step.impl.routing;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.RoutingStep;

import java.util.*;

/**
 * Routes rows to named branches based on the value of a field.
 * Equivalent to Pentaho's SwitchCase step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}      — 0-based column index to switch on
 *   <li>{@code case.VALUE}  — step ID for rows where field == VALUE
 *                             (one param per case, e.g. case.ACTIVE=step_active)
 *   <li>{@code defaultStep} — step ID for rows that match no case
 * </ul>
 *
 * <p>Rows are materialized per-branch in memory. For very large fan-out,
 * consider using multiple FilterRows steps with BroadcastBuffer upstream.
 */
public class SwitchCaseStep implements RoutingStep {

    private int                  column;
    private final Map<String, String> caseMap   = new LinkedHashMap<>();  // value → stepId
    private String               defaultStep;

    @Override
    public void configure(Map<String, String> params) {
        column      = Integer.parseInt(params.getOrDefault("column", "0"));
        defaultStep = params.get("defaultStep");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().startsWith("case.")) {
                String caseValue = e.getKey().substring("case.".length());
                caseMap.put(caseValue, e.getValue());
            }
        }
    }

    @Override
    public Map<String, Iterator<Row>> route(List<Iterator<Row>> inputs) {
        // bucket: stepId → accumulated rows
        Map<String, List<Row>> buckets = new LinkedHashMap<>();
        for (String stepId : caseMap.values()) buckets.computeIfAbsent(stepId, k -> new ArrayList<>());
        if (defaultStep != null) buckets.computeIfAbsent(defaultStep, k -> new ArrayList<>());

        Iterator<Row> upstream = inputs.get(0);
        while (upstream.hasNext()) {
            Row    row      = upstream.next();
            String fieldVal = column < row.fieldCount() ? row.getString(column) : null;
            String target   = fieldVal != null ? caseMap.get(fieldVal) : null;
            if (target == null) target = defaultStep;
            if (target != null) buckets.computeIfAbsent(target, k -> new ArrayList<>()).add(row);
        }

        Map<String, Iterator<Row>> routes = new HashMap<>();
        buckets.forEach((stepId, rows) -> routes.put(stepId, rows.iterator()));
        return routes;
    }
}
