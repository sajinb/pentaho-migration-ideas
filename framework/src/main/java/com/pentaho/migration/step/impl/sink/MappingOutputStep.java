package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Output port inside a Mapping (sub-transformation).
 * Collects rows so the parent {@code MappingStep} can retrieve them.
 * Equivalent to Pentaho's MappingOutput step.
 */
public class MappingOutputStep implements Step {

    private final List<Row> collected = new ArrayList<>();

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        while (upstream.hasNext()) collected.add(upstream.next());
        return Collections.emptyIterator();
    }

    /** Called by MappingStep after sub-transformation completes. */
    public Iterator<Row> getCollected() {
        return collected.iterator();
    }
}
