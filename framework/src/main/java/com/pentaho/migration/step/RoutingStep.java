package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implemented by steps that conditionally route rows to named output branches.
 *
 * <p>Used by {@code SwitchCase} and {@code FilterRows}. Instead of broadcasting
 * all rows to all downstream steps (the default fan-out via BroadcastBuffer),
 * a RoutingStep sends each row to exactly one branch.
 *
 * <p>The engine calls {@link #route(List)} and wires each map key to the downstream
 * step whose hop "to" ID matches. The downstream step IDs used as map keys must be
 * injected at {@link #configure(Map)} time (e.g. via params {@code "trueStep"} /
 * {@code "falseStep"} for FilterRows, or case values for SwitchCase).
 */
public interface RoutingStep extends Step {

    /**
     * Route rows from upstream to named output branches.
     *
     * @param inputs upstream iterators (same contract as {@link Step#apply(List)})
     * @return map of target step ID → iterator of rows destined for that branch
     */
    Map<String, Iterator<Row>> route(List<Iterator<Row>> inputs) throws Exception;

    @Override
    default Iterator<Row> apply(List<Iterator<Row>> inputs) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " is a RoutingStep — call route() instead of apply()");
    }
}
