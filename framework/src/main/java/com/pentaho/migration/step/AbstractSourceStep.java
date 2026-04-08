package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.Iterator;
import java.util.List;

/**
 * Base class for source steps that produce rows from an external source
 * (file, database, system, etc.) and receive no upstream input.
 */
public abstract class AbstractSourceStep implements Step {

    @Override
    public final Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        return readRows();
    }

    /**
     * Open the source and return a streaming iterator of rows.
     * The iterator is consumed exactly once by the engine.
     */
    protected abstract Iterator<Row> readRows() throws Exception;
}
