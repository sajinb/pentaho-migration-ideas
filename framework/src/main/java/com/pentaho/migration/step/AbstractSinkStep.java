package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Base class for sink steps that consume all input rows and write to an external
 * destination (file, database, etc.) with no downstream output.
 * Returns an empty iterator to the engine.
 */
public abstract class AbstractSinkStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        open();
        try {
            Iterator<Row> upstream = inputs.get(0);
            while (upstream.hasNext()) writeRow(upstream.next());
        } finally {
            close();
        }
        return Collections.emptyIterator();
    }

    /** Open the destination resource (file, connection, etc.). */
    protected abstract void open() throws Exception;

    /** Write one row to the destination. */
    protected abstract void writeRow(Row row) throws Exception;

    /** Flush and close the destination resource. */
    protected abstract void close() throws Exception;
}
