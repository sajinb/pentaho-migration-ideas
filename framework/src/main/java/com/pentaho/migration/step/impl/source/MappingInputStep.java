package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.util.*;

/**
 * Input port inside a Mapping (sub-transformation).
 * Receives the rows fed to it by the parent {@code MappingStep}.
 * Equivalent to Pentaho's MappingInput step.
 */
public class MappingInputStep extends AbstractSourceStep {

    private Iterator<Row> feed = Collections.emptyIterator();

    /** Called by MappingStep before executing the sub-transformation. */
    public void feed(Iterator<Row> rows) {
        this.feed = rows;
    }

    @Override
    protected Iterator<Row> readRows() {
        return feed;
    }
}
