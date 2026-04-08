package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Base class for single-row, single-input streaming transformations.
 * Each input row is independently mapped to one output row.
 * Approximately 40 of the 85 KTR step types extend this class.
 */
public abstract class AbstractStreamingStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        return new Iterator<Row>() {
            @Override public boolean hasNext() { return upstream.hasNext(); }
            @Override public Row next() {
                if (!upstream.hasNext()) throw new NoSuchElementException();
                return transform(upstream.next());
            }
        };
    }

    /**
     * Transform one input row into one output row.
     * Implementations should not retain references to the input row.
     */
    protected abstract Row transform(Row row);
}
